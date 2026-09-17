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
         * Free, no API key, and serves `access-control-allow-origin: *` so the Wasm
         * build can fetch it directly. Attribution is a condition of use, not a nicety.
         */
        val CartoDarkMatter = TileSource(
            urlTemplate = "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors © CARTO",
            maxZoom = 20,
        )
    }
}
