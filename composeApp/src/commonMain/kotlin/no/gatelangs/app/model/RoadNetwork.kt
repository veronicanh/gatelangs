package no.gatelangs.app.model

import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.GridIndex
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
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
    /** Named places to group progress by. Empty if the snapshot carried none. */
    val neighbourhoods: List<Neighbourhood> = emptyList(),
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

    /**
     * Index into [neighbourhoods] for each segment, or -1 where there are none.
     *
     * OSM gives neighbourhoods as single points, not outlines, so a segment joins the
     * nearest one and the boundaries fall where the halfway line does. Rough at the
     * edges and exactly right in the middle, which is the useful half.
     */
    val neighbourhoodOfSegment: IntArray = run {
        if (neighbourhoods.isEmpty()) return@run IntArray(segments.size) { -1 }

        // Projected once rather than per segment: this is tens of thousands of segments
        // against tens of places, so the inner loop is the one that has to be cheap.
        val centres = neighbourhoods.map { projection.project(it.centre) }
        IntArray(segments.size) { id ->
            val mid = projection.project(midpointOf(id))
            var best = 0
            var bestDistance = Double.MAX_VALUE
            for (index in centres.indices) {
                val dx = mid.x - centres[index].x
                val dy = mid.y - centres[index].y
                val squared = dx * dx + dy * dy // comparing, so no need for the root
                if (squared < bestDistance) {
                    bestDistance = squared
                    best = index
                }
            }
            best
        }
    }

    /** Segment ids grouped by the neighbourhood they fall in. */
    val segmentsByNeighbourhood: Map<String, IntArray> =
        segments.indices.groupBy { neighbourhoodOfSegment[it] }
            .mapNotNull { (index, ids) ->
                if (index < 0) null else (neighbourhoods[index].name to ids.toIntArray())
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
        fun from(ways: List<RawWay>, neighbourhoods: List<Neighbourhood> = emptyList()): RoadNetwork {
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
                neighbourhoods = neighbourhoods,
            )
        }
    }
}

/**
 * A named part of the city — Tøyen, Sofienberg, Rodeløkka.
 *
 * A point, not an outline, because that is how OSM holds them (`place=suburb` and
 * friends) and because outlines would be a second, much larger download for a
 * distinction nobody looks at closely.
 */
data class Neighbourhood(val name: String, val centre: LatLon)

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
