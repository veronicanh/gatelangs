package no.gatelangs.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.model.RawWay
import no.gatelangs.app.model.RoadNetwork
import no.gatelangs.app.resources.Res

/** Highway classes that count as walkable streets. */
private const val WALKABLE_HIGHWAYS =
    "^(residential|living_street|pedestrian|footway|unclassified|tertiary|secondary|primary|path|steps)$"

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
private data class BundledRoads(val ways: List<BundledWay> = emptyList())

@Serializable
private data class BundledWay(
    val id: Long,
    val name: String? = null,
    val points: List<List<Double>> = emptyList(),
)

// --- Repository -------------------------------------------------------------------

/** Where a network came from — surfaced in the UI so a fallback is never silent. */
enum class RoadSource { OVERPASS, BUNDLED }

data class LoadedRoads(val network: RoadNetwork, val source: RoadSource)

class RoadRepository(private val http: HttpClient) {

    /**
     * Live road data for [area], falling back to the bundled snapshot.
     *
     * Overpass is a free, shared, frequently overloaded service — 504s and truncated
     * responses are routine, not exceptional. The bundled copy means a bad minute on
     * their side cannot take the app down with it.
     */
    suspend fun load(area: BoundingBox): LoadedRoads =
        runCatching { LoadedRoads(fetchFromOverpass(area), RoadSource.OVERPASS) }
            .getOrElse { LoadedRoads(loadBundled(), RoadSource.BUNDLED) }

    suspend fun fetchFromOverpass(area: BoundingBox): RoadNetwork {
        val query = buildString {
            append("[out:json][timeout:90];")
            append("way[\"highway\"~\"$WALKABLE_HIGHWAYS\"]")
            append("(${area.south},${area.west},${area.north},${area.east});")
            // `out geom` inlines each way's node coordinates, so there is no second
            // request to resolve node references and no join to do here.
            append("out geom;")
        }

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
                )
            }

        require(ways.isNotEmpty()) { "Overpass returned no walkable ways for $area" }
        return RoadNetwork.from(ways)
    }

    suspend fun loadBundled(): RoadNetwork {
        val text = Res.readBytes(BUNDLED_PATH).decodeToString()
        val ways = json.decodeFromString(BundledRoads.serializer(), text).ways
            .filter { it.points.size >= 2 }
            .map { way ->
                RawWay(
                    id = way.id,
                    name = way.name,
                    points = way.points.map { LatLon(it[0], it[1]) },
                )
            }
        require(ways.isNotEmpty()) { "bundled road data at $BUNDLED_PATH is empty" }
        return RoadNetwork.from(ways)
    }

    companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val BUNDLED_PATH = "files/roads-oslo.json"

        /** Central Oslo: ~180 km of walkable road, which is a day's worth of demo. */
        val DEFAULT_AREA = BoundingBox(
            south = 59.9150,
            west = 10.7450,
            north = 59.9300,
            east = 10.7750,
        )
    }
}
