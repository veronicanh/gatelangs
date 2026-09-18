package no.gatelangs.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.model.District
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
)

/**
 * The bydel outlines, in their own file.
 *
 * Separate from the roads because they are separate data: the roads are OSM and can be
 * refreshed from Overpass at any time, while these come from Oslo kommune's
 * `Bydel_og_delbydelsgrenser` and cannot. Keeping them in one envelope meant that
 * regenerating the road snapshot silently risked dropping outlines that no live fetch
 * could ever put back. They also change on entirely different timescales — a few times a
 * year against once in a generation.
 */
@Serializable
private data class BundledDistricts(
    val districts: List<BundledDistrict> = emptyList(),
)

/** One bydel outline: rings of `[lat, lon]` pairs, in the same compact form as the ways. */
@Serializable
private data class BundledDistrict(
    val name: String,
    val rings: List<List<List<Double>>> = emptyList(),
)

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

    /**
     * Roads only. Bydel outlines are not fetched live — they come from Oslo kommune
     * rather than from OSM, and they change on the scale of decades. A network loaded
     * this way has no districts, and the progress screen falls back to a flat list of
     * streets, which is the honest thing for it to do.
     */
    suspend fun fetchFromOverpass(area: BoundingBox): RoadNetwork {
        val bbox = "(${area.south},${area.west},${area.north},${area.east})"
        // `out geom` inlines each way's node coordinates, so there is no second request
        // to resolve node references and no join to do here.
        val query = "[out:json][timeout:90];" +
            "way[\"highway\"~\"$STREET_HIGHWAYS_REGEX\"]$bbox;" +
            "out geom;"

        val body = http.submitForm(
            url = OVERPASS_ENDPOINT,
            formParameters = Parameters.build { append("data", query) },
        ).bodyAsText()

        val ways = json.decodeFromString(OverpassResponse.serializer(), body).elements
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
        return RoadNetwork.from(ways)
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
        return RoadNetwork.from(ways = ways, districts = loadDistricts())
    }

    /**
     * The bydel outlines, or none.
     *
     * Failure here is not failure of the load: without outlines the progress screen drops
     * to a flat list of streets, which is exactly what it already does on the Overpass
     * path. Roads are the thing the app cannot work without — a missing bydel file should
     * cost you the breakdown, not the map. Splitting the two files is what makes that
     * distinction expressible at all.
     */
    private suspend fun loadDistricts(): List<District> = runCatching {
        val text = Res.readBytes(DISTRICTS_PATH).decodeToString()
        json.decodeFromString(BundledDistricts.serializer(), text).districts.map { district ->
            District(
                name = district.name,
                rings = district.rings.map { ring -> ring.map { LatLon(it[0], it[1]) } },
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val BUNDLED_PATH = "files/roads-oslo.json"
        const val DISTRICTS_PATH = "files/districts-oslo.json"

        /**
         * The whole municipality, as the bydel outlines describe it.
         *
         * These are the bounds of every ring in `districts-oslo.json`, which is the right
         * box precisely because the bydeler are what progress is measured against: an area
         * smaller than this leaves bydeler with no roads in them and a breakdown with empty
         * rows. It was previously the much smaller Ring 3 box, and that mismatch is what
         * this widening fixes.
         *
         * A box, so it also takes in slices of Bærum, Nittedal and Lørenskog. The snapshot
         * builder clips those away against the rings; the live Overpass path does not, and
         * a handful of neighbouring streets on a fallback load is a fair price for not
         * shipping a polygon filter into the client.
         */
        val DEFAULT_AREA = BoundingBox(
            south = 59.8093,
            west = 10.4892,
            north = 60.1352,
            east = 10.9514,
        )
    }
}
