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

    override val label: String = "GPS i nettleseren"

    override fun fixes(): Flow<Fix> = flow {
        // A slot of its own rather than one global shared by the page. Two watches do
        // overlap in practice — swapping the source under a running walk stops one and
        // starts the next — and sharing a slot let the old one's teardown blank the new
        // one's readings, or its error surface as the new one's first result.
        val slot = nextSlot++
        val watchId = startGeolocationWatch(slot)
        try {
            var lastTimestamp = Long.MIN_VALUE
            while (true) {
                val error = readGeolocationError(slot)
                if (error.isNotEmpty()) throw geolocationException(error)

                val payload = readGeolocationFix(slot)
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
            clearGeolocationWatch(watchId, slot)
        }
    }

    private companion object {
        const val DEFAULT_ACCURACY_M = 20.0
        const val POLL_INTERVAL_MS = 400L

        /** Hands out slot names. Only ever touched from the single browser thread. */
        var nextSlot = 0
    }
}

/**
 * A geolocation failure, with the browser's own code kept.
 *
 * The code is the whole value: it is the only part of a `PositionError` that says what
 * to do about it, and the browser's `message` is frequently empty.
 */
class GeolocationException(val code: Int, message: String) : Exception(message)

/**
 * Turns a browser `PositionError` into something the person holding the device can act on.
 *
 * The browser's own `message` is not that. A Mac with Location Services switched off in
 * System Settings reports bare code 2, which used to reach the screen as "geolocation
 * error 2" — true, and no help at all, because it sends you to the browser's permission
 * settings when the switch you need is in the operating system.
 */
private fun geolocationException(payload: String): GeolocationException {
    val code = runCatching { json.decodeFromString(RawError.serializer(), payload) }
        .getOrNull()?.code ?: 0
    return GeolocationException(code, messageFor(code))
}

private fun messageFor(code: Int): String = when (code) {
    // An insecure origin is refused as a denial rather than as its own code, so taking
    // code 1 at face value would send you to the permission settings for a problem that
    // is in the URL bar. Worth splitting: it is the expected state on a phone pointed at
    // a laptop's LAN IP, which is exactly how this gets tested.
    PERMISSION_DENIED -> if (isSecureContext()) {
        "Posisjon er avslått. Tillat posisjon for denne siden i nettleseren."
    } else {
        "GPS krever HTTPS. Siden må serveres over https:// (eller localhost)."
    }
    POSITION_UNAVAILABLE ->
        "Fant ikke posisjonen din. Sjekk at stedstjenester er slått på for nettleseren " +
            "i systeminnstillingene, eller slå på Tastatur."
    TIMEOUT -> "GPS-en svarte ikke i tide. Prøv igjen."
    else -> "GPS-feil (kode $code)."
}

private const val PERMISSION_DENIED = 1
private const val POSITION_UNAVAILABLE = 2
private const val TIMEOUT = 3

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class RawFix(val lat: Double, val lon: Double, val acc: Double, val t: Double)

/** Defaulted throughout: a malformed payload should still produce *some* message. */
@Serializable
private data class RawError(val code: Int = 0, val message: String = "")

private fun hasGeolocation(): Boolean =
    js("typeof navigator !== 'undefined' && !!navigator.geolocation")

private fun isSecureContext(): Boolean =
    js("globalThis.isSecureContext === true")

/**
 * `enableHighAccuracy` asks for the GPS chip rather than a coarse network fix — the
 * difference between telling two streets apart and not.
 */
private fun startGeolocationWatch(slot: Int): Int =
    js(
        """{
            var slots = globalThis.__gatelangs || (globalThis.__gatelangs = {});
            var s = slots[slot] = { fix: '', error: '' };
            return navigator.geolocation.watchPosition(
                function (p) {
                    s.fix = JSON.stringify({
                        lat: p.coords.latitude,
                        lon: p.coords.longitude,
                        acc: p.coords.accuracy,
                        t: p.timestamp
                    });
                },
                function (e) {
                    s.error = JSON.stringify({ code: e.code, message: e.message || '' });
                },
                { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 }
            );
        }"""
    )

private fun readGeolocationFix(slot: Int): String =
    js("(globalThis.__gatelangs && globalThis.__gatelangs[slot]) ? globalThis.__gatelangs[slot].fix : ''")

private fun readGeolocationError(slot: Int): String =
    js("(globalThis.__gatelangs && globalThis.__gatelangs[slot]) ? globalThis.__gatelangs[slot].error : ''")

private fun clearGeolocationWatch(watchId: Int, slot: Int): Unit =
    js(
        """{
            navigator.geolocation.clearWatch(watchId);
            if (globalThis.__gatelangs) delete globalThis.__gatelangs[slot];
        }"""
    )
