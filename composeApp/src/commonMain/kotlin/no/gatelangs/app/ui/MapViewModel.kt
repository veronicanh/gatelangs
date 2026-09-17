package no.gatelangs.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.gatelangs.app.data.RoadRepository
import no.gatelangs.app.data.RoadSource
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.location.LocationSource
import no.gatelangs.app.location.SimulatedWalker
import no.gatelangs.app.location.createRealLocationSource
import no.gatelangs.app.map.MapState
import no.gatelangs.app.map.TileCache
import no.gatelangs.app.map.TileSource
import no.gatelangs.app.map.createHttpClient
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.RoadNetwork
import no.gatelangs.app.storage.Storage
import no.gatelangs.app.storage.WalkedCodec
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the map screen is currently doing. */
sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val message: String) : LoadState
    data class Ready(val network: RoadNetwork, val source: RoadSource) : LoadState
}

class MapViewModel : ViewModel() {

    private val http = createHttpClient()
    private val repository = RoadRepository(http)
    private val storage = Storage()

    val mapState = MapState(center = INITIAL_CENTER, zoom = INITIAL_ZOOM)
    val tiles = TileCache(http, viewModelScope, TileSource.OpenStreetMap)

    var loadState: LoadState by mutableStateOf(LoadState.Loading)
        private set

    var coverage: Coverage? by mutableStateOf(null)
        private set

    var position: LatLon? by mutableStateOf(null)
        private set

    var positionAccuracyM: Double? by mutableStateOf(null)
        private set

    var isTracking: Boolean by mutableStateOf(false)
        private set

    var followPosition: Boolean by mutableStateOf(true)

    var locationLabel: String by mutableStateOf("")
        private set

    /** Bumped whenever coverage changes, so the map redraws without diffing a BooleanArray. */
    var coverageRevision: Int by mutableStateOf(0)
        private set

    private var trackingJob: Job? = null
    private var lastPersist: TimeMark? = null

    init {
        load()
    }

    fun load() {
        loadState = LoadState.Loading
        viewModelScope.launch {
            val result = runCatching { repository.load(RoadRepository.DEFAULT_AREA) }
            loadState = result.fold(
                onSuccess = { loaded ->
                    val restored = Coverage(loaded.network).apply {
                        // Segment ids are positional, so they are only meaningful against
                        // the same network. Keying the saved state by the network's shape
                        // means a refreshed road download starts clean instead of lighting
                        // up unrelated streets.
                        val saved = storage.read(walkedKey(loaded.network.segments.size))
                        if (saved != null) restore(WalkedCodec.decode(saved))
                    }
                    coverage = restored
                    coverageRevision++
                    mapState.moveTo(loaded.network.bounds.center)
                    LoadState.Ready(loaded.network, loaded.source)
                },
                onFailure = { LoadState.Failed(it.message ?: it::class.simpleName ?: "unknown error") },
            )
        }
    }

    fun toggleTracking() {
        if (isTracking) stopTracking() else startTracking()
    }

    private fun startTracking() {
        val ready = loadState as? LoadState.Ready ?: return
        val activeCoverage = coverage ?: return

        // Real GPS where the platform has it; the walker keeps the pipeline exercisable
        // on a laptop, which is also how this gets demoed indoors.
        val source: LocationSource = createRealLocationSource() ?: SimulatedWalker(ready.network)
        locationLabel = source.label
        isTracking = true

        trackingJob = viewModelScope.launch {
            source.fixes().collect { fix ->
                position = fix.position
                positionAccuracyM = fix.accuracyM
                if (activeCoverage.record(fix).isNotEmpty()) {
                    coverageRevision++
                    persist(ready.network.segments.size, activeCoverage)
                }
                if (followPosition) mapState.moveTo(fix.position)
            }
        }
    }

    /**
     * Writes walked state at most once every few seconds. Saving on every matched fix
     * would mean a disk or localStorage write every second of a walk, for a value that
     * barely changes.
     */
    private fun persist(networkSize: Int, coverage: Coverage) {
        val since = lastPersist
        if (since != null && since.elapsedNow() < PERSIST_INTERVAL) return
        lastPersist = TimeSource.Monotonic.markNow()
        viewModelScope.launch {
            storage.write(walkedKey(networkSize), WalkedCodec.encode(coverage.walkedIds()))
        }
    }

    private fun walkedKey(networkSize: Int): String = "walked-$networkSize"

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        isTracking = false

        // Flush on stop, so the throttle above can never lose the tail of a walk.
        val network = (loadState as? LoadState.Ready)?.network
        val active = coverage
        if (network != null && active != null) {
            lastPersist = null
            persist(network.segments.size, active)
        }
    }

    override fun onCleared() {
        stopTracking()
        http.close()
    }

    companion object {
        /** Central Oslo — replaced by the loaded network's own centre once it arrives. */
        private val INITIAL_CENTER = LatLon(59.9225, 10.7600)
        private const val INITIAL_ZOOM = 15.0
        private val PERSIST_INTERVAL = 5.seconds
    }
}
