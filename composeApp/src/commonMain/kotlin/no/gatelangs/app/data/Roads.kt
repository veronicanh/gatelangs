package no.gatelangs.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.model.Neighbourhood
import no.gatelangs.app.model.RawWay
import no.gatelangs.app.model.RoadNetwork
import no.gatelangs.app.resources.Res

/**
 * Highway classes that count as a street to be walked.
 *
 * Footways, paths and steps are deliberately absent, and their absence is load-bearing
 * rather than a matter of taste. OSM maps the pavement down each side of a street as its
 * own way, so admitting them made one street arrive three or four times over: of the
 * 179 km this box used to yield, 91 km was pavement running parallel to a street already
 * in the set, and only 29% of the length carried a name at all.
 *
 * Two things follow from leaving them out. Coverage stops meaning "walk this street, and
 * then walk it again down each side". And because matching now snaps to the nearest road
 * (see [no.gatelangs.app.model.Coverage.record]), walking the pavement credits the street
 * it belongs to — the street is the nearest thing to it once the pavement is not itself
 * in the network.
 *
 * Park paths and stairs go with them, which is a real loss. They are a separate thing to
 * count, not part of "every street in Oslo".
 */
private val STREET_HIGHWAYS = setOf(
    "residential",
    "living_street",
    "pedestrian",
    "unclassified",
    "tertiary",
    "secondary",
    "primary",
)

/** [STREET_HIGHWAYS] as an Overpass regex, so the filtering also happens server-side. */
private val STREET_HIGHWAYS_REGEX = STREET_HIGHWAYS.joinToString("|", prefix = "^(", postfix = ")$")

/**
 * OSM place types that read as a part of town you would name in conversation.
 *
 * `suburb` and `quarter` are the Oslo strøk — Tøyen, Sofienberg, Rodeløkka. `city` and
 * `town` are excluded because one of them would swallow everything.
 */
private const val PLACE_TYPES_REGEX = "^(suburb|quarter|neighbourhood)$"

private val json = Json { ignoreUnknownKeys = true }

// --- Overpass ---------------------------------------------------------------------

@Serializable
private data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

@Serializable
private data class OverpassElement(
    val type: String = "",
    val id: Long = 0,
    val tags: Map<String, String> = emptyMap(),
    val geometry: List<OverpassPoint> = emptyList(),
    /** Set on nodes only; ways carry their coordinates in [geometry] instead. */
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
private data class OverpassPoint(val lat: Double, val lon: Double)

// --- Bundled fallback -------------------------------------------------------------

/**
 * The compact on-disk form: `[lat, lon]` pairs rather than objects, which roughly
 * quarters the file against raw Overpass output.
 */
@Serializable
private data class BundledRoads(
    val ways: List<BundledWay> = emptyList(),
    val places: List<BundledPlace> = emptyList(),
)

@Serializable
private data class BundledPlace(val name: String, val lat: Double, val lon: Double)

@Serializable
private data class BundledWay(
    val id: Long,
    val name: String? = null,
    val highway: String? = null,
    val points: List<List<Double>> = emptyList(),
)

// --- Repository -------------------------------------------------------------------

/** Where a network came from — surfaced in the UI so a fallback is never silent. */
enum class RoadSource { OVERPASS, BUNDLED }

data class LoadedRoads(val network: RoadNetwork, val source: RoadSource)

class RoadRepository(private val http: HttpClient) {

    /**
     * The bundled snapshot, falling back to a live Overpass fetch.
     *
     * This way round on purpose. Streets are not a live feed — Oslo's road layout inside
     * Ring 3 changes a few times a year — so paying Overpass for it on every start buys
     * nothing and costs the one thing a demo cannot spare. Overpass is a free, shared and
     * frequently overloaded service: a cold fetch of this area is tens of seconds when it
     * works and a 504 when it does not, against a parse of a file already on disk.
     *
     * The live path stays for refreshing the snapshot and for areas outside it.
     */
    suspend fun load(area: BoundingBox): LoadedRoads =
        runCatching { LoadedRoads(loadBundled(), RoadSource.BUNDLED) }
            .getOrElse { LoadedRoads(fetchFromOverpass(area), RoadSource.OVERPASS) }

    suspend fun fetchFromOverpass(area: BoundingBox): RoadNetwork {
        val bbox = "(${area.south},${area.west},${area.north},${area.east})"
        // Streets and the places to group them by in one request: two round trips to a
        // service this slow is the difference between a pause and a wait.
        val query = buildString {
            append("[out:json][timeout:90];(")
            append("way[\"highway\"~\"$STREET_HIGHWAYS_REGEX\"]$bbox;")
            append("node[\"place\"~\"$PLACE_TYPES_REGEX\"]$bbox;")
            append(");")
            // `out geom` inlines each way's node coordinates, so there is no second
            // request to resolve node references and no join to do here.
            append("out geom;")
        }

        val body = http.submitForm(
            url = OVERPASS_ENDPOINT,
            formParameters = Parameters.build { append("data", query) },
        ).bodyAsText()

        val elements = json.decodeFromString(OverpassResponse.serializer(), body).elements

        val neighbourhoods = elements.mapNotNull { element ->
            val name = element.tags["name"]
            val lat = element.lat
            val lon = element.lon
            if (element.type != "node" || name == null || lat == null || lon == null) {
                null
            } else {
                Neighbourhood(name, LatLon(lat, lon))
            }
        }

        val ways = elements
            .filter { it.type == "way" && it.geometry.size >= 2 }
            .map { element ->
                RawWay(
                    id = element.id,
                    name = element.tags["name"],
                    points = element.geometry.map { LatLon(it.lat, it.lon) },
                    highway = element.tags["highway"],
                )
            }
            .filter { it.highway in STREET_HIGHWAYS }

        require(ways.isNotEmpty()) { "Overpass returned no walkable ways for $area" }
        return RoadNetwork.from(ways, neighbourhoods)
    }

    suspend fun loadBundled(): RoadNetwork {
        val text = Res.readBytes(BUNDLED_PATH).decodeToString()
        val bundled = json.decodeFromString(BundledRoads.serializer(), text)
        val ways = bundled.ways
            .filter { it.points.size >= 2 && it.highway in STREET_HIGHWAYS }
            .map { way ->
                RawWay(
                    id = way.id,
                    name = way.name,
                    points = way.points.map { LatLon(it[0], it[1]) },
                    highway = way.highway,
                )
            }
        // A snapshot predating the highway field filters down to nothing, which fails
        // here rather than quietly loading the pavements this filter exists to remove.
        require(ways.isNotEmpty()) { "bundled road data at $BUNDLED_PATH is empty" }
        return RoadNetwork.from(
            ways = ways,
            neighbourhoods = bundled.places.map { Neighbourhood(it.name, LatLon(it.lat, it.lon)) },
        )
    }

    companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val BUNDLED_PATH = "files/roads-oslo.json"

        /**
         * Everything inside Ring 3, which is the city as most people mean it.
         *
         * These are the bounds of Ring 3 (Riksvei 150) itself, measured from its own OSM
         * geometry — lat 59.9083..59.9536, lon 10.6271..10.8083 — pushed south to the
         * fjord so Bjørvika and the waterfront are not clipped off. A box rather than the
         * ring's actual outline: it takes in a little beyond the ring on each side, which
         * is the forgiving direction to be wrong in.
         */
        val DEFAULT_AREA = BoundingBox(
            south = 59.8950,
            west = 10.6271,
            north = 59.9536,
            east = 10.8083,
        )
    }
}
