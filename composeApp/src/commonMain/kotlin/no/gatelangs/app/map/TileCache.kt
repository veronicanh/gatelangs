package no.gatelangs.app.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Fetches and holds basemap tiles.
 *
 * Backed by a Compose state map so an arriving tile redraws the map on its own, with no
 * explicit invalidation. Four things keep it from misbehaving:
 *
 *  - **In-flight tracking**, so panning back and forth over the same tiles does not
 *    queue the same request repeatedly.
 *  - **A concurrency limit**, taken from the source rather than fixed here, because what
 *    counts as polite is a property of whoever publishes the tiles.
 *  - **Bounded size** with oldest-first eviction that skips whatever is on screen, so a
 *    long session does not grow without limit and a large viewport does not evict the
 *    tiles it is in the middle of drawing.
 *  - **A retry delay on failures**, so a tile the server will not serve is not requested
 *    again on every single recomposition for as long as it stays on screen.
 *
 * Behind the in-memory map sits [store], which survives restarts: a tile is fetched from
 * the network once ever, not once per run. The lookup order is memory, then store, then
 * the network — so walking the same neighbourhood on a second day costs no requests at all.
 */
@Stable
class TileCache(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    source: TileSource,
    private val store: TileStore = createTileStore(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /**
     * A tile of a particular basemap.
     *
     * Keyed by source as well as position so light and dark can be held at the same time.
     * Swapping the basemap used to empty the cache, which made toggling the theme
     * re-download and re-decode the whole viewport for imagery that was already in hand a
     * moment earlier.
     */
    private data class Slot(val sourceId: String, val key: TileKey)

    private val tiles = mutableStateMapOf<Slot, ImageBitmap>()
    private val inFlight = HashSet<Slot>()
    private val insertionOrder = ArrayDeque<Slot>()

    /** When each failing tile last failed. Read by [retryBlocked], cleared on success. */
    private val failures = HashMap<Slot, TimeMark>()

    /** What [prefetch] last asked for — i.e. what is on screen. Never evicted. */
    private var pinned: Set<Slot> = emptySet()

    /** Replaced on a source change, since the limit belongs to the source. */
    private var gate = Semaphore(source.concurrentRequests)

    /** Snapshot-backed so a screen showing the basemap's attribution follows a swap. */
    private var current by mutableStateOf(source)

    /** Where tiles come from. Tiles already held for the old source are kept. */
    var source: TileSource
        get() = current
        set(value) {
            if (value == current) return
            current = value
            // Requests still running against the old source hold permits on the old
            // semaphore and release them there, so they are unaffected by this.
            gate = Semaphore(value.concurrentRequests)
        }

    /** The tile if it is already loaded, else null. Never blocks, never starts a fetch. */
    operator fun get(key: TileKey): ImageBitmap? = tiles[Slot(current.id, key)]

    /**
     * Starts fetching any of [keys] not already loaded, in flight, or recently failed.
     *
     * Also records [keys] as the set worth protecting from eviction — the caller passes
     * what it is about to draw, which is exactly the set that must not be thrown away.
     */
    fun prefetch(keys: Collection<TileKey>) {
        val from = source
        val wanted = LinkedHashSet<Slot>(keys.size)
        for (key in keys) {
            if (key.zoom < 0 || key.zoom > from.maxZoom) continue
            wanted.add(Slot(from.id, key))
        }
        pinned = wanted

        for (slot in wanted) {
            if (tiles.containsKey(slot) || retryBlocked(slot) || !inFlight.add(slot)) continue
            scope.launch {
                try {
                    // The store first, and outside the semaphore: that gate exists to be
                    // polite to the tile server, and reading a local file is neither slow
                    // nor anyone else's business.
                    val image = fromStore(from, slot.key) ?: fromNetwork(from, slot.key)
                    if (image != null) {
                        failures.remove(slot)
                        put(slot, image)
                    } else {
                        failures[slot] = TimeSource.Monotonic.markNow()
                    }
                } catch (_: Throwable) {
                    // A failed tile is a hole in the backdrop, not a broken app: the
                    // roads and the coverage maths do not depend on it.
                    failures[slot] = TimeSource.Monotonic.markNow()
                } finally {
                    inFlight.remove(slot)
                }
            }
        }
    }

    /**
     * Whether this tile failed too recently to be worth asking for again.
     *
     * Without this, a 404 or a rate-limit response is re-requested on the very next
     * recomposition and every one after it, for as long as that tile is on screen — a
     * tight retry loop aimed at a server that has just said no.
     */
    private fun retryBlocked(slot: Slot): Boolean {
        val failedAt = failures[slot] ?: return false
        if (failedAt.elapsedNow() < RETRY_AFTER) return true
        failures.remove(slot)
        return false
    }

    /**
     * The stored tile, or null if there is not a usable one.
     *
     * Bytes that fail to decode are treated as a miss rather than as a failure, so a tile
     * truncated by a crash or a full disk is re-fetched and overwritten instead of leaving
     * a permanent hole in the map.
     */
    private suspend fun fromStore(from: TileSource, key: TileKey): ImageBitmap? {
        val bytes = store.read(from.id, key) ?: return null
        return decodeImage(bytes)
    }

    /** Fetches, and keeps what it got — but only once it is known to be an image. */
    private suspend fun fromNetwork(from: TileSource, key: TileKey): ImageBitmap? {
        // Only the request itself holds a permit. Decoding and writing to the store are
        // local work, and doing them under the gate would leave a network slot idle.
        val bytes = gate.withPermit { client.get(from.urlFor(key)).readRawBytes() }
        // Storing only what decodes keeps error pages and rate-limit responses from
        // being cached forever as if they were tiles.
        val image = decodeImage(bytes) ?: return null
        store.write(from.id, key, bytes)
        return image
    }

    private fun put(slot: Slot, image: ImageBitmap) {
        tiles[slot] = image
        insertionOrder.addLast(slot)

        // Oldest first, but never a tile that is on screen. At a large viewport the visible
        // set is a sizeable fraction of the cap, and dropping something being drawn means
        // fetching and decoding it again immediately. Bounded by one pass over the queue,
        // so a viewport larger than the cap overshoots slightly rather than spinning.
        var budget = insertionOrder.size
        while (tiles.size > maxEntries && budget > 0) {
            budget--
            val candidate = insertionOrder.removeFirst()
            if (!tiles.containsKey(candidate)) continue
            if (candidate in pinned) {
                insertionOrder.addLast(candidate)
                continue
            }
            tiles.remove(candidate)
        }
    }

    fun loadedCount(): Int = tiles.size

    companion object {
        /**
         * How many decoded tiles to hold.
         *
         * Memory-bound, not arbitrary: an [ImageBitmap] at 256x256x4 bytes is 256 KB, so
         * this is already about 64 MB. Raising it is not the free win it looks like.
         */
        const val DEFAULT_MAX_ENTRIES = 256

        /** Long enough that a struggling tile server is not hammered, short enough to recover. */
        val RETRY_AFTER = 10.seconds
    }
}
