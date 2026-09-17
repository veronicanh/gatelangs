package no.gatelangs.app.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun createTileStore(): TileStore = FileTileStore()

/**
 * Tiles on disk, under `~/.gatelangs/tiles/`, laid out as `<source>/<z>/<x>/<y>.png`.
 *
 * The same directory the walked-state [no.gatelangs.app.storage.Storage] uses, so there is
 * one place to delete to reset everything.
 *
 * Keyed by source id rather than by URL: the URL now carries the API key, and keying on it
 * would throw away every cached tile the first time the key changed, for imagery that had
 * not changed at all.
 *
 * Nothing evicts. A tile is ~16 KB and walking the whole of inner Oslo at the zooms this
 * app is used at comes to a few tens of megabytes — small enough that a cap would cost more
 * code than it saves disk. If that stops being true, oldest-first over the directory is the
 * obvious answer.
 */
private class FileTileStore : TileStore {

    private val root = File(System.getProperty("user.home"), ".gatelangs/tiles")

    override suspend fun read(sourceId: String, key: TileKey): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = fileFor(sourceId, key)
            // A zero-length file is a half-finished write from a previous run, not a tile.
            if (file.isFile && file.length() > 0) {
                runCatching { file.readBytes() }.getOrNull()
            } else {
                null
            }
        }

    override suspend fun write(sourceId: String, key: TileKey, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(sourceId, key)
                file.parentFile?.mkdirs()
                // Written beside and moved into place, so a crash mid-write cannot leave a
                // truncated PNG that would then be served from cache forever.
                val partial = File(file.parentFile, "${file.name}.part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) {
                    partial.copyTo(file, overwrite = true)
                    partial.delete()
                }
            }
        }
    }

    private fun fileFor(sourceId: String, key: TileKey) =
        File(root, "$sourceId/${key.zoom}/${key.x}/${key.y}.png")
}
