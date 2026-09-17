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

    }
}
