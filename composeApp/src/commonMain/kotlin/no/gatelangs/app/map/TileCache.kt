package no.gatelangs.app.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
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
 */
@Stable
class TileCache(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val source: TileSource,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val tiles = mutableStateMapOf<TileKey, ImageBitmap>()
    private val inFlight = HashSet<TileKey>()
    private val insertionOrder = ArrayDeque<TileKey>()
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    /** The tile if it is already loaded, else null. Never blocks, never starts a fetch. */
    operator fun get(key: TileKey): ImageBitmap? = tiles[key]

    /** Starts fetching any of [keys] not already loaded or in flight. */
    fun prefetch(keys: Collection<TileKey>) {
        for (key in keys) {
            if (key.zoom > source.maxZoom) continue
            if (tiles.containsKey(key) || !inFlight.add(key)) continue
            scope.launch {
                try {
                    val image = gate.withPermit {
                        decodeImage(client.get(source.urlFor(key)).readRawBytes())
                    }
                    if (image != null) put(key, image)
                } catch (_: Throwable) {
                    // A failed tile is a hole in the backdrop, not a broken app: the
                    // roads and the coverage maths do not depend on it. Dropping the
                    // key from inFlight lets a later pan retry it.
                } finally {
                    inFlight.remove(key)
                }
            }
        }
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
