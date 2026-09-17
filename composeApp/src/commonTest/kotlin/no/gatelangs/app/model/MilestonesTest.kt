package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val MILESTONE_ORIGIN = LatLon(59.9139, 10.7522)
private val MILESTONE_PROJECTION = MetricProjection(MILESTONE_ORIGIN)

private fun spot(east: Double, north: Double): LatLon =
    MILESTONE_PROJECTION.unproject(Vec2(east, north))

/** 400 m of Parkveien in two OSM ways, a side street, and a stretch OSM never named. */
private fun streets(): RoadNetwork = RoadNetwork.from(
    listOf(
        RawWay(1L, "Parkveien", listOf(spot(0.0, 0.0), spot(200.0, 0.0))),
        RawWay(2L, "Parkveien", listOf(spot(200.0, 0.0), spot(400.0, 0.0))),
        RawWay(3L, "Sidegata", listOf(spot(0.0, 100.0), spot(200.0, 100.0))),
        RawWay(4L, null, listOf(spot(0.0, 200.0), spot(200.0, 200.0))),
    )
)

/**
 * Walks [street] in order until at least [fraction] of its *length* is done, and reports
 * what that took.
 *
 * By length rather than by segment count, because that is what a milestone measures and
 * the two do not agree: segmentize caps at 25 m but leaves shorter spans alone, so
 * Parkveien is 17 segments and its first eight are 47% of it, not half.
 */
private fun Coverage.walkUpTo(network: RoadNetwork, street: String, fraction: Double): IntArray {
    val ids = network.segmentsByStreet.getValue(street).sortedArray()
    val total = network.lengthOf(ids)
    val step = mutableListOf<Int>()
    var done = walkedLengthOf(ids)
    for (id in ids) {
        if (done / total >= fraction) break
        if (isWalked(id)) continue
        step += id
        done += network.segmentLengths[id]
    }
    val walked = step.toIntArray()
    restore(walked)
    return walked
}

/** Ids of [street] that are not yet walked, in order. */
private fun Coverage.remaining(network: RoadNetwork, street: String): IntArray =
    network.segmentsByStreet.getValue(street).filter { !isWalked(it) }.toIntArray()

class MilestonesTest {

    @Test
    fun `crossing halfway is announced once`() {
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val half = coverage.walkUpTo(network, "Parkveien", 0.5)
        assertEquals(
            listOf(Achievement("Parkveien", Milestone.HALFWAY)),
            milestones.check(coverage, half),
        )

        // One more segment is still the same milestone, and says nothing.
        val next = coverage.remaining(network, "Parkveien").take(1).toIntArray()
        coverage.restore(next)
        assertEquals(emptyList(), milestones.check(coverage, next))
    }

    @Test
    fun `each milestone fires as it is passed`() {
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        assertEquals(
            listOf(Achievement("Parkveien", Milestone.HALFWAY)),
            milestones.check(coverage, coverage.walkUpTo(network, "Parkveien", 0.5)),
        )
        assertEquals(
            listOf(Achievement("Parkveien", Milestone.NEARLY)),
            milestones.check(coverage, coverage.walkUpTo(network, "Parkveien", 0.8)),
        )

        val rest = coverage.remaining(network, "Parkveien")
        coverage.restore(rest)
        assertEquals(listOf(Achievement("Parkveien", Milestone.CLEARED)), milestones.check(coverage, rest))
    }

    @Test
    fun `finishing a street in one go announces only that it is cleared`() {
        // Three popups for one step would be noise. The skipped milestones still count
        // as reached, so they cannot fire later.
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val all = network.segmentsByStreet.getValue("Parkveien")
        coverage.restore(all)

        assertEquals(listOf(Achievement("Parkveien", Milestone.CLEARED)), milestones.check(coverage, all))
    }

    @Test
    fun `a cleared street stays cleared and says nothing more`() {
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val all = network.segmentsByStreet.getValue("Parkveien")
        coverage.restore(all)
        milestones.check(coverage, all)

        assertEquals(emptyList(), milestones.check(coverage, all), "a second lap must not re-announce")
    }

    @Test
    fun `a street spanning two ways is not cleared by finishing one of them`() {
        // Parkveien is two OSM ways, the way a real street is. Walking the first is
        // reaching the middle of the street, not the end of it.
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val firstWay = network.segmentsByWay.getValue(1L)
        coverage.restore(firstWay)

        val earned = milestones.check(coverage, firstWay)
        assertTrue(
            earned.none { it.milestone == Milestone.CLEARED },
            "half a street was announced as cleared: $earned",
        )
        assertTrue(coverage.fractionOfStreet("Parkveien") < 0.6)
    }

    @Test
    fun `restoring a walk in progress announces nothing`() {
        // Reopening the app part way through the city must not fire a popup for every
        // street already past halfway. This is what seed is for.
        val network = streets()
        val coverage = Coverage(network)
        coverage.walkUpTo(network, "Parkveien", 0.9)
        coverage.restore(network.segmentsByStreet.getValue("Sidegata"))

        val milestones = Milestones(network).apply { seed(coverage) }

        // Only the last stretch of Parkveien is left; finishing it is genuinely new.
        val rest = coverage.remaining(network, "Parkveien")
        coverage.restore(rest)
        assertEquals(listOf(Achievement("Parkveien", Milestone.CLEARED)), milestones.check(coverage, rest))
    }

    @Test
    fun `unnamed road never earns anything`() {
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val unnamed = network.segmentsByWay.getValue(4L)
        coverage.restore(unnamed)

        assertTrue(milestones.check(coverage, unnamed).isEmpty(), "unnamed road is a category, not a street")
    }

    @Test
    fun `nothing walked earns nothing`() {
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }
        assertEquals(emptyList(), milestones.check(coverage, IntArray(0)))
    }

    @Test
    fun `two streets finishing on the same fix both report`() {
        // The junction case, and the reason the view model queues rather than replaces.
        val network = streets()
        val coverage = Coverage(network)
        val milestones = Milestones(network).apply { seed(coverage) }

        val both = network.segmentsByStreet.getValue("Parkveien") +
            network.segmentsByStreet.getValue("Sidegata")
        coverage.restore(both)

        val earned = milestones.check(coverage, both)
        assertEquals(2, earned.size, "both streets crossed a line")
        assertTrue(earned.all { it.milestone == Milestone.CLEARED })
        assertEquals(setOf("Parkveien", "Sidegata"), earned.map { it.street }.toSet())
    }
}
