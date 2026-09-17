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

    companion object {
        /**
         * Builds a network from raw way geometry.
         *
         * The projection origin is the centre of the data's own bounding box, which
         * keeps the equirectangular approximation tightest where the segments actually
         * are.
         */
        fun from(ways: List<RawWay>): RoadNetwork {
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
            )
        }
    }
}

/** One OSM way, as it arrives from Overpass, before flattening. */
data class RawWay(
    val id: Long,
    val name: String?,
    val points: List<LatLon>,
)
