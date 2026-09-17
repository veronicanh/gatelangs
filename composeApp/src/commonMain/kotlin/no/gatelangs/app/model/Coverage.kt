package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.bearingDegrees
import no.gatelangs.app.geo.bearingDifference
import no.gatelangs.app.geo.distanceToSegment

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

    /** Metres of [ids] walked. */
    fun walkedLengthOf(ids: IntArray): Double {
        var total = 0.0
        for (id in ids) if (walked[id]) total += network.segmentLengths[id]
        return total
    }

    /**
     * Fraction of [ids] walked, by length, in `[0, 1]`.
     *
     * By length rather than by segment count, and that matters: segmentize caps
     * segments at 25 m but leaves shorter spans alone, so they are not equal. Counting
     * them would make a street of many short bends read differently from the same street
     * drawn as a few long ones, and would disagree with [fraction] on the same screen.
     */
    fun fractionOf(ids: IntArray): Double {
        val total = network.lengthOf(ids)
        return if (total <= 0.0) 0.0 else walkedLengthOf(ids) / total
    }

    /** Fraction of one OSM way walked, by length. */
    fun fractionOfWay(wayId: Long): Double = fractionOf(network.segmentsByWay[wayId] ?: return 0.0)

    /** Fraction of a whole named street walked — every OSM way that carries the name. */
    fun fractionOfStreet(name: String): Double =
        fractionOf(network.segmentsByStreet[name] ?: return 0.0)

    /**
     * Folds a fix into the coverage, returning the segment newly marked walked, if any.
     *
     * You are on one road at a time, so exactly one is credited — see [bestMatch] for
     * which. An earlier version credited *every* segment within the radius, which is
     * where "streets I have not walked are marked" came from: walking four streets'
     * centrelines credited 2.43 km of road belonging to other streets, a third of
     * everything it marked. Picking one brings that to 0.10 km, and the 4.66 km it does
     * credit for those four streets is within 1% of their true length.
     *
     * The bearing gate was never what fixed this — the roads being wrongly marked are
     * the ones *parallel* to the one you are on, so they agree with your heading and
     * sail straight through it. Distance is what separates them.
     *
     * Widening the search while narrowing what is credited is deliberate — a generous
     * radius now only decides which road is nearest, so it buys tolerance of GPS drift
     * without buying false positives.
     *
     * Fixes too imprecise to mean anything are dropped ([MAX_USABLE_ACCURACY_M]): a
     * reading with a 90 m error circle cannot tell you which street you are on.
     */
    fun record(fix: Fix): IntArray {
        if (fix.accuracyM > MAX_USABLE_ACCURACY_M) {
            previous = fix
            return IntArray(0)
        }

        val heading = headingFrom(previous, fix)
        previous = fix

        val id = bestMatch(fix, heading)
        if (id < 0 || walked[id]) return IntArray(0)

        walked[id] = true
        walkedLengthM += network.segmentLengths[id]
        return intArrayOf(id)
    }

    /**
     * The segment the walker is most likely on, or -1 if none is close enough.
     *
     * Scored rather than filtered: distance, plus a penalty for pointing the wrong way.
     * A hard bearing filter was the first attempt and it under-credited badly — walking
     * with eight keyboard directions, or along any street that does not run square to
     * them, put the heading more than 45° off the road underfoot often enough that 11%
     * of fixes sitting squarely on a road matched nothing at all. The road below you not
     * lighting up is a worse failure than a crossing street occasionally doing so.
     *
     * As a score, alignment breaks ties instead of vetoing: at a junction both roads are
     * underfoot at nearly zero distance, and the penalty is what picks the one you are
     * travelling along. [MAX_BEARING_DIFFERENCE_DEG] survives as a much wider backstop,
     * refusing only roads you are crossing close to square.
     *
     * Deliberately considers segments already walked. Skipping them would mean that on a
     * street you have covered, the nearest *unwalked* thing wins instead — which is the
     * street next door. Re-walking a road has to keep matching that road, precisely so it
     * cannot be credited to its neighbour.
     */
    private fun bestMatch(fix: Fix, heading: Double?): Int {
        val radius = maxOf(MATCH_SEARCH_RADIUS_M, fix.accuracyM)
        val projection = network.projection
        val point = projection.project(fix.position)

        var best = -1
        var bestScore = Double.MAX_VALUE

        for (id in network.index.near(fix.position, radius)) {
            val segment = network.segments[id]
            val a = projection.project(segment.a)
            val b = projection.project(segment.b)

            val distance = distanceToSegment(point, a, b)
            if (distance > radius) continue

            var score = distance
            if (heading != null) {
                val offBy = bearingDifference(heading, bearingDegrees(segment.a, segment.b))
                if (offBy > MAX_BEARING_DIFFERENCE_DEG) continue
                // Expressed in metres so it can be weighed against distance directly:
                // square to your direction of travel costs the full penalty, in line
                // costs nothing.
                score += MISALIGNMENT_PENALTY_M * (offBy / 90.0)
            }

            if (score < bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
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

        /**
         * How far to look for the road you are on.
         *
         * Generous on purpose, and safe to be: it decides which road is *nearest*, not
         * how much road gets credited. Wide enough to find the street from its pavement,
         * which is the normal case now that pavements are not in the network themselves.
         */
        const val MATCH_SEARCH_RADIUS_M = 25.0

        /**
         * Beyond this angle you are crossing a road, not walking along it.
         *
         * Wide, because it is now a backstop rather than the main discriminator — see
         * [MISALIGNMENT_PENALTY_M], which does the actual work of preferring the road you
         * are travelling along. Narrowing it back towards 45° starts refusing the road
         * directly underfoot whenever your heading is a little off it.
         */
        const val MAX_BEARING_DIFFERENCE_DEG = 75.0

        /**
         * What a right-angle mismatch is worth, in metres of extra apparent distance.
         *
         * At 30 m it outweighs the whole search radius, so a road in line with you beats
         * a square one even when the square one is nearer — while a road underfoot still
         * beats an aligned road right at the edge of the radius.
         */
        const val MISALIGNMENT_PENALTY_M = 30.0

        /** Shorter than this and the heading is noise, not direction. */
        const val MIN_HEADING_DISTANCE_M = 4.0

        /** Longer than this between fixes and the two are not one continuous movement. */
        const val MAX_HEADING_GAP_MS = 30_000L
    }
}
