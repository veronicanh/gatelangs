package no.gatelangs.app.model

import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.GridIndex
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Ring
import no.gatelangs.app.geo.Segment
import no.gatelangs.app.geo.length
import no.gatelangs.app.geo.segmentize

/**
 * The road network of one area, flattened and indexed.
 *
 * [segments] is flat and its list index *is* the segment id — walked state is a
 * `BooleanArray` of the same length, which keeps the hot path free of hashing.
 */
class RoadNetwork(
    val segments: List<Segment>,
    val streetNames: Map<Long, String?>,
    val index: GridIndex,
    val bounds: BoundingBox,
    /** Districts to group progress by. Empty if the snapshot carried none. */
    val districts: List<District> = emptyList(),
) {
    val projection: MetricProjection get() = index.projection

    /** Length of each segment in metres, by segment id. Precomputed for coverage sums. */
    val segmentLengths: DoubleArray = DoubleArray(segments.size) { i ->
        val s = segments[i]
        length(projection.project(s.a), projection.project(s.b))
    }

    val totalLengthM: Double = segmentLengths.sum()

    /** Segment ids grouped by the OSM way they came from. */
    val segmentsByWay: Map<Long, IntArray> =
        segments.indices.groupBy { segments[it].wayId }
            .mapValues { (_, ids) -> ids.toIntArray() }

    /** The street name a segment belongs to, or null where OSM has none. */
    fun streetNameOf(segmentId: Int): String? = streetNames[segments[segmentId].wayId]

    /**
     * Segment ids grouped by street *name*, which is not the same as by way.
     *
     * OSM splits a street wherever its tags change, so a street arrives as many ways —
     * Trondheimsveien is 27 of them. Grouping by way would report a street as twenty-odd
     * unrelated fragments, each with its own percentage, which is not what anyone means
     * by "how much of Parkveien have I walked".
     */
    val segmentsByStreet: Map<String, IntArray> =
        segments.indices.groupBy { streetNameOf(it) }
            .mapNotNull { (name, ids) -> name?.let { it to ids.toIntArray() } }
            .toMap()

    /** Each district's outlines, projected once so the assignment below stays cheap. */
    private val districtRings: List<List<Ring>> =
        districts.map { district -> district.rings.map { Ring(it.map(projection::project)) } }

    /**
     * Index into [districts] for each segment, or -1 for one that falls in none.
     *
     * By the segment's midpoint, and by real boundaries rather than by nearest centre
     * point: a street belongs to the bydel it is actually in, including right up against
     * the border, which is exactly where nearest-centre got it wrong.
     *
     * A segment outside every district keeps -1 rather than being forced into the closest
     * one. Oslo's bydeler tile the whole municipality, so this should be nothing at all —
     * and if a snapshot ever reaches past them, an honest gap beats a road filed under a
     * district it is nowhere near.
     */
    val districtOfSegment: IntArray = run {
        if (districtRings.isEmpty()) return@run IntArray(segments.size) { -1 }
        IntArray(segments.size) { id ->
            val mid = projection.project(midpointOf(id))
            districtRings.indexOfFirst { rings -> rings.any { mid in it } }
        }
    }

    /** Segment ids grouped by the district they fall in. */
    val segmentsByDistrict: Map<String, IntArray> =
        segments.indices.groupBy { districtOfSegment[it] }
            .mapNotNull { (index, ids) ->
                if (index < 0) null else (districts[index].name to ids.toIntArray())
            }
            .toMap()

    /** Total length of [ids] in metres. */
    fun lengthOf(ids: IntArray): Double {
        var total = 0.0
        for (id in ids) total += segmentLengths[id]
        return total
    }

    private fun midpointOf(id: Int): LatLon {
        val segment = segments[id]
        return LatLon(
            lat = (segment.a.lat + segment.b.lat) / 2.0,
            lon = (segment.a.lon + segment.b.lon) / 2.0,
        )
    }

    companion object {
        /**
         * Builds a network from raw way geometry.
         *
         * The projection origin is the centre of the data's own bounding box, which
         * keeps the equirectangular approximation tightest where the segments actually
         * are.
         */
        fun from(ways: List<RawWay>, districts: List<District> = emptyList()): RoadNetwork {
            val allPoints = ways.flatMap { it.points }
            require(allPoints.isNotEmpty()) { "cannot build a road network from no geometry" }

            val bounds = BoundingBox.around(allPoints)
            val projection = MetricProjection(bounds.center)

            val segments = ways.flatMap { segmentize(it.id, it.points, projection) }
            require(segments.isNotEmpty()) { "ways produced no segments" }

            return RoadNetwork(
                segments = segments,
                streetNames = ways.associate { it.id to it.name },
                index = GridIndex.build(segments, bounds.center),
                bounds = bounds,
                districts = districts,
            )
        }
    }
}

/**
 * One of Oslo's bydeler — Gamle Oslo, Grünerløkka, St. Hanshaugen, Frogner.
 *
 * An outline rather than a centre point, and an official one: the boundaries come from
 * Oslo kommune's own `Bydel_og_delbydelsgrenser`, simplified to a 12 m tolerance, which
 * is far finer than the question "which side of the border is this street on" needs.
 *
 * [rings] is a list because a bydel can arrive as more than one piece. Each ring is
 * closed and outer; the dataset has no holes worth carrying.
 */
data class District(val name: String, val rings: List<List<LatLon>>)

/** One OSM way, as it arrives from Overpass, before flattening. */
data class RawWay(
    val id: Long,
    val name: String?,
    val points: List<LatLon>,
    /**
     * The OSM `highway` class, kept so the same street/path filter can be applied to
     * live and bundled data alike. Null only in tests, which build geometry directly.
     */
    val highway: String? = null,
)
