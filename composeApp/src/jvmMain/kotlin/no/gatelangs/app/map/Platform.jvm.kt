package no.gatelangs.app.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import org.jetbrains.skia.Image

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun createHttpClient(): HttpClient = HttpClient {
    install(UserAgent) { agent = USER_AGENT }
}

/** The OSM tile usage policy asks for an identifying User-Agent. */
private const val USER_AGENT = "Gatelangs/0.1 (Compose Multiplatform hackathon build)"
