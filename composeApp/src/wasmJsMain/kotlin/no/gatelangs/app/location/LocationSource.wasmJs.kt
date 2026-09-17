package no.gatelangs.app.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.gatelangs.app.model.Fix

/**
 * Real GPS, via the browser's geolocation API.
 *
 * This is the app's actual delivery vehicle: opened on a phone, the browser hands over
 * genuine GPS. It requires a secure context — `localhost` is exempt, but a phone
 * pointed at a laptop's LAN IP over plain HTTP is refused, so serving the Wasm build
 * over HTTPS is a deployment requirement rather than a nicety.
 */
actual fun createRealLocationSource(): LocationSource? =
    if (hasGeolocation()) BrowserLocationSource() else null

/**
 * The JS side parks the newest reading on a global and Kotlin polls it, rather than
 * Kotlin handing a callback to `watchPosition`.
 *
 * Kotlin/Wasm requires every `js(...)` call to be a lone expression in a top-level
 * function, which rules out closing over Kotlin lambdas inside the JS snippet. Passing
 * a JSON string across the boundary keeps the interop to types both sides agree on.
 * Polling costs nothing here: `watchPosition` itself only fires about once a second.
 */
private class BrowserLocationSource : LocationSource {

    override val label: String = "Browser GPS"

    override fun fixes(): Flow<Fix> = flow {
        val watchId = startGeolocationWatch()
        try {
            var lastTimestamp = Long.MIN_VALUE
            while (true) {
                val error = readGeolocationError()
                if (error.isNotEmpty()) throw GeolocationException(error)

                val payload = readGeolocationFix()
                if (payload.isNotEmpty()) {
                    val raw = runCatching { json.decodeFromString(RawFix.serializer(), payload) }.getOrNull()
                    if (raw != null && raw.t.toLong() != lastTimestamp) {
                        lastTimestamp = raw.t.toLong()
                        emit(
                            Fix(
                                lat = raw.lat,
                                lon = raw.lon,
                                accuracyM = if (raw.acc.isNaN()) DEFAULT_ACCURACY_M else raw.acc,
                                timestampMs = lastTimestamp,
                            )
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            clearGeolocationWatch(watchId)
        }
    }

    private companion object {
        const val DEFAULT_ACCURACY_M = 20.0
        const val POLL_INTERVAL_MS = 400L
        val json = Json { ignoreUnknownKeys = true }
    }
}

class GeolocationException(message: String) : Exception(message)

@Serializable
private data class RawFix(val lat: Double, val lon: Double, val acc: Double, val t: Double)

private fun hasGeolocation(): Boolean =
    js("typeof navigator !== 'undefined' && !!navigator.geolocation")

/**
 * `enableHighAccuracy` asks for the GPS chip rather than a coarse network fix — the
 * difference between telling two streets apart and not.
 */
private fun startGeolocationWatch(): Int =
    js(
        """{
            globalThis.__gatelangs = { fix: '', error: '' };
            return navigator.geolocation.watchPosition(
                function (p) {
                    globalThis.__gatelangs.fix = JSON.stringify({
                        lat: p.coords.latitude,
                        lon: p.coords.longitude,
                        acc: p.coords.accuracy,
                        t: p.timestamp
                    });
                },
                function (e) {
                    globalThis.__gatelangs.error = e.message || ('geolocation error ' + e.code);
                },
                { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 }
            );
        }"""
    )

private fun readGeolocationFix(): String =
    js("globalThis.__gatelangs ? globalThis.__gatelangs.fix : ''")

private fun readGeolocationError(): String =
    js("globalThis.__gatelangs ? globalThis.__gatelangs.error : ''")

private fun clearGeolocationWatch(watchId: Int): Unit =
    js("navigator.geolocation.clearWatch(watchId)")
