package no.gatelangs.app.map

/**
 * Keeps fetched basemap tiles between runs.
 *
 * Tile imagery is the one thing this app fetches over and over for no reason: the roads
 * come from a bundled snapshot, but every restart used to re-download the same few hundred
 * PNGs of the same few streets. A tile is ~16 KB and CARTO itself marks them
 * `max-age=15552000` — half a year — so treating them as permanent is exactly what the
 * publisher intends.
 *
 * An interface with a platform factory rather than an `expect class`, so the cache logic in
 * [TileCache] can be tested against a fake without a filesystem — the same shape as
 * `createRealLocationSource`.
 *
 * Both methods swallow their own failures. A tile that cannot be read or written is a
 * cache miss and a slightly slower map, never a broken one.
 */
interface TileStore {

    /** The stored tile, or null if this source has never fetched it. */
    suspend fun read(sourceId: String, key: TileKey): ByteArray?

    /** Keeps [bytes] for later. Overwrites silently. */
    suspend fun write(sourceId: String, key: TileKey, bytes: ByteArray)
}

/** Remembers nothing. The fallback wherever a platform has somewhere better to put tiles. */
object NoTileStore : TileStore {
    override suspend fun read(sourceId: String, key: TileKey): ByteArray? = null
    override suspend fun write(sourceId: String, key: TileKey, bytes: ByteArray) = Unit
}

/** The platform's tile store. */
expect fun createTileStore(): TileStore
