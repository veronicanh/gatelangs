package no.gatelangs.app.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.Vec2
import no.gatelangs.app.geo.length
import no.gatelangs.app.model.Fix
import no.gatelangs.app.model.RoadNetwork
import kotlin.math.round
import kotlin.random.Random

/**
 * Walks the road network, emitting fixes as a real walker would.
 *
 * It follows the *graph* rather than drifting in a straight line, because the point is
 * to exercise the matching pipeline honestly: turning corners, passing junctions and
 * doubling back are exactly the cases where the bearing gate and the grid lookup have
 * to behave. A walker that ignored the roads would prove nothing.
 *
 * Fixes carry a little positional noise, so matching is never handed perfect input.
 */
class SimulatedWalker(
    private val network: RoadNetwork,
    private val speedMps: Double = WALKING_SPEED_MPS,
    private val tickMs: Long = 400,
    private val accuracyM: Double = 6.0,
    seed: Int = 20260917,
) : LocationSource {

    override val label: String = "Simulated walker"

    private val random = Random(seed)

    /** Node key -> segment ids touching it. Built by snapping endpoints to ~0.1 m. */
    private val adjacency: Map<Long, List<Int>> = buildAdjacency()

    override fun fixes(): Flow<Fix> = flow {
        if (network.segments.isEmpty()) return@flow

        var segmentId = startingSegment()
        var forward = true
        var travelled = 0.0
        var clock = 0L

        while (true) {
            val segment = network.segments[segmentId]
            val from = if (forward) segment.a else segment.b
            val to = if (forward) segment.b else segment.a
            val segmentLength = network.segmentLengths[segmentId]

            if (segmentLength <= 0.0) {
                val next = stepFrom(to, segmentId) ?: (startingSegment() to true)
                segmentId = next.first
                forward = next.second
                travelled = 0.0
                continue
            }

            travelled += speedMps * (tickMs / 1000.0)
            if (travelled >= segmentLength) {
                // Arrived at the far node: choose where to go next from there.
                val next = stepFrom(to, segmentId) ?: (startingSegment() to true)
                segmentId = next.first
                forward = next.second
                travelled = 0.0
                emit(fixAt(to, clock))
                clock += tickMs
                delay(tickMs)
                continue
            }

            val t = travelled / segmentLength
            emit(fixAt(interpolate(from, to, t), clock))
            clock += tickMs
            delay(tickMs)
        }
    }

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

    /**
     * Picks the next segment to walk from [node], avoiding an immediate U-turn where the
     * junction offers anything else — otherwise the walker oscillates on one segment.
     */
    private fun stepFrom(node: LatLon, cameFrom: Int): Pair<Int, Boolean>? {
        val options = adjacency[keyOf(node)].orEmpty().filter { it != cameFrom }
        val chosen = when {
            options.isNotEmpty() -> options[random.nextInt(options.size)]
            else -> cameFrom // dead end: turn around
        }
        val segment = network.segments[chosen]
        // Walk away from the node we are standing on.
        val forward = keyOf(segment.a) == keyOf(node)
        return chosen to forward
    }

    private fun startingSegment(): Int {
        // Prefer a junction, so the walk has somewhere to go.
        val junction = adjacency.entries.firstOrNull { it.value.size >= 3 }
        return junction?.value?.first() ?: random.nextInt(network.segments.size)
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
    }
}
