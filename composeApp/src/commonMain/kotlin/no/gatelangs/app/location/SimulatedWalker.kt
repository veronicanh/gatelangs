package no.gatelangs.app.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.Vec2
import no.gatelangs.app.geo.bearingDegrees
import no.gatelangs.app.geo.headingDifference
import no.gatelangs.app.model.Fix
import no.gatelangs.app.model.RoadNetwork
import kotlin.math.round
import kotlin.random.Random

/**
 * Walks the road network the way someone actually trying to cover a city would: it heads
 * for road it has not walked yet, and between those decisions it stays on the road it is
 * on.
 *
 * Two earlier versions missed that second half. The first picked a random turn at every
 * junction — a random walk, which on a street grid revisits the same few blocks forever.
 * The second headed for the nearest unwalked segment, which fixed coverage but still
 * wandered, and the reason is worth writing down because it is not obvious from the code:
 *
 * The matcher credits everything within the matcher's 15 m radius of a fix, so simply
 * walking down a street also credits the first few metres of every side street and much
 * of the block ahead. So by the time the walker reaches the next junction there is often
 * nothing unwalked *adjacent* to it, even though the street plainly continues. The old
 * planner treated that as "stuck here", fell through to the graph search, and was sent
 * off to whatever uncredited scrap lay nearest — usually a sliver a few hops to the side
 * that the bearing gate had refused. Repeat every 50 m and you get a walker that jinks
 * about a neighbourhood instead of walking down a street. On the bundled Oslo data it
 * changed course 35 times per 3 km.
 *
 * So there is a tier between the two: if nothing unwalked leads out of this junction but
 * the road carries on, carry on. It is what a person does, it costs nothing — that road
 * still has to be walked eventually — and on the same data it drops to 9 course changes
 * per 3 km while crediting about 45% more road, because the distance goes into road
 * instead of into detours.
 *
 * It still follows the graph rather than flying between points, because the point of the
 * simulation is to exercise the matching pipeline honestly — turning corners and passing
 * junctions are where the bearing gate and grid lookup have to behave. Fixes carry
 * positional noise, so matching is never handed perfect input.
 */
class SimulatedWalker(
    private val network: RoadNetwork,
    /**
     * Live view of what the matcher has credited. Read on every replan, so the walker
     * reacts to coverage as it accumulates rather than to a snapshot.
     */
    private val isWalked: (Int) -> Boolean = { false },
    private val speedMps: Double = WALKING_SPEED_MPS,
    private val tickMs: Long = 400,
    private val accuracyM: Double = 6.0,
    seed: Int = 20260917,
) : LocationSource {

    override val label: String = "Simulated walker"

    private val random = Random(seed)

    /** Node key -> segment ids touching it. Built by snapping endpoints to ~0.1 m. */
    private val adjacency: Map<Long, List<Int>> = buildAdjacency()

    /**
     * Segments this walker has physically traversed.
     *
     * Kept separately from [isWalked] because the matcher legitimately declines to
     * credit some of them — the bearing gate rejects a segment crossed at a right
     * angle, for instance. Without this, the planner would route to such a segment,
     * walk it, see it still uncredited, and route to it again forever.
     */
    private val traversed = HashSet<Int>()

    /** Current course, or null before the first step. */
    private var heading: Double? = null

    /**
     * Metres of ground covered on this stretch that these legs have already been over.
     * Resets the moment the walker reaches somewhere new. See [MAX_CARRY_ON_M].
     */
    private var rewalkedM = 0.0

    override fun fixes(): Flow<Fix> = flow {
        if (network.segments.isEmpty()) return@flow
        var node = startNode() ?: return@flow

        val route = ArrayDeque<Step>()
        var clock = 0L

        while (true) {
            if (route.isEmpty()) route.addAll(planFrom(node))

            if (route.isEmpty()) {
                // Everything reachable has been walked. Stand still rather than spin:
                // the flow stays alive so the UI keeps showing a position.
                emit(fixAt(nodePosition(node), clock))
                clock += tickMs
                delay(tickMs)
                continue
            }

            val step = route.removeFirst()
            val segment = network.segments[step.id]
            val from = if (step.forward) segment.a else segment.b
            val to = if (step.forward) segment.b else segment.a
            val lengthM = network.segmentLengths[step.id]
            val goingRoundAgain = step.id in traversed

            var travelled = 0.0
            while (travelled < lengthM) {
                travelled += speedMps * (tickMs / 1000.0)
                val t = (travelled / lengthM).coerceAtMost(1.0)
                emit(fixAt(interpolate(from, to, t), clock))
                clock += tickMs
                delay(tickMs)
            }

            traversed.add(step.id)
            rewalkedM = if (goingRoundAgain) rewalkedM + lengthM else 0.0
            heading = bearingDegrees(from, to)
            node = keyOf(to)
        }
    }

    // --- Routing -------------------------------------------------------------------

    private data class Step(val id: Int, val forward: Boolean)

    /** A segment worth going to: not yet credited, and not already tried. */
    private fun isTarget(id: Int): Boolean = id !in traversed && !isWalked(id)

    private fun planFrom(node: Long): List<Step> {
        val options = adjacency[node].orEmpty().mapNotNull { stepAlong(it, node) }

        // 1. Unwalked road leads out of this junction. Take the straightest of it —
        //    among equally useful turns, carrying on reads far better than zigzagging.
        val fresh = options.filter { isTarget(it.id) }
        if (fresh.isNotEmpty()) return listOf(fresh.minBy { turnOnto(it) })

        // 2. Nothing new here, but the road goes on: stay on it. This is the tier the
        //    wandering came from — without it the walker abandons a perfectly good
        //    street the moment the matcher has run ahead of it.
        //
        //    Held back once the walker is genuinely going round in circles, which is
        //    the one way this tier could otherwise loop forever: a block whose every
        //    junction is exhausted would be circled rather than left.
        if (rewalkedM < MAX_CARRY_ON_M) {
            val carryOn = options.filter { turnOnto(it) <= STRAIGHT_TURN_DEG }
                .minByOrNull { turnOnto(it) }
            if (carryOn != null) return listOf(carryOn)
        }

        // 3. The road has run out. Go and find some that has not been walked.
        return routeToNearestTarget(node)
    }

    /**
     * How far off the current course [step] would take us, in degrees `[0, 180]`.
     *
     * [headingDifference] rather than the matcher's `bearingDifference`: that one folds
     * to `[0, 90]`, so a U-turn scores the same as carrying straight on. Fine for asking
     * "is this the same street", useless for choosing where to go — and it is what lets
     * the segment we just walked back up look like the straightest way on.
     */
    private fun turnOnto(step: Step): Double {
        val current = heading ?: return 0.0
        val segment = network.segments[step.id]
        val from = if (step.forward) segment.a else segment.b
        val to = if (step.forward) segment.b else segment.a
        return headingDifference(current, bearingDegrees(from, to))
    }

    /**
     * Breadth-first search for the closest unwalked segment, returning the whole path.
     *
     * BFS by hop count rather than metres: segments are capped at ~25 m so hops are a
     * good proxy for distance, and it keeps the search to a plain queue. Only runs when
     * the road itself has run out, so it is far from a hot path.
     */
    private fun routeToNearestTarget(start: Long): List<Step> {
        val arrivedBy = HashMap<Long, Pair<Step, Long>>()
        val seen = HashSet<Long>().apply { add(start) }
        val queue = ArrayDeque<Long>().apply { addLast(start) }

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (id in adjacency[node].orEmpty()) {
                val step = stepAlong(id, node) ?: continue
                val next = otherEnd(id, node) ?: continue

                if (isTarget(id)) return pathTo(step, node, start, arrivedBy)

                if (seen.add(next)) {
                    arrivedBy[next] = step to node
                    queue.addLast(next)
                }
            }
        }
        return emptyList()
    }

    /** Walks the BFS parent chain back to [start] and flips it into travel order. */
    private fun pathTo(
        finalStep: Step,
        foundAt: Long,
        start: Long,
        arrivedBy: Map<Long, Pair<Step, Long>>,
    ): List<Step> {
        val path = ArrayList<Step>()
        path.add(finalStep)
        var cursor = foundAt
        while (cursor != start) {
            val (step, from) = arrivedBy[cursor] ?: break
            path.add(step)
            cursor = from
        }
        path.reverse()
        return path
    }

    /** Orients segment [id] so it leads away from [node], or null if it does not touch it. */
    private fun stepAlong(id: Int, node: Long): Step? {
        val segment = network.segments[id]
        return when (node) {
            keyOf(segment.a) -> Step(id, forward = true)
            keyOf(segment.b) -> Step(id, forward = false)
            else -> null
        }
    }

    private fun otherEnd(id: Int, node: Long): Long? {
        val segment = network.segments[id]
        val a = keyOf(segment.a)
        val b = keyOf(segment.b)
        return when (node) {
            a -> b
            b -> a
            else -> null
        }
    }

    // --- Position ------------------------------------------------------------------

    private fun fixAt(point: LatLon, clock: Long): Fix {
        val projection = network.projection
        val noiseM = accuracyM * NOISE_FRACTION
        val jittered = projection.unproject(
            Vec2(
                x = projection.x(point.lon) + (random.nextDouble() - 0.5) * noiseM,
                y = projection.y(point.lat) + (random.nextDouble() - 0.5) * noiseM,
            )
        )
        return Fix(
            lat = jittered.lat,
            lon = jittered.lon,
            accuracyM = accuracyM,
            timestampMs = clock,
        )
    }

    private fun interpolate(from: LatLon, to: LatLon, t: Double): LatLon {
        val projection = network.projection
        val a = projection.project(from)
        val b = projection.project(to)
        return projection.unproject(Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
    }

    /** Starts near the middle of the data, which is where the map opens. */
    private fun startNode(): Long? {
        val centre = network.bounds.center
        val nearby = network.index.near(centre, START_SEARCH_RADIUS_M)
        val chosen = nearby.firstOrNull() ?: network.segments.indices.firstOrNull() ?: return null
        return keyOf(network.segments[chosen].a)
    }

    private fun nodePosition(node: Long): LatLon {
        val id = adjacency[node]?.firstOrNull() ?: return network.bounds.center
        val segment = network.segments[id]
        return if (keyOf(segment.a) == node) segment.a else segment.b
    }

    private fun buildAdjacency(): Map<Long, List<Int>> {
        val map = HashMap<Long, MutableList<Int>>()
        network.segments.forEachIndexed { id, segment ->
            map.getOrPut(keyOf(segment.a)) { ArrayList() }.add(id)
            map.getOrPut(keyOf(segment.b)) { ArrayList() }.add(id)
        }
        return map
    }

    /**
     * Snaps a coordinate to ~1e-6 degrees (about 0.1 m) and packs it into one Long, so
     * endpoints that OSM shares between ways hash to the same node.
     */
    private fun keyOf(point: LatLon): Long {
        val lat = round(point.lat * 1e6).toLong()
        val lon = round(point.lon * 1e6).toLong()
        return lat * 1_000_000_000L + lon
    }

    companion object {
        /** Average walking pace. */
        const val WALKING_SPEED_MPS = 1.4

        /**
         * Up to this far off the current course still counts as the road carrying on.
         * Wide enough for a bend and for the kinks OSM leaves at junctions, narrow
         * enough that a genuine side street never passes for one — and far short of the
         * 180° that would let the walker double back and call it straight.
         */
        const val STRAIGHT_TURN_DEG = 35.0

        /**
         * How far the walker will carry on over ground it has already covered before it
         * gives up and searches instead.
         *
         * A liveness backstop rather than a tuning knob: any value from 25 m upwards
         * behaves identically on the bundled Oslo data, because there the walker reaches
         * somewhere new long before it accumulates this. What it rules out is the one
         * shape that would trap the carry-on tier — a closed block with nothing unwalked
         * on it, which would otherwise be circled forever.
         */
        const val MAX_CARRY_ON_M = 400.0

        private const val NOISE_FRACTION = 0.8
        private const val START_SEARCH_RADIUS_M = 500.0
    }
}
