package no.gatelangs.app.map

/**
 * The browser already does this, so the app does not.
 *
 * CARTO serves tiles with `cache-control: public, max-age=15552000` — 180 days — and the
 * browser's HTTP cache honours that on disk, across reloads, and while offline. A second
 * cache in front of it would re-implement eviction, quota handling and staleness for no
 * gain, and would cost a `js()` Promise bridge to the Cache API that the Kotlin/Wasm
 * interop rules make genuinely awkward (see LocationSource.wasmJs.kt for why every
 * `js(...)` snippet has to be a lone top-level expression).
 *
 * So "only fetch a tile once" is already true here — it is enforced a layer down, by the
 * browser, rather than by [TileStore].
 *
 * What this does *not* give, and the Cache API would: survival when the browser evicts the
 * HTTP cache under storage pressure, and tiles that outlive the 180-day window. Neither is
 * worth the interop today. If it becomes worth it, this is the one file to change.
 */
actual fun createTileStore(): TileStore = NoTileStore
