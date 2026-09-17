package no.gatelangs.app.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import org.jetbrains.skia.Image

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * No User-Agent plugin: the browser sets that header itself and rejects attempts to
 * override it.
 */
actual fun createHttpClient(): HttpClient = HttpClient()
