package no.gatelangs.app.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A 1x1 PNG. Real bytes, because [decodeImage] is a real decoder and the cache only keeps
 * what actually decodes — a fake byte array would be indistinguishable from a broken tile.
 */
private val ONE_PIXEL_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
    0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
    0x54, 0x78, 0x9C.toByte(), 0x63, 0x50, 0x50, 0x50, 0x00,
    0x00, 0x00, 0xC4.toByte(), 0x00, 0x61, 0x29, 0x95.toByte(), 0xF9.toByte(),
    0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
    0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
)

private val TILE = TileKey(zoom = 14, x = 8681, y = 4765)

/** An in-memory [TileStore], so the ordering can be tested without touching a disk. */
private class FakeTileStore(seed: Map<String, ByteArray> = emptyMap()) : TileStore {
    val saved = seed.toMutableMap()
    var reads = 0
    var writes = 0

    override suspend fun read(sourceId: String, key: TileKey): ByteArray? {
        reads++
        return saved["$sourceId/${key.zoom}/${key.x}/${key.y}"]
    }

    override suspend fun write(sourceId: String, key: TileKey, bytes: ByteArray) {
        writes++
        saved["$sourceId/${key.zoom}/${key.x}/${key.y}"] = bytes
    }
}

private fun pngEngine(requests: MutableList<String>) = MockEngine { request ->
    requests += request.url.toString()
    respond(
        content = ONE_PIXEL_PNG,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "image/png"),
    )
}

class TileStoreCachingTest {

    @Test
    fun `a stored tile is served without touching the network`() = runTest {
        val requests = mutableListOf<String>()
        val store = FakeTileStore(mapOf("carto-dark/14/8681/4765" to ONE_PIXEL_PNG))
        val cache = TileCache(
            client = HttpClient(pngEngine(requests)),
            scope = TestScope(testScheduler),
            source = TileSource.CartoDarkMatter,
            store = store,
        )

        cache.prefetch(listOf(TILE))
        testScheduler.advanceUntilIdle()

        assertTrue(requests.isEmpty(), "a cached tile must not be fetched again: $requests")
        assertEquals(1, cache.loadedCount())
        assertEquals(0, store.writes, "nothing new was fetched, so nothing should be written")
    }

    @Test
    fun `a missing tile is fetched and then kept`() = runTest {
        val requests = mutableListOf<String>()
        val store = FakeTileStore()
        val cache = TileCache(
            client = HttpClient(pngEngine(requests)),
            scope = TestScope(testScheduler),
            source = TileSource.CartoDarkMatter,
            store = store,
        )

        cache.prefetch(listOf(TILE))
        testScheduler.advanceUntilIdle()

        assertEquals(1, requests.size, "a tile absent from the store has to come from CARTO")
        assertEquals(1, store.writes, "and must be kept, or the next run refetches it")
        assertTrue("carto-dark/14/8681/4765" in store.saved.keys)
        assertEquals(1, cache.loadedCount())
    }

    @Test
    fun `tiles are kept per basemap, not per url`() = runTest {
        // The CARTO url carries the API key. Keying the store on the url would discard
        // every cached tile the first time the key changed, for identical imagery.
        val store = FakeTileStore()
        val keyless = cartoDarkMatter("")
        val keyed = cartoDarkMatter("abc123")

        assertEquals(keyless.id, keyed.id)

        store.write(keyed.id, TILE, ONE_PIXEL_PNG)
        assertTrue(store.read(keyless.id, TILE) != null, "the same basemap, so the same tiles")
    }

    @Test
    fun `a different basemap does not reuse the other's tiles`() = runTest {
        val store = FakeTileStore()
        store.write(TileSource.CartoDarkMatter.id, TILE, ONE_PIXEL_PNG)

        assertEquals(null, store.read(TileSource.OpenStreetMap.id, TILE))
    }
}
