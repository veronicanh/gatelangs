package no.gatelangs.app.geo

import kotlin.math.ceil

/** Target length of one walkable segment. Coverage can only ever be this granular. */
const val TARGET_SEGMENT_LENGTH_M = 25.0

/**
 * Guards `ceil` against a span that is an exact multiple of the cap but for float drift:
 * a 500 m street at a 25 m cap must give 20 pieces, not 20 plus a sub-nanometre stub.
 */
private const val SPLIT_EPSILON = 1e-9

/**
 * Flattens an OSM way's polyline into segments of at most [maxLengthM].
 *
 * OSM nodes sit wherever the geometry needs them, so consecutive nodes can be 3 m apart
 * on a bend and 400 m apart on a straight. Splitting long spans keeps coverage honest:
 * without it, stepping onto one end of a straight would credit the entire block.
 *
 * Short spans are left alone rather than merged — merging would round corners, and the
 * cost of a few extra segments is nothing.
 */
fun segmentize(
    wayId: Long,
    points: List<LatLon>,
    projection: MetricProjection,
    maxLengthM: Double = TARGET_SEGMENT_LENGTH_M,
): List<Segment> {
    require(maxLengthM > 0.0) { "maxLengthM must be positive, was $maxLengthM" }
    if (points.size < 2) return emptyList()

    val out = ArrayList<Segment>(points.size)
    for (i in 0 until points.lastIndex) {
        val start = points[i]
        val end = points[i + 1]
        val a = projection.project(start)
        val b = projection.project(end)
        val span = length(a, b)

        if (span <= maxLengthM || span == 0.0) {
            if (span > 0.0) out.add(Segment(wayId, start, end))
            continue
        }

        val pieces = ceil(span / maxLengthM - SPLIT_EPSILON).toInt().coerceAtLeast(1)
        var previous = start
        for (piece in 1..pieces) {
            val t = piece.toDouble() / pieces
            val next = if (piece == pieces) {
                end // land exactly on the node, never on a rounded interpolation
            } else {
                projection.unproject(Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
            out.add(Segment(wayId, previous, next))
            previous = next
        }
    }
    return out
}
