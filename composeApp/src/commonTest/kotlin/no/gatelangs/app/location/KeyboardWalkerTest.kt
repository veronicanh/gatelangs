package no.gatelangs.app.location

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.haversineMeters
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.Fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ORIGIN = LatLon(59.9139, 10.7522)
private val PROJECTION = MetricProjection(ORIGIN)

private fun walker() = KeyboardWalker(start = ORIGIN, projection = PROJECTION)

/** Ground covered per tick at the defaults, which is what the step assertions are against. */
private val STEP_M = KeyboardWalker.MAP_SPEED_MPS * (KeyboardWalker.TICK_MS / 1000.0)

/** Generous next to a 4.5 m step, tight enough to catch a wrong speed or a lost tick. */
private const val TOLERANCE_M = 0.05

class KeyboardWalkerTest {

    @Test
    fun `shows a position before a key is touched`() = runTest {
        // Otherwise the map has no marker until you press something, which reads as the
        // walk having failed to start.
        val first = walker().fixes().take(1).toList().single()
        assertEquals(ORIGIN.lat, first.lat, 1e-9)
        assertEquals(ORIGIN.lon, first.lon, 1e-9)
    }

    @Test
    fun `holding a key walks that way, at the pace it claims`() = runTest {
        val walker = walker()
        walker.press(WalkDirection.NORTH)

        val fixes = walker.fixes().take(5).toList() // the opening fix plus four steps
        val last = fixes.last().position

        assertEquals(4 * STEP_M, haversineMeters(ORIGIN, last), TOLERANCE_M)
        assertTrue(last.lat > ORIGIN.lat, "north should increase latitude")
        assertEquals(ORIGIN.lon, last.lon, 1e-9)
    }

    @Test
    fun `diagonals are not faster than going straight`() = runTest {
        // Adding the two unit pushes and walking the result would make north-east 1.41x
        // quicker than north, which is the classic way this goes wrong.
        val walker = walker()
        walker.press(WalkDirection.NORTH)
        walker.press(WalkDirection.EAST)

        val last = walker.fixes().take(5).toList().last().position

        assertEquals(4 * STEP_M, haversineMeters(ORIGIN, last), TOLERANCE_M)
        assertTrue(last.lat > ORIGIN.lat && last.lon > ORIGIN.lon, "should have gone north-east")
    }

    @Test
    fun `opposite keys cancel rather than fighting`() = runTest {
        val walker = walker()
        walker.press(WalkDirection.NORTH)
        walker.press(WalkDirection.SOUTH)

        val fixes = mutableListOf<Fix>()
        withTimeoutOrNull(2_000) { walker.fixes().collect { fixes.add(it) } }

        assertEquals(1, fixes.size, "standing still should emit nothing after the first fix")
    }

    @Test
    fun `stops when the keys are let go`() = runTest {
        val walker = walker()
        walker.press(WalkDirection.EAST)

        val fixes = mutableListOf<Fix>()
        withTimeoutOrNull(3_000) {
            walker.fixes().collect {
                fixes.add(it)
                if (fixes.size == 3) walker.releaseAll()
            }
        }

        assertEquals(3, fixes.size, "kept moving after the keys were released")
    }

    @Test
    fun `shift multiplies the pace`() = runTest {
        val cruising = walker().apply { press(WalkDirection.NORTH) }
        val sprinting = walker().apply { press(WalkDirection.NORTH); sprint(true) }

        val slow = haversineMeters(ORIGIN, cruising.fixes().take(4).toList().last().position)
        val fast = haversineMeters(ORIGIN, sprinting.fixes().take(4).toList().last().position)

        assertEquals(KeyboardWalker.SPRINT_MULTIPLIER, fast / slow, 0.01)
    }

    @Test
    fun `steps far enough apart for the matcher to read a heading`() = runTest {
        // The constraint that sets the speed, and the reason it is nothing like walking
        // pace. Below Coverage.MIN_HEADING_DISTANCE_M the matcher cannot tell which way
        // you are going, drops the bearing gate, and credits every segment within 15 m —
        // so walking one street lights up the pavement and the street beside it.
        val walker = walker()
        walker.press(WalkDirection.EAST)

        val gaps = walker.fixes().take(6).toList()
            .zipWithNext { a, b -> haversineMeters(a.position, b.position) }

        assertTrue(
            gaps.all { it >= Coverage.MIN_HEADING_DISTANCE_M },
            "fixes are ${gaps.minOrNull()} m apart, under the matcher's " +
                "${Coverage.MIN_HEADING_DISTANCE_M} m floor",
        )
    }
}
