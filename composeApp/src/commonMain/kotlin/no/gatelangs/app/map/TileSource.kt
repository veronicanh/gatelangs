package no.gatelangs.app.map

/** Identifies one raster tile in the slippy-map scheme. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/**
 * The tile one zoom level out that contains this one, or null at the root.
 *
 * What makes a blurred stand-in possible while the real tile is still in flight: the
 * parent covers the same ground at a quarter of the detail, and a quarter of it is
 * exactly this tile.
 */
fun TileKey.parent(): TileKey? = if (zoom <= 0) null else TileKey(zoom - 1, x / 2, y / 2)

/**
 * The distinct ancestors of these tiles, up to [levels] zoom levels out.
 *
 * Fetched alongside the tiles actually being drawn, and far cheaper than they look: each
 * level out covers four times the ground, so two levels of ancestors for a fifty-tile
 * viewport is under a dozen extra requests. They are what the map falls back to while the
 * real tiles are in flight — without them the first load has nothing to show but the page
 * background, and they arrive first precisely because there are so few of them.
 */
fun Collection<TileKey>.ancestors(levels: Int): List<TileKey> {
    if (levels <= 0 || isEmpty()) return emptyList()
    val found = LinkedHashSet<TileKey>()
    var frontier: Collection<TileKey> = this
    repeat(levels) {
        val next = LinkedHashSet<TileKey>()
        for (key in frontier) {
            val parent = key.parent() ?: continue
            if (found.add(parent)) next.add(parent)
        }
        if (next.isEmpty()) return found.toList()
        frontier = next
    }
    return found.toList()
}

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
    /**
     * How many tiles to ask this publisher for at once.
     *
     * A property of the publisher, not of the app: a CDN we hold an API key for will
     * serve a screenful over one multiplexed HTTP/2 connection without noticing, while a
     * free community service asks to be treated gently. Filling a viewport takes 40-90
     * tiles, so this number is very nearly the divisor on how long the map takes to
     * appear.
     */
    val concurrentRequests: Int = 6,
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
            // Left low on purpose. Their usage policy asks for restraint, and this is the
            // fallback basemap rather than the one the app ships pointed at.
            concurrentRequests = 6,
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
    // A CDN, over HTTP/2, that we hold a key for. Six at a time turned a single screenful
    // into a dozen sequential round trips for no reason: the limit was sized for HTTP/1.1,
    // where six connections per origin was the browser's own ceiling.
    concurrentRequests = 24,
)
