package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.bearingDegrees
import no.gatelangs.app.geo.bearingDifference
import no.gatelangs.app.geo.squaredDistanceToSegment

/** One GPS reading. [accuracyM] is the radius of the 68% confidence circle, as reported. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val timestampMs: Long,
) {
    val position: LatLon get() = LatLon(lat, lon)
}

/**
 * Tracks which segments of a [RoadNetwork] have been walked.
 *
 * Mutable and deliberately cheap: [record] runs once per GPS fix and touches only the
 * handful of segments the grid hands back.
 */
class Coverage(private val network: RoadNetwork) {

    private val walked = BooleanArray(network.segments.size)
    private var walkedLengthM = 0.0
    private var previous: Fix? = null

    /** Whether segment [id] has been walked. */
    fun isWalked(id: Int): Boolean = walked[id]

    fun walkedSegmentCount(): Int = walked.count { it }

    fun walkedLengthMeters(): Double = walkedLengthM

    /** Fraction of the network walked, by length, in `[0, 1]`. */
    fun fraction(): Double =
        if (network.totalLengthM == 0.0) 0.0 else walkedLengthM / network.totalLengthM

    /** Fraction of one OSM way walked, by segment count, in `[0, 1]`. */
    fun fractionOfWay(wayId: Long): Double {
        val ids = network.segmentsByWay[wayId] ?: return 0.0
        if (ids.isEmpty()) return 0.0
        return ids.count { walked[it] }.toDouble() / ids.size
    }

    /**
     * Folds a fix into the coverage, returning the segments newly marked walked.
     *
     * Rejects fixes too imprecise to mean anything ([MAX_USABLE_ACCURACY_M]); a reading
     * with a 90 m error circle would light up every street in the block.
     *
     * When a previous fix is available, candidates must also roughly align with the
     * direction of travel. This is what keeps a walk down one street from claiming the
     * parallel street 18 m away, which plain radius matching cannot distinguish.
     */
    fun record(fix: Fix): IntArray {
        if (fix.accuracyM > MAX_USABLE_ACCURACY_M) {
            previous = fix
            return IntArray(0)
        }

        val radius = maxOf(MIN_MATCH_RADIUS_M, fix.accuracyM)
        val heading = headingFrom(previous, fix)
        previous = fix

        val projection = network.projection
        val point = projection.project(fix.position)
        val radiusSquared = radius * radius

        val newlyWalked = ArrayList<Int>(4)
        for (id in network.index.near(fix.position, radius)) {
            if (walked[id]) continue

            val segment = network.segments[id]
            val a = projection.project(segment.a)
            val b = projection.project(segment.b)
            if (squaredDistanceToSegment(point, a, b) > radiusSquared) continue

            if (heading != null) {
                val segmentBearing = bearingDegrees(segment.a, segment.b)
                if (bearingDifference(heading, segmentBearing) > MAX_BEARING_DIFFERENCE_DEG) continue
            }

            walked[id] = true
            walkedLengthM += network.segmentLengths[id]
            newlyWalked.add(id)
        }
        return newlyWalked.toIntArray()
    }

    /** Segment ids walked so far, for persistence. */
    fun walkedIds(): IntArray = walked.indices.filter { walked[it] }.toIntArray()

    /** Restores previously walked ids, e.g. after a reload. Ignores out-of-range ids. */
    fun restore(ids: IntArray) {
        for (id in ids) {
            if (id !in walked.indices || walked[id]) continue
            walked[id] = true
            walkedLengthM += network.segmentLengths[id]
        }
    }

    /**
     * Direction of travel, or null when it cannot be trusted — no previous fix, the two
     * fixes are too close together for the angle to mean anything, or too far apart in
     * time to be one continuous movement.
     */
    private fun headingFrom(previous: Fix?, current: Fix): Double? {
        if (previous == null) return null
        val elapsed = current.timestampMs - previous.timestampMs
        if (elapsed <= 0 || elapsed > MAX_HEADING_GAP_MS) return null
        val moved = no.gatelangs.app.geo.haversineMeters(previous.position, current.position)
        if (moved < MIN_HEADING_DISTANCE_M) return null
        return bearingDegrees(previous.position, current.position)
    }

    companion object {
        /** Below this, a fix cannot distinguish a street from its neighbour. */
        const val MAX_USABLE_ACCURACY_M = 30.0

        /** Even a perfect fix is credited this generously — pavements are not centrelines. */
        const val MIN_MATCH_RADIUS_M = 15.0

        /** Beyond this angle, the segment is not the street being walked. */
        const val MAX_BEARING_DIFFERENCE_DEG = 45.0

        /** Shorter than this and the heading is noise, not direction. */
        const val MIN_HEADING_DISTANCE_M = 4.0

        /** Longer than this between fixes and the two are not one continuous movement. */
        const val MAX_HEADING_GAP_MS = 30_000L
    }
}
