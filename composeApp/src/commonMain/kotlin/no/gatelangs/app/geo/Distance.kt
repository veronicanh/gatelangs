package no.gatelangs.app.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance from [p] to the *line segment* a–b, not to its nearest endpoint.
 *
 * This is the single most load-bearing function in the app: whether a street counts as
 * walked is decided here. Walking down the middle of a long block puts you far from
 * both endpoints but nearly on the segment, so endpoint distance would be wrong in
 * exactly the common case.
 *
 * Works in the metres plane of a [MetricProjection]; project first.
 */
fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double = sqrt(squaredDistanceToSegment(p, a, b))

/** As [distanceToSegment], without the square root — for comparing against a threshold. */
fun squaredDistanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSquared = abx * abx + aby * aby

    // Degenerate segment (both ends the same node): fall back to point distance.
    if (lengthSquared == 0.0) {
        val dx = p.x - a.x
        val dy = p.y - a.y
        return dx * dx + dy * dy
    }

    // Parameter of the projection of p onto the infinite line, clamped to the segment.
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSquared).coerceIn(0.0, 1.0)
    val dx = p.x - (a.x + t * abx)
    val dy = p.y - (a.y + t * aby)
    return dx * dx + dy * dy
}

/** Euclidean length of the segment a–b in the metres plane. */
fun length(a: Vec2, b: Vec2): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Great-circle distance in metres.
 *
 * Used for ground truth in tests and for one-off measurements, *not* in the matching
 * hot path — see [MetricProjection] for why.
 */
fun haversineMeters(from: LatLon, to: LatLon): Double {
    val dLat = (to.lat - from.lat).toRadians()
    val dLon = (to.lon - from.lon).toRadians()
    val lat1 = from.lat.toRadians()
    val lat2 = to.lat.toRadians()
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceAtMost(1.0)))
}

/**
 * Compass bearing from [from] to [to], in degrees clockwise from north, in `[0, 360)`.
 *
 * Reserved for the bearing gate described in PLAN.md §5 — rejecting a candidate segment
 * whose direction disagrees with the direction of travel is what stops a parallel street
 * lighting up.
 */
fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val lat1 = from.lat.toRadians()
    val lat2 = to.lat.toRadians()
    val dLon = (to.lon - from.lon).toRadians()
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x).toDegrees() + 360.0) % 360.0
}

/**
 * Smallest angle between two bearings, ignoring direction of travel along the line:
 * result is in `[0, 90]`. Walking a street east-to-west must match a segment drawn
 * west-to-east, so 180° apart counts as aligned.
 */
fun bearingDifference(a: Double, b: Double): Double {
    val raw = ((a - b) % 360.0 + 360.0) % 360.0
    val folded = if (raw > 180.0) 360.0 - raw else raw
    return if (folded > 90.0) 180.0 - folded else folded
}

/**
 * Absolute angle between two headings, in `[0, 180]`.
 *
 * Unlike [bearingDifference] this keeps the direction of travel: doubling back down the
 * street you just came up is 180°, not 0°. Matching wants the folded version — a street
 * is the same street whichever way you walk it — but anything *choosing* where to go
 * next has to be able to tell carrying straight on from a U-turn.
 */
fun headingDifference(a: Double, b: Double): Double {
    val raw = ((a - b) % 360.0 + 360.0) % 360.0
    return if (raw > 180.0) 360.0 - raw else raw
}
