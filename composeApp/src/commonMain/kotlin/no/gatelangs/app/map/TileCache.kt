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

/**
 * Fetches and holds basemap tiles.
 *
 * Backed by a Compose state map so an arriving tile redraws the map on its own, with no
 * explicit invalidation. Three things keep it from misbehaving:
 *
 *  - **In-flight tracking**, so panning back and forth over the same tiles does not
 *    queue the same request repeatedly.
 *  - **A concurrency limit**, so a zoom-out that reveals a hundred new tiles does not
 *    open a hundred sockets at once — and does not look like abuse to the tile server.
 *  - **Bounded size** with oldest-first eviction, so a long session does not grow
 *    without limit.
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
    private val tiles = mutableStateMapOf<TileKey, ImageBitmap>()
    private val inFlight = HashSet<TileKey>()
    private val insertionOrder = ArrayDeque<TileKey>()
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    /**
     * Bumped whenever [source] changes, so tiles already in flight for the old basemap
     * are dropped on arrival instead of being painted over the new one.
     */
    private var generation = 0

    /** Snapshot-backed so a screen showing the basemap's attribution follows a swap. */
    private var current by mutableStateOf(source)

    /** Where tiles come from. Setting it to a different source empties the cache. */
    var source: TileSource
        get() = current
        set(value) {
            if (value == current) return
            current = value
            generation++
            tiles.clear()
            insertionOrder.clear()
            inFlight.clear()
        }

    /** The tile if it is already loaded, else null. Never blocks, never starts a fetch. */
    operator fun get(key: TileKey): ImageBitmap? = tiles[key]

    /** Starts fetching any of [keys] not already loaded or in flight. */
    fun prefetch(keys: Collection<TileKey>) {
        for (key in keys) {
            if (key.zoom > source.maxZoom) continue
            if (tiles.containsKey(key) || !inFlight.add(key)) continue
            val requested = generation
            val from = source
            scope.launch {
                try {
                    // The store first, and outside the semaphore: that gate exists to be
                    // polite to the tile server, and reading a local file is neither slow
                    // nor anyone else's business.
                    val image = fromStore(from, key) ?: fromNetwork(from, key)
                    if (image != null && requested == generation) put(key, image)
                } catch (_: Throwable) {
                    // A failed tile is a hole in the backdrop, not a broken app: the
                    // roads and the coverage maths do not depend on it. Dropping the
                    // key from inFlight lets a later pan retry it.
                } finally {
                    // Only if the basemap has not changed underneath us — the swap
                    // already cleared inFlight, and the key may have been re-added for
                    // the new source since.
                    if (requested == generation) inFlight.remove(key)
                }
            }
        }
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
    private suspend fun fromNetwork(from: TileSource, key: TileKey): ImageBitmap? =
        gate.withPermit {
            val bytes = client.get(from.urlFor(key)).readRawBytes()
            // Storing only what decodes keeps error pages and rate-limit responses from
            // being cached forever as if they were tiles.
            decodeImage(bytes)?.also { store.write(from.id, key, bytes) }
        }

    private fun put(key: TileKey, image: ImageBitmap) {
        tiles[key] = image
        insertionOrder.addLast(key)
        while (insertionOrder.size > maxEntries) {
            val evicted = insertionOrder.removeFirst()
            tiles.remove(evicted)
        }
    }

    fun loadedCount(): Int = tiles.size

    companion object {
        /** A 1080p viewport shows ~40 tiles; this holds several screens of panning. */
        const val DEFAULT_MAX_ENTRIES = 256

        /** Polite, and enough to fill a screen quickly. */
        const val MAX_CONCURRENT_REQUESTS = 6
    }
}
