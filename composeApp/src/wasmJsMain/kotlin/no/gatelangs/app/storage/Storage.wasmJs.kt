package no.gatelangs.app.storage

import kotlinx.browser.localStorage

actual class Storage actual constructor() {

    /**
     * Both calls are wrapped: localStorage throws outright in private browsing and when
     * site data is blocked, and losing walked state is much better than crashing.
     */
    actual suspend fun read(key: String): String? =
        runCatching { localStorage.getItem(key) }.getOrNull()

    actual suspend fun write(key: String, value: String) {
        runCatching { localStorage.setItem(key, value) }
    }
}
