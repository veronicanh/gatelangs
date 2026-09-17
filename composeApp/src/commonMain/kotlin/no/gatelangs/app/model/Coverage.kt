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

    /** The segment the last fix matched, or -1. Used to fill the span between fixes. */
    private var previousMatch = -1

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
     * Folds a fix into the coverage, returning the segments newly marked walked.
     *
     * Two questions, kept apart on purpose. *Which road are you on* is answered once, by
     * [bestMatch], and exactly one road wins — crediting every road within a radius is
     * where "streets I have not walked are marked" came from: walking four streets'
     * centrelines credited 2.43 km belonging to other streets, a third of everything it
     * marked. *How much of that road did you just cover* is then answered generously,
     * because once the road is settled there is nothing left to get wrong: more of the
     * road underfoot is the road you are walking on.
     *
     * Generous in two ways, each fixing a way road went unmarked:
     *
     * - [creditRoadUnderfoot] takes the rest of the same road within [CREDIT_RADIUS_M].
     *   At a junction a way often starts with a two-metre stub that no fix is ever
     *   *nearest* to, so it stayed grey with the walker standing on it.
     * - [bridgeFromPreviousFix] fills the span between this match and the last one when
     *   both are the same road. Fixes land 4.5 m apart walking and 13.5 m apart
     *   sprinting, while a bend in a street produces segments far shorter than the 25 m
     *   cap — so short segments were being stepped clean over and never credited.
     *
     * Neither can reach another street: both are gated on [sameRoad].
     *
     * Fixes too imprecise to mean anything are dropped ([MAX_USABLE_ACCURACY_M]): a
     * reading with a 90 m error circle cannot tell you which street you are on.
     */
    fun record(fix: Fix): IntArray {
        if (fix.accuracyM > MAX_USABLE_ACCURACY_M) {
            previous = fix
            previousMatch = -1
            return IntArray(0)
        }

        val heading = headingFrom(previous, fix)
        previous = fix

        val matched = bestMatch(fix, heading)
        if (matched < 0) {
            previousMatch = -1
            return IntArray(0)
        }

        val newly = ArrayList<Int>(4)
        mark(matched, newly)
        creditRoadUnderfoot(fix, matched, newly)
        bridgeFromPreviousFix(matched, newly)
        previousMatch = matched

        return newly.toIntArray()
    }

    private fun mark(id: Int, into: MutableList<Int>) {
        if (walked[id]) return
        walked[id] = true
        walkedLengthM += network.segmentLengths[id]
        into += id
    }

    /**
     * Whether two segments are the same road as a walker would name it.
     *
     * By name where there is one, because OSM splits a street wherever its tags change
     * and Trondheimsveien arrives as 27 ways — stopping at every one of those seams would
     * put the holes back. By way id where there is not, which keeps unnamed roads from
     * all counting as one.
     */
    private fun sameRoad(a: Int, b: Int): Boolean {
        if (network.segments[a].wayId == network.segments[b].wayId) return true
        val name = network.streetNameOf(a) ?: return false
        return name == network.streetNameOf(b)
    }

    /** Credits the rest of the matched road lying within [CREDIT_RADIUS_M] of the fix. */
    private fun creditRoadUnderfoot(fix: Fix, matched: Int, into: MutableList<Int>) {
        val projection = network.projection
        val point = projection.project(fix.position)
        for (id in network.index.near(fix.position, CREDIT_RADIUS_M)) {
            if (walked[id] || !sameRoad(id, matched)) continue
            val segment = network.segments[id]
            val a = projection.project(segment.a)
            val b = projection.project(segment.b)
            if (distanceToSegment(point, a, b) > CREDIT_RADIUS_M) continue
            mark(id, into)
        }
    }

    /**
     * Credits the road between the previous fix's match and this one.
     *
     * Ids from one way are consecutive — [RoadNetwork] flattens each way in turn — so the
     * span is a range, and everything in it belonging to the same road is taken. By road
     * rather than by way: a street crossing a seam between two of its own OSM ways is the
     * commonest place for a fix to land, and stopping at the seam left a hole there.
     *
     * Only when the span is short enough ([MAX_BRIDGE_M]) to be a stride rather than a
     * teleport; anything longer is a GPS jump, a fresh start, or a second stretch of the
     * same street on the other side of a square, and filling it in would draw a line down
     * a road nobody walked. That cap is also what makes matching by *name* safe here.
     */
    private fun bridgeFromPreviousFix(matched: Int, into: MutableList<Int>) {
        val from = previousMatch
        if (from < 0 || from == matched || !sameRoad(from, matched)) return

        val low = minOf(from, matched)
        val high = maxOf(from, matched)
        // Two ways of one street are usually neighbours in the list but are not promised
        // to be, and the loop below walks the whole range. A stride is a handful of ids;
        // anything wider is two distant stretches of a street sharing a name, which
        // [MAX_BRIDGE_M] would reject anyway after scanning half the city to find out.
        if (high - low > MAX_BRIDGE_SEGMENTS) return

        var spanned = 0.0
        for (id in low..high) {
            if (!sameRoad(id, matched)) continue
            spanned += network.segmentLengths[id]
            if (spanned > MAX_BRIDGE_M) return
        }
        for (id in low..high) {
            if (sameRoad(id, matched)) mark(id, into)
        }
    }

    /**
     * The segment the walker is most likely on, or -1 if none is close enough.
     *
     * Scored rather than filtered: distance, plus a penalty for pointing the wrong way.
     * A hard bearing filter was the first attempt and it under-credited badly — walking
     * with eight keyboard directions, or along any street that does not run square to
     * them, put the heading more than 45 deg off the road underfoot often enough that 11%
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
         * How much of the matched road counts as covered by one fix.
         *
         * Six metres, which is where the measurements put the knee. Walking five streets
         * end to end with the keyboard: at 0 m the sprint leaves 19% of the road behind,
         * at 6 m that is 2% for barely any extra wrong marking (0.7% to 0.9%), and wider
         * radii buy almost no coverage while multiplying the wrong marking — 15 m costs
         * 2.3%, and 13% once GPS noise is in play. It only ever reaches the road already
         * chosen, so what it widens is how much of *that* road counts, never which road.
         */
        const val CREDIT_RADIUS_M = 6.0

        /**
         * The longest span between two fixes that still counts as having walked it.
         *
         * Fixes arrive 4.5 m apart at walking speed and 13.5 m apart sprinting, so this
         * is several strides of headroom and still far short of the distance a GPS jump
         * covers.
         */
        const val MAX_BRIDGE_M = 80.0

        /** A cheap bound on the bridging scan; see [bridgeFromPreviousFix]. */
        const val MAX_BRIDGE_SEGMENTS = 64

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
