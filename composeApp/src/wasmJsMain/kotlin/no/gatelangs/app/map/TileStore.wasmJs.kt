package no.gatelangs.app.map

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Tiles in the browser's Cache Storage, under `gatelangs-tiles`.
 *
 * The HTTP cache already does most of this — CARTO serves `max-age=15552000`, and the
 * browser honours it on disk and across reloads. Two things it does not do, and they are
 * the reasons this exists:
 *
 *  - It is keyed on the full URL, and ours carries `?key=<CARTO_API_KEY>`. Rotating the
 *    key throws away every cached tile in the browser for imagery that has not changed.
 *    Keying on [TileSource.id] instead is exactly what the desktop store does, and why
 *    that id exists at all.
 *  - It is evicted first under storage pressure, and expires at 180 days.
 *
 * Cache Storage is secure-context only, so a plain-HTTP origin falls back to keeping
 * nothing rather than throwing — the same condition the browser puts on geolocation, and
 * the same reason the Wasm build wants serving over HTTPS.
 *
 * Nothing evicts, matching the desktop store. A tile is ~16 KB and the browser drops the
 * whole bucket if it needs the room, which is safe: everything in it is re-fetchable.
 */
actual fun createTileStore(): TileStore =
    if (hasCacheStorage()) CacheStorageTileStore() else NoTileStore

private class CacheStorageTileStore : TileStore {

    override suspend fun read(sourceId: String, key: TileKey): ByteArray? =
        runCatching { cacheMatch(cacheUrlFor(sourceId, key)).await() }
            .getOrNull()
            ?.toByteArray()

    override suspend fun write(sourceId: String, key: TileKey, bytes: ByteArray) {
        // A full quota is a cache miss and a slightly slower map, never a broken one.
        runCatching { cachePut(cacheUrlFor(sourceId, key), bytes.toUint8Array()).await() }
    }
}

/**
 * The key a tile is stored under.
 *
 * A URL because `Cache.put` demands an http(s) one, and a made-up host because this is a
 * name in our own cache rather than anything that is ever fetched. Deliberately without
 * the API key — see the class note.
 */
private fun cacheUrlFor(sourceId: String, key: TileKey): String =
    "https://tiles.gatelangs.local/$sourceId/${key.zoom}/${key.x}/${key.y}.png"

/**
 * Both directions copy element by element: Wasm linear memory and a JS `ArrayBuffer` are
 * separate heaps, so there is no view that spans them. At ~16 KB a tile this is far
 * cheaper than the PNG decode that follows it.
 */
private fun ArrayBuffer.toByteArray(): ByteArray {
    // Int8Array rather than Uint8Array because Kotlin's Byte is signed too, so the bits
    // carry across unchanged. The offset and length are not optional on this declaration.
    val view = Int8Array(this, 0, byteLength)
    return ByteArray(view.length) { view[it] }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (index in indices) out[index] = this[index]
    return out
}

private fun hasCacheStorage(): Boolean =
    js("typeof caches !== 'undefined'")

/**
 * Resolves to the stored bytes, or null when this tile has never been kept.
 *
 * Every `js(...)` has to be a lone expression in a top-level function on Kotlin/Wasm,
 * which is why the whole promise chain lives inside one snippet rather than being
 * assembled from Kotlin — see LocationSource.wasmJs.kt for the same constraint.
 */
private fun cacheMatch(url: String): Promise<ArrayBuffer?> =
    js(
        """{
            return caches.open('gatelangs-tiles')
                .then(function (c) { return c.match(url); })
                .then(function (r) { return r ? r.arrayBuffer() : null; });
        }"""
    )

private fun cachePut(url: String, body: Uint8Array): Promise<JsAny?> =
    js(
        """{
            return caches.open('gatelangs-tiles').then(function (c) {
                return c.put(url, new Response(body, { headers: { 'Content-Type': 'image/png' } }));
            });
        }"""
    )
