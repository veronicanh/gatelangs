package no.gatelangs.app.location

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.RawWay
import no.gatelangs.app.model.RoadNetwork
import kotlin.test.Test
import kotlin.test.assertTrue

private val ORIGIN = LatLon(59.9139, 10.7522)
private val PROJECTION = MetricProjection(ORIGIN)

private fun at(east: Double, north: Double): LatLon = PROJECTION.unproject(Vec2(east, north))

/**
 * Fixes needed to travel [metres] at the walker's defaults (1.4 m/s, one fix per
 * 400 ms = 0.56 m per fix). Budgets are derived rather than guessed — an under-budgeted
 * test reads as a routing failure when it is really just a short walk.
 */
private fun fixesToTravel(metres: Double): Int =
    (metres / (SimulatedWalker.WALKING_SPEED_MPS * 0.4)).toInt() + 50

/** A single straight 400 m street: 16 segments, no choices to make. */
private fun straightNetwork(): RoadNetwork =
    RoadNetwork.from(listOf(RawWay(1L, "Langgata", listOf(at(0.0, 0.0), at(400.0, 0.0)))))

/**
 * A 3x3 ladder: three 200 m streets running east (ways 1-3), three running north
 * (ways 4-6), meeting at junctions. 1200 m of road, and plenty of turns for a random
 * walk to get lost in.
 */
private fun gridNetwork(): RoadNetwork {
    val ways = ArrayList<RawWay>()
    for (row in 0..2) {
        ways.add(RawWay(1L + row, "East $row", listOf(at(0.0, row * 100.0), at(200.0, row * 100.0))))
    }
    for (col in 0..2) {
        ways.add(RawWay(4L + col, "North $col", listOf(at(col * 100.0, 0.0), at(col * 100.0, 200.0))))
    }
    return RoadNetwork.from(ways)
}

private const val GRID_LENGTH_M = 1_200.0

class SimulatedWalkerTest {

    @Test
    fun `covers a straight street`() = runTest {
        // It starts mid-street, so one end plus the full length back: 600 m of travel.
        val network = straightNetwork()
        val coverage = Coverage(network)
        val walker = SimulatedWalker(network, isWalked = coverage::isWalked)

        walker.fixes().take(fixesToTravel(600.0)).toList().forEach { coverage.record(it) }

        assertTrue(
            coverage.fraction() > 0.95,
            "only covered ${(coverage.fraction() * 100).toInt()}% of a single street",
        )
    }

    @Test
    fun `covers a grid without getting lost in it`() = runTest {
        // The case the random walk failed: on a grid it revisited the same blocks and
        // coverage crept up logarithmically. Budget is 2x the network length, so there
        // is room to backtrack but not room to wander.
        val network = gridNetwork()
        val coverage = Coverage(network)
        val walker = SimulatedWalker(network, isWalked = coverage::isWalked)

        walker.fixes().take(fixesToTravel(GRID_LENGTH_M * 2)).toList().forEach { coverage.record(it) }

        assertTrue(
            coverage.fraction() > 0.9,
            "only covered ${(coverage.fraction() * 100).toInt()}% of the grid",
        )
    }

    @Test
    fun `spends its distance on new road rather than re-walking`() = runTest {
        // The efficiency property behind the whole change: walking one network-length
        // should cover most of the network. A random walk manages roughly a third.
        val network = gridNetwork()
        val coverage = Coverage(network)

        SimulatedWalker(network, isWalked = coverage::isWalked)
            .fixes().take(fixesToTravel(GRID_LENGTH_M)).toList().forEach { coverage.record(it) }

        assertTrue(
            coverage.fraction() > 0.7,
            "one network-length of walking only covered ${(coverage.fraction() * 100).toInt()}%",
        )
    }

    @Test
    fun `heads for road the matcher has not credited`() = runTest {
        // Directly the requested behaviour, and the one case that exercises isWalked
        // rather than the walker's own memory: the east streets are already credited
        // from a previous session, so it should go and do the north ones.
        val network = gridNetwork()
        val coverage = Coverage(network)

        val eastIds = (1L..3L).flatMap { network.segmentsByWay[it]?.toList().orEmpty() }
        coverage.restore(eastIds.toIntArray())

        val northBefore = (4L..6L).sumOf { coverage.fractionOfWay(it) }
        SimulatedWalker(network, isWalked = coverage::isWalked)
            .fixes().take(fixesToTravel(900.0)).toList().forEach { coverage.record(it) }
        val northAfter = (4L..6L).sumOf { coverage.fractionOfWay(it) }

        assertTrue(
            northAfter > northBefore + 2.0,
            "north streets went from $northBefore to $northAfter of 3.0 — it did not " +
                "prioritise the uncredited roads",
        )
    }

    @Test
    fun `routes across walked roads to reach unwalked ones`() = runTest {
        // Only the middle is pre-walked, so the greedy tier finds nothing at the start
        // node and the BFS tier has to carry it out of the walked patch.
        val network = gridNetwork()
        val coverage = Coverage(network)
        coverage.restore(network.index.near(network.bounds.center, 40.0))
        val preWalked = coverage.walkedSegmentCount()
        assertTrue(preWalked in 1 until network.segments.size, "fixture should pre-walk part of the grid")

        SimulatedWalker(network, isWalked = coverage::isWalked)
            .fixes().take(fixesToTravel(400.0)).toList().forEach { coverage.record(it) }

        assertTrue(
            coverage.walkedSegmentCount() > preWalked,
            "walker never got past the already-walked area around the start",
        )
    }

    @Test
    fun `keeps emitting once everything is walked`() = runTest {
        // It must idle rather than end the flow or spin: the UI still wants a position.
        val network = straightNetwork()
        val fixes = SimulatedWalker(network, isWalked = { true }).fixes().take(20).toList()
        assertTrue(fixes.size == 20, "expected a steady stream, got ${fixes.size}")
    }

    @Test
    fun `stays on the road network`() = runTest {
        // Every fix should sit within GPS noise of a real segment, otherwise the walker
        // is flying between points rather than following the graph.
        val network = gridNetwork()
        for (fix in SimulatedWalker(network).fixes().take(300).toList()) {
            assertTrue(
                network.index.near(fix.position, 30.0).isNotEmpty(),
                "fix at ${fix.position} is nowhere near a road",
            )
        }
    }
}
