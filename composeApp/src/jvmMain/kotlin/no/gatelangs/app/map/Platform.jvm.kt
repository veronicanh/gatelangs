package no.gatelangs.app.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import org.jetbrains.skia.Image

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun createHttpClient(): HttpClient = HttpClient {
    install(UserAgent) { agent = USER_AGENT }
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
}

/** The OSM tile usage policy asks for an identifying User-Agent. */
private const val USER_AGENT = "Gatelangs/0.1 (Compose Multiplatform hackathon build)"

/**
 * Without this a hung request holds its tile-fetch permit forever, and enough of them
 * wedge the basemap for the rest of the session with no error anywhere.
 */
private const val REQUEST_TIMEOUT_MS = 15_000L
