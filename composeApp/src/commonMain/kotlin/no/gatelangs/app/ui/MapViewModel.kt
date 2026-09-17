package no.gatelangs.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.gatelangs.app.data.RoadRepository
import no.gatelangs.app.data.RoadSource
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.location.KeyboardWalker
import no.gatelangs.app.location.LocationSource
import no.gatelangs.app.location.createRealLocationSource
import no.gatelangs.app.map.MapState
import no.gatelangs.app.map.TileCache
import no.gatelangs.app.map.TileSource
import no.gatelangs.app.map.createHttpClient
import no.gatelangs.app.model.Achievement
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.Milestones
import no.gatelangs.app.model.RoadNetwork
import no.gatelangs.app.storage.Storage
import no.gatelangs.app.storage.WalkedCodec
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Which screen is showing.
 *
 * Two screens and one edge between them do not pay for a navigation library, a back
 * stack or a route type — this is the whole of it.
 */
enum class Screen { MAP, PROGRESS }

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
    val tiles = TileCache(http, viewModelScope, TileSource.CartoDarkMatter)

    var loadState: LoadState by mutableStateOf(LoadState.Loading)
        private set

    var screen: Screen by mutableStateOf(Screen.MAP)
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

    /**
     * Whether this platform can offer real GPS at all.
     *
     * Asked once: the answer is a property of the platform, not of the moment. It is
     * what decides whether choosing between GPS and the keyboard is a choice worth
     * putting on screen.
     */
    val hasRealGps: Boolean = createRealLocationSource() != null

    /**
     * Drive the position from the keyboard rather than from GPS.
     *
     * Defaults to on wherever there is no GPS to prefer. It stays settable on top of
     * GPS because a desktop browser *has* `navigator.geolocation` and it is no use at
     * all indoors — it would pin you to one spot for the whole demo.
     */
    val useKeyboard: Boolean get() = keyboardControl

    /**
     * Backs [useKeyboard].
     *
     * Separate because a `var` with a private setter already compiles to
     * `setUseKeyboard(Z)V` on the JVM, which [setUseKeyboard] would then clash with.
     */
    private var keyboardControl: Boolean by mutableStateOf(!hasRealGps)

    /**
     * The walker the keyboard drives, while one is running.
     *
     * Exposed so the screen can feed it key events. Null when nothing is tracking, or
     * when the platform has real GPS and the keys have nothing to steer.
     */
    var keyboardWalker: KeyboardWalker? by mutableStateOf(null)
        private set

    /**
     * The achievement on screen, if any.
     *
     * One at a time: several streets can cross a line on the same fix at a junction, and
     * stacking the popups would cover the map you are walking on. [pendingAchievements]
     * holds the rest.
     */
    var achievement: Achievement? by mutableStateOf(null)
        private set

    /**
     * Whether it should be on screen.
     *
     * Separate from [achievement] so the banner still has something to draw while it
     * animates out — the content outlives the visibility by design.
     */
    var achievementVisible: Boolean by mutableStateOf(false)
        private set

    /** Bumped whenever coverage changes, so the map redraws without diffing a BooleanArray. */
    var coverageRevision: Int by mutableStateOf(0)
        private set

    private var trackingJob: Job? = null
    private var lastPersist: TimeMark? = null
    private var milestones: Milestones? = null
    private var announcer: Job? = null
    private val pendingAchievements = ArrayDeque<Achievement>()

    /** Where walked state is saved for the network now loaded. Empty until one is. */
    private var walkedKey: String = ""

    init {
        load()
    }

    fun load() {
        loadState = LoadState.Loading
        viewModelScope.launch {
            val result = runCatching { repository.load(RoadRepository.DEFAULT_AREA) }
            loadState = result.fold(
                onSuccess = { loaded ->
                    walkedKey = "walked-${loaded.source.name.lowercase()}-" +
                        "${loaded.network.segments.size}"
                    val restored = Coverage(loaded.network).apply {
                        // Segment ids are positional, so they are only meaningful against
                        // the same network. Keying the saved state by the network's shape
                        // means a refreshed road download starts clean instead of lighting
                        // up unrelated streets.
                        //
                        // Keyed by source as well as size, because the two paths can
                        // agree on a segment count while disagreeing on what segment 400
                        // is: the snapshot is ordered by way id, Overpass returns its own
                        // order. Restoring one into the other would light up a scatter of
                        // streets nobody walked.
                        val saved = storage.read(walkedKey)
                        if (saved != null) restore(WalkedCodec.decode(saved))
                    }
                    coverage = restored
                    // Seeded, not checked: restoring a walk in progress must not fire a
                    // popup for every street that was already past halfway last time.
                    milestones = Milestones(loaded.network).apply { seed(restored) }
                    coverageRevision++
                    mapState.moveTo(loaded.network.bounds.center)
                    LoadState.Ready(loaded.network, loaded.source)
                },
                onFailure = { LoadState.Failed(it.message ?: it::class.simpleName ?: "unknown error") },
            )
        }
    }

    fun showProgress() {
        screen = Screen.PROGRESS
    }

    fun showMap() {
        screen = Screen.MAP
    }

    /** Keeps the basemap in step with the theme. Swapping it empties the tile cache. */
    fun setDarkBasemap(dark: Boolean) {
        tiles.source = if (dark) TileSource.CartoDarkMatter else TileSource.OpenStreetMap
    }

    fun toggleTracking() {
        if (isTracking) stopTracking() else startTracking()
    }

    /** Swaps the source under a running walk, rather than making the user stop and start. */
    fun setUseKeyboard(on: Boolean) {
        if (on == keyboardControl) return
        keyboardControl = on
        if (isTracking) {
            stopTracking()
            startTracking()
        }
    }

    private fun startTracking() {
        val ready = loadState as? LoadState.Ready ?: return
        val activeCoverage = coverage ?: return

        // Real GPS where the platform has it and it has not been waved off. Otherwise
        // you drive the position yourself from the keyboard, which is both the dev loop
        // and how this gets demoed indoors — see MapScreen for the key handling.
        val real = if (useKeyboard) null else createRealLocationSource()
        val source: LocationSource = real ?: KeyboardWalker(
            // Carry on from where the marker already is, so stop/start does not teleport.
            start = position ?: ready.network.bounds.center,
            projection = ready.network.projection,
        ).also { keyboardWalker = it }
        locationLabel = source.label
        isTracking = true

        trackingJob = viewModelScope.launch {
            source.fixes().collect { fix ->
                position = fix.position
                positionAccuracyM = fix.accuracyM
                val walked = activeCoverage.record(fix)
                if (walked.isNotEmpty()) {
                    coverageRevision++
                    persist(activeCoverage)
                    milestones?.check(activeCoverage, walked)?.let(::announce)
                }
                if (followPosition) mapState.moveTo(fix.position)
            }
        }
    }

    /**
     * Queues achievements and shows them one after another.
     *
     * A queue rather than a replacement, because the interesting case is exactly the one
     * that would be lost: finishing a street usually means finishing it at a junction,
     * where the same fix can tip a second street over a line too.
     */
    private fun announce(earned: List<Achievement>) {
        if (earned.isEmpty()) return
        pendingAchievements += earned
        if (announcer?.isActive == true) return

        announcer = viewModelScope.launch {
            while (pendingAchievements.isNotEmpty()) {
                val next = pendingAchievements.removeFirst()
                achievement = next
                achievementVisible = true
                delay(if (next.milestone.isCleared) CLEARED_SHOWN_FOR else PROGRESS_SHOWN_FOR)
                achievementVisible = false
                // Long enough for the exit to finish, and a beat of nothing after it so
                // two in a row read as two rather than as one flickering.
                delay(BETWEEN_ACHIEVEMENTS)
            }
        }
    }

    /**
     * Writes walked state at most once every few seconds. Saving on every matched fix
     * would mean a disk or localStorage write every second of a walk, for a value that
     * barely changes.
     */
    private fun persist(coverage: Coverage) {
        if (walkedKey.isEmpty()) return
        val since = lastPersist
        if (since != null && since.elapsedNow() < PERSIST_INTERVAL) return
        lastPersist = TimeSource.Monotonic.markNow()
        viewModelScope.launch {
            storage.write(walkedKey, WalkedCodec.encode(coverage.walkedIds()))
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        announcer?.cancel()
        announcer = null
        pendingAchievements.clear()
        achievementVisible = false
        achievement = null
        isTracking = false
        keyboardWalker?.releaseAll()
        keyboardWalker = null

        // Flush on stop, so the throttle above can never lose the tail of a walk.
        val active = coverage
        if (active != null) {
            lastPersist = null
            persist(active)
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

        /** Finishing a street is the one worth stopping to read. */
        private val CLEARED_SHOWN_FOR = 4.seconds
        private val PROGRESS_SHOWN_FOR = 2.seconds
        private val BETWEEN_ACHIEVEMENTS = 400.milliseconds
    }
}
