package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ORIGIN = LatLon(59.9139, 10.7522)
private val PROJECTION = MetricProjection(ORIGIN)

private fun at(east: Double, north: Double): LatLon = PROJECTION.unproject(Vec2(east, north))

private fun fix(east: Double, north: Double, accuracy: Double = 5.0, t: Long = 0L) =
    Fix(at(east, north).lat, at(east, north).lon, accuracy, t)

/** A rectangle from [west] to [east], tall enough to hold every street in [town]. */
private fun box(west: Double, east: Double): List<LatLon> = listOf(
    at(west, -50.0),
    at(east, -50.0),
    at(east, 300.0),
    at(west, 300.0),
    at(west, -50.0),
)

/**
 * Parkveien crosses the bydel boundary at 200 m, which is the whole point of the fixture:
 * the street percentage must not change depending on which side of it you are standing.
 * Plus a side street, and two stretches OSM never named.
 */
private fun town(): RoadNetwork = RoadNetwork.from(
    ways = listOf(
        RawWay(1L, "Parkveien", listOf(at(0.0, 0.0), at(200.0, 0.0))),
        RawWay(2L, "Parkveien", listOf(at(200.0, 0.0), at(400.0, 0.0))),
        RawWay(3L, "Sidegata", listOf(at(0.0, 100.0), at(100.0, 100.0))),
        RawWay(4L, null, listOf(at(0.0, 200.0), at(100.0, 200.0))),
        RawWay(5L, null, listOf(at(0.0, 250.0), at(100.0, 250.0))),
    ),
    districts = listOf(
        District("Vest", listOf(box(west = -50.0, east = 200.0))),
        District("Øst", listOf(box(west = 200.0, east = 500.0))),
    ),
)

/** The same town with no bydel outlines — the shape the Overpass fallback produces. */
private fun townWithoutDistricts(): RoadNetwork = RoadNetwork.from(
    ways = listOf(RawWay(1L, "Parkveien", listOf(at(0.0, 0.0), at(200.0, 0.0)))),
)

class WhereaboutsTest {

    @Test
    fun `knows nothing before the first fix`() {
        val network = town()
        val coverage = Coverage(network)

        assertEquals(-1, coverage.currentSegment)
        assertNull(coverage.whereabouts(network))
    }

    @Test
    fun `names the street and the bydel under the last fix`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.record(fix(50.0, 0.0))

        val here = coverage.whereabouts(network)
        assertEquals("Parkveien", here?.street)
        assertEquals("Vest", here?.district)
        // A named street carries no way id: OSM splits Parkveien in two here, and keeping
        // the id would make the seam at 200 m look like a change of location.
        assertNull(here?.wayId)
    }

    @Test
    fun `forgets where you are when a fix matches nothing`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.record(fix(50.0, 0.0))
        coverage.record(fix(50.0, 800.0))

        assertNull(coverage.whereabouts(network))
    }

    @Test
    fun `forgets where you are when the fix is too imprecise to place`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.record(fix(50.0, 0.0))
        // A 90 m error circle cannot tell Parkveien from Sidegata. Rejected fixes take a
        // different code path out of record() than unmatched ones, so this is its own test.
        coverage.record(fix(50.0, 0.0, accuracy = 90.0, t = 1_000L))

        assertNull(coverage.whereabouts(network))
    }

    @Test
    fun `re-walking a finished street still says where you are`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByStreet.getValue("Parkveien"))

        val walked = coverage.record(fix(50.0, 0.0))

        // Nothing new was credited, so nothing downstream gets told coverage changed —
        // and the readout must still know which street is underfoot. Driving it off the
        // coverage revision instead of off every fix is exactly the bug this catches.
        assertTrue(walked.isEmpty(), "the street was already finished")
        assertEquals("Parkveien", coverage.whereabouts(network)?.street)
    }

    @Test
    fun `an unnamed road is placed by its way`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.record(fix(50.0, 200.0))

        val here = coverage.whereabouts(network)
        assertNull(here?.street)
        assertEquals(4L, here?.wayId)
        assertEquals("Vest", here?.district)
    }
}

class WhereaboutsProgressTest {

    @Test
    fun `the street percentage is the whole street, not the part in this bydel`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByWay.getValue(1L)) // Parkveien west of the border
        coverage.record(fix(50.0, 0.0))

        val here = coverage.whereabouts(network)!!
        val street = coverage.streetProgress(network, here)!!

        // Half of Parkveien, standing on the finished half. The bydel-scoped view of the
        // same street says something else entirely, and that difference is the decision:
        // a street reads the same wherever on it you are standing.
        assertEquals("Parkveien", street.name)
        assertEquals(0.5, street.fraction, 0.02)

        val inWest = coverage.streetsIn(network, "Vest").single { it.name == "Parkveien" }
        assertTrue(
            inWest.fraction > 0.9,
            "the part of Parkveien inside Vest is finished, which is the number we are NOT showing",
        )
    }

    @Test
    fun `the street is measured whole from either side of a bydel border`() {
        val network = town()

        // A fresh coverage each time: standing on a stretch credits it, so walking to the
        // far side and reading again would differ because the street really had moved on.
        fun standingAt(east: Double): Progress {
            val coverage = Coverage(network)
            coverage.record(fix(east, 0.0))
            return coverage.streetProgress(network, coverage.whereabouts(network)!!)!!
        }

        val fromWest = standingAt(50.0)   // Vest
        val fromEast = standingAt(350.0)  // Øst

        // The denominator is the invariant worth pinning, because it is the one thing that
        // cannot be confounded by what the probing fix itself credited: both sides measure
        // against all 400 m of Parkveien, not against the 200 m in the bydel underfoot.
        val wholeStreet = network.lengthOf(network.segmentsByStreet.getValue("Parkveien"))
        assertEquals("Parkveien", fromWest.name)
        assertEquals("Parkveien", fromEast.name)
        assertEquals(wholeStreet, fromWest.totalM, 1e-6)
        assertEquals(wholeStreet, fromEast.totalM, 1e-6)
    }

    @Test
    fun `the bydel percentage counts only the roads inside it`() {
        val network = town()
        val coverage = Coverage(network)

        val vest = coverage.districtProgress(network, "Vest")!!
        assertEquals(
            network.lengthOf(network.segmentsByDistrict.getValue("Vest")),
            vest.totalM,
            1e-6,
        )
    }

    @Test
    fun `an unnamed road reports its own way, not every unnamed road in town`() {
        val network = town()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByWay.getValue(4L)) // one of the two unnamed ways
        coverage.record(fix(50.0, 200.0))

        val road = coverage.streetProgress(network, coverage.whereabouts(network)!!)!!
        assertEquals(UNNAMED_ROAD, road.name)
        assertEquals(1.0, road.fraction, 1e-9)

        // The city-wide bucket says something different, which is why that row is not
        // somewhere this one can link to.
        val bucket = coverage.byStreet(network).single { it.name == UNNAMED_ROADS }
        assertEquals(0.5, bucket.fraction, 0.02)
    }

    @Test
    fun `a network with no bydel outlines still knows the street`() {
        val network = townWithoutDistricts()
        val coverage = Coverage(network)
        coverage.record(fix(50.0, 0.0))

        val here = coverage.whereabouts(network)!!
        assertEquals("Parkveien", here.street)
        assertNull(here.district, "Overpass carries no bydel outlines")
        assertNull(coverage.districtProgress(network, here.district))
    }

    @Test
    fun `a road outside every bydel reports no bydel rather than the nearest one`() {
        val network = RoadNetwork.from(
            ways = listOf(RawWay(1L, "Langtvekkgata", listOf(at(900.0, 0.0), at(1000.0, 0.0)))),
            districts = listOf(District("Vest", listOf(box(west = -50.0, east = 200.0)))),
        )
        val coverage = Coverage(network)
        coverage.record(fix(950.0, 0.0))

        val here = coverage.whereabouts(network)!!
        assertEquals("Langtvekkgata", here.street)
        assertNull(here.district)
    }
}
