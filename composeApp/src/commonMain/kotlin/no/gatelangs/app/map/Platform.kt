package no.gatelangs.app.map

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient

/**
 * Decodes PNG/JPEG bytes into an [ImageBitmap], or null if the bytes are not an image.
 *
 * Both targets are Skiko-backed so the two implementations are identical, but a
 * jvm+wasmJs project has no shared Skiko source set, so the declaration has to be
 * expect/actual anyway.
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * The HTTP client for tiles and Overpass.
 *
 * Platform-specific only because User-Agent is a forbidden header in browsers: the JVM
 * build sets the identifying agent the OSM tile policy asks for, and the Wasm build
 * cannot and must not try.
 */
expect fun createHttpClient(): HttpClient
