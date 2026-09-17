package no.gatelangs.app.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.Vec2
import no.gatelangs.app.geo.bearingDegrees
import no.gatelangs.app.geo.bearingDifference
import no.gatelangs.app.model.Fix
import no.gatelangs.app.model.RoadNetwork
import kotlin.math.round
import kotlin.random.Random

/**
 * Walks the road network the way someone actually trying to cover a city would: it
 * heads for road it has not walked yet.
 *
 * An earlier version picked a random turn at every junction. That is a random walk, and
 * a random walk on a street grid revisits the same few blocks over and over — it looked
 * like wandering in circles because that is exactly what it was. Coverage crept up
 * logarithmically and the demo stalled.
 *
 * The route is chosen in two tiers:
 *
 *  1. If any road at the current junction is unwalked, take it. No search needed, and
 *     it produces the natural behaviour of clearing a neighbourhood before moving on.
 *  2. Otherwise breadth-first search the graph for the nearest unwalked segment and
 *     walk the whole path to it, crossing already-walked roads on the way.
 *
 * It still follows the graph rather than flying between points, because the point of
 * the simulation is to exercise the matching pipeline honestly — turning corners and
 * passing junctions are where the bearing gate and grid lookup have to behave. Fixes
 * carry positional noise, so matching is never handed perfect input.
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

    private var heading: Double? = null

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

            var travelled = 0.0
            while (travelled < lengthM) {
                travelled += speedMps * (tickMs / 1000.0)
                val t = (travelled / lengthM).coerceAtMost(1.0)
                emit(fixAt(interpolate(from, to, t), clock))
                clock += tickMs
                delay(tickMs)
            }

            traversed.add(step.id)
            heading = bearingDegrees(from, to)
            node = keyOf(to)
        }
    }

    // --- Routing -------------------------------------------------------------------

    private data class Step(val id: Int, val forward: Boolean)

    /** A segment worth going to: not yet credited, and not already tried. */
    private fun isTarget(id: Int): Boolean = id !in traversed && !isWalked(id)

    private fun planFrom(node: Long): List<Step> {
        val here = adjacency[node].orEmpty().filter { isTarget(it) }
        if (here.isNotEmpty()) {
            val step = stepAlong(preferStraightest(here, node), node)
            if (step != null) return listOf(step)
        }
        return routeToNearestTarget(node)
    }

    /**
     * Among equally valid unwalked turns, carry straight on.
     *
     * Purely cosmetic, but a walker that zigzags at every junction reads as broken even
     * when its coverage is fine.
     */
    private fun preferStraightest(candidates: List<Int>, node: Long): Int {
        val current = heading ?: return candidates[random.nextInt(candidates.size)]
        return candidates.minBy { id ->
            val step = stepAlong(id, node)
            if (step == null) {
                Double.MAX_VALUE
            } else {
                val segment = network.segments[id]
                val from = if (step.forward) segment.a else segment.b
                val to = if (step.forward) segment.b else segment.a
                bearingDifference(current, bearingDegrees(from, to))
            }
        }
    }

    /**
     * Breadth-first search for the closest unwalked segment, returning the whole path.
     *
     * BFS by hop count rather than metres: segments are capped at ~25 m so hops are a
     * good proxy for distance, and it keeps the search to a plain queue. Only runs when
     * the current junction is exhausted, so it is far from a hot path.
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
        private const val NOISE_FRACTION = 0.8
        private const val START_SEARCH_RADIUS_M = 500.0
    }
}
