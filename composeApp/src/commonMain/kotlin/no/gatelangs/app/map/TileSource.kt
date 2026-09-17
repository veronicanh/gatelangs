package no.gatelangs.app.map

/** Identifies one raster tile in the slippy-map scheme. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/**
 * Where basemap imagery comes from.
 *
 * Deliberately just a URL template: swapping OSM for Mapbox, MapTiler or Stadia is a
 * one-line change here and touches nothing else, because the road data the app actually
 * reasons about comes from Overpass regardless of who draws the backdrop.
 */
data class TileSource(
    /**
     * Stable name for this basemap, used as the [TileStore] directory.
     *
     * Deliberately not derived from [urlTemplate]: the template carries the API key, so a
     * key change would look like a different basemap and throw away every cached tile for
     * imagery that had not changed.
     */
    val id: String,
    val urlTemplate: String,
    val attribution: String,
    val maxZoom: Int = 19,
) {
    fun urlFor(key: TileKey): String = urlTemplate
        .replace("{z}", key.zoom.toString())
        .replace("{x}", key.x.toString())
        .replace("{y}", key.y.toString())

    companion object {
        /**
         * Standard OSM raster tiles. Free, and CORS-open so the Wasm build can fetch them
         * directly. Their usage policy expects light, cached use and an identifying
         * User-Agent — fine for this, but it is not a CDN to hammer.
         */
        val OpenStreetMap = TileSource(
            id = "osm",
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
        )

        /**
         * CARTO's Dark Matter: a near-black basemap with the labels dimmed right down.
         *
         * The app is about the road overlay, not the backdrop, and on the standard OSM
         * raster the unwalked roads had to compete with a map already full of coloured
         * streets. Against this they are the only bright thing on screen, which is the
         * whole point — what is left to walk should be what you see.
         *
         * Serves `access-control-allow-origin: *`, so the Wasm build fetches it directly.
         * Attribution is a condition of use, not a nicety.
         *
         * Keyed from [CARTO_API_KEY] — see [cartoDarkMatter] for what happens without one.
         * One instance rather than a factory call at each use site: [TileSource] is a data
         * class and [TileCache] compares sources with `==` to decide whether the basemap
         * changed, so two equal-but-separate instances would wipe the tile cache on every
         * theme toggle.
         */
        val CartoDarkMatter = cartoDarkMatter(CARTO_API_KEY)
    }
}

/**
 * CARTO's Dark Matter at a given key.
 *
 * Split out from [TileSource.CartoDarkMatter] so the URL shape can be tested at both
 * settings: the key is a compile-time constant and a test cannot vary a `const`.
 *
 * A blank key yields the plain, keyless URL rather than a dangling `?key=`. That is the
 * fresh-clone path — someone who has just cloned the repo has no `local.properties`, and
 * the app should still build and draw a map. CARTO still serves those tiles, but since
 * August 2026 it stamps an "API KEY REQUIRED" watermark across each one, so the backdrop
 * looks wrong until a key is configured. Nothing breaks; it just looks shabby.
 */
internal fun cartoDarkMatter(apiKey: String): TileSource = TileSource(
    id = "carto-dark",
    urlTemplate = buildString {
        append("https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png")
        if (apiKey.isNotBlank()) append("?key=").append(apiKey)
    },
    attribution = "© OpenStreetMap contributors © CARTO",
    maxZoom = 20,
)
