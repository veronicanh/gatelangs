package no.gatelangs.app.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.jetbrains.skia.Image

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * No User-Agent plugin: the browser sets that header itself and rejects attempts to
 * override it.
 */
actual fun createHttpClient(): HttpClient = HttpClient {
    // Without this a hung request holds its tile-fetch permit forever, and enough of them
    // wedge the basemap for the rest of the session with no error anywhere. The Js engine
    // honours the request timeout; connect and socket timeouts are not its to give.
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
}

private const val REQUEST_TIMEOUT_MS = 15_000L
