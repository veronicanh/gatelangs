package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ORIGIN = LatLon(59.9139, 10.7522)
private val PROJECTION = MetricProjection(ORIGIN)

private fun at(east: Double, north: Double): LatLon = PROJECTION.unproject(Vec2(east, north))

/** A single street running 500 m east along y = 0. */
private fun oneStreet(): RoadNetwork = RoadNetwork.from(
    listOf(RawWay(id = 1L, name = "Testgata", points = listOf(at(0.0, 0.0), at(500.0, 0.0))))
)

/** Two parallel streets 18 m apart — the case plain radius matching gets wrong. */
private fun parallelStreets(): RoadNetwork = RoadNetwork.from(
    listOf(
        RawWay(1L, "Sorgata", listOf(at(0.0, 0.0), at(500.0, 0.0))),
        RawWay(2L, "Nordgata", listOf(at(0.0, 18.0), at(500.0, 18.0))),
    )
)

private fun fix(east: Double, north: Double, accuracy: Double = 5.0, t: Long = 0L) =
    Fix(at(east, north).lat, at(east, north).lon, accuracy, t)

class CoverageTest {

    @Test
    fun `starts at zero`() {
        val coverage = Coverage(oneStreet())
        assertEquals(0.0, coverage.fraction(), 1e-9)
        assertEquals(0, coverage.walkedSegmentCount())
    }

    @Test
    fun `marks the segment under a fix as walked`() {
        val network = oneStreet()
        val coverage = Coverage(network)

        val walked = coverage.record(fix(250.0, 0.0))
        assertTrue(walked.isNotEmpty(), "a fix on the street must mark something")
        assertTrue(coverage.fraction() > 0.0)
    }

    @Test
    fun `ignores a fix far from any road`() {
        val coverage = Coverage(oneStreet())
        assertEquals(0, coverage.record(fix(250.0, 400.0)).size)
        assertEquals(0.0, coverage.fraction(), 1e-9)
    }

    @Test
    fun `rejects a fix too imprecise to mean anything`() {
        // A 90 m error circle would otherwise light up every street in the block.
        val coverage = Coverage(oneStreet())
        assertEquals(0, coverage.record(fix(250.0, 0.0, accuracy = 90.0)).size)
        assertEquals(0.0, coverage.fraction(), 1e-9)
    }

    @Test
    fun `does not double count a segment walked twice`() {
        val coverage = Coverage(oneStreet())
        coverage.record(fix(250.0, 0.0, t = 0))
        val first = coverage.fraction()
        val again = coverage.record(fix(250.0, 0.0, t = 1_000))
        assertEquals(0, again.size, "already-walked segments must not be reported again")
        assertEquals(first, coverage.fraction(), 1e-9)
    }

    @Test
    fun `reaches full coverage after walking the whole street`() {
        val coverage = Coverage(oneStreet())
        var t = 0L
        var east = 0.0
        while (east <= 500.0) {
            coverage.record(fix(east, 0.0, t = t))
            east += 5.0
            t += 1_000
        }
        assertTrue(coverage.fraction() > 0.99, "expected near-total coverage, got ${coverage.fraction()}")
        assertTrue(coverage.fractionOfWay(1L) > 0.99)
    }

    @Test
    fun `a parallel street is not claimed`() {
        // 18 m apart, which is inside the 25 m search radius — so this is settled by
        // crediting only the nearest road, not by the radius and not by the bearing gate
        // (both streets run east, so the gate lets both through).
        val coverage = Coverage(parallelStreets())
        var t = 0L
        var east = 0.0
        while (east <= 500.0) {
            coverage.record(fix(east, 0.0, t = t))
            east += 5.0
            t += 1_000
        }

        assertTrue(coverage.fractionOfWay(1L) > 0.9, "the walked street should be covered")
        assertEquals(
            0.0,
            coverage.fractionOfWay(2L),
            1e-9,
            "a street 18 m away must not be claimed",
        )
    }

    @Test
    fun `a street 10 m away is not claimed either`() {
        // This used to be recorded as an accepted limitation: both streets sat inside the
        // match radius on the same bearing, so every fix credited both and walking one
        // street silently completed its neighbour. It is what "streets that are not
        // really walked are marked" meant. Crediting only the nearest settles it — 10 m
        // is ambiguous to a *radius*, but not to a comparison.
        val network = RoadNetwork.from(
            listOf(
                RawWay(1L, "Sorgata", listOf(at(0.0, 0.0), at(500.0, 0.0))),
                RawWay(2L, "Nordgata", listOf(at(0.0, 10.0), at(500.0, 10.0))),
            )
        )
        val coverage = Coverage(network)
        var t = 0L
        var east = 0.0
        while (east <= 500.0) {
            coverage.record(fix(east, 0.0, t = t))
            east += 5.0
            t += 1_000
        }

        assertTrue(coverage.fractionOfWay(1L) > 0.9, "the street walked should be covered")
        assertEquals(
            0.0,
            coverage.fractionOfWay(2L),
            1e-9,
            "the street 10 m away was not walked and must not be marked",
        )
    }

    @Test
    fun `re-walking a finished street keeps matching that street`() {
        // The trap in crediting only the nearest: if already-walked segments were skipped
        // when choosing, then on a street you have finished the nearest *unwalked* road
        // wins instead — which is the one next door. Walking a street twice has to stay
        // on it.
        val coverage = Coverage(parallelStreets())
        repeat(2) { lap ->
            var east = 0.0
            var t = lap * 1_000_000L
            while (east <= 500.0) {
                coverage.record(fix(east, 0.0, t = t))
                east += 5.0
                t += 1_000
            }
        }

        assertEquals(
            0.0,
            coverage.fractionOfWay(2L),
            1e-9,
            "the second lap leaked onto the parallel street",
        )
    }

    @Test
    fun `credits one road per fix`() {
        // Standing at a junction is still one road, not two.
        val network = RoadNetwork.from(
            listOf(
                RawWay(1L, "Langsgata", listOf(at(0.0, 0.0), at(500.0, 0.0))),
                RawWay(2L, "Sidegata", listOf(at(0.0, 3.0), at(500.0, 3.0))),
            )
        )
        val coverage = Coverage(network)
        assertEquals(1, coverage.record(fix(250.0, 0.0)).size, "a fix must credit exactly one road")
    }

    @Test
    fun `credits the road underfoot even when the heading is off it`() {
        // The regression this guards. A hard 45 deg bearing filter refused the road
        // directly below the marker whenever the direction of travel was a little off
        // it — which, walking with eight keyboard directions along streets that run at
        // whatever angle they run at, is most of them. Measured against the real Oslo
        // snapshot, 11% of fixes sitting squarely on a road matched nothing at all.
        val coverage = Coverage(oneStreet()) // runs east, so bearing 90
        val firstFix = coverage.record(fix(0.0, 40.0, t = 0))
        assertEquals(0, firstFix.size, "40 m out should be beyond the search radius")

        // Arrives 5 m from the street on a heading 60 deg off it: too far off for the old
        // filter, and plainly still on the street.
        val walked = coverage.record(fix(20.2, 5.0, t = 1_000))

        assertTrue(walked.isNotEmpty(), "the road under the marker was not credited")
    }

    @Test
    fun `the bearing gate rejects a crossing street`() {
        val network = RoadNetwork.from(
            listOf(
                RawWay(1L, "Langsgata", listOf(at(0.0, 0.0), at(500.0, 0.0))),
                RawWay(2L, "Tversgata", listOf(at(250.0, -200.0), at(250.0, 200.0))),
            )
        )
        val coverage = Coverage(network)

        // Establish an eastward heading, then pass the junction.
        coverage.record(fix(200.0, 0.0, t = 0))
        coverage.record(fix(250.0, 0.0, t = 10_000))

        assertTrue(coverage.fractionOfWay(1L) > 0.0, "the street being walked must be marked")
        assertEquals(
            0.0,
            coverage.fractionOfWay(2L),
            1e-9,
            "a street crossing at right angles must not be claimed by passing through it",
        )
    }

    @Test
    fun `matches without a heading when there is no previous fix`() {
        // The very first fix of a session has no direction; it must still count.
        val coverage = Coverage(oneStreet())
        assertTrue(coverage.record(fix(250.0, 0.0)).isNotEmpty())
    }

    @Test
    fun `treats a long gap between fixes as a fresh start`() {
        val coverage = Coverage(parallelStreets())
        coverage.record(fix(0.0, 0.0, t = 0))
        // Ten minutes later and somewhere else: no trustworthy heading.
        assertTrue(coverage.record(fix(400.0, 18.0, t = 600_000)).isNotEmpty())
    }

    @Test
    fun `restores walked state`() {
        val network = oneStreet()
        val original = Coverage(network)
        var t = 0L
        var east = 0.0
        while (east <= 200.0) {
            original.record(fix(east, 0.0, t = t))
            east += 5.0
            t += 1_000
        }

        val restored = Coverage(network)
        restored.restore(original.walkedIds())

        assertEquals(original.walkedSegmentCount(), restored.walkedSegmentCount())
        assertEquals(original.fraction(), restored.fraction(), 1e-9)
        assertEquals(original.walkedLengthMeters(), restored.walkedLengthMeters(), 1e-6)
    }

    @Test
    fun `restore ignores ids outside the network`() {
        val coverage = Coverage(oneStreet())
        coverage.restore(intArrayOf(-1, 999_999))
        assertEquals(0, coverage.walkedSegmentCount())
    }

    @Test
    fun `restore is idempotent`() {
        val network = oneStreet()
        val coverage = Coverage(network)
        coverage.record(fix(250.0, 0.0))
        val ids = coverage.walkedIds()

        val length = coverage.walkedLengthMeters()
        coverage.restore(ids)
        assertEquals(length, coverage.walkedLengthMeters(), 1e-9, "re-restoring must not add length twice")
    }
}

class RoadNetworkTest {

    @Test
    fun `flattens ways into capped segments`() {
        val network = oneStreet()
        assertEquals(20, network.segments.size, "500 m at a 25 m cap")
        assertTrue(network.segments.all { it.wayId == 1L })
    }

    @Test
    fun `measures total length`() {
        assertEquals(500.0, oneStreet().totalLengthM, 1.0)
    }

    @Test
    fun `groups segments by way`() {
        val network = parallelStreets()
        assertEquals(2, network.segmentsByWay.size)
        assertEquals(network.segments.size, network.segmentsByWay.values.sumOf { it.size })
    }

    @Test
    fun `keeps street names`() {
        assertEquals("Testgata", oneStreet().streetNames[1L])
    }

    @Test
    fun `bounds contain every point`() {
        val network = parallelStreets()
        for (segment in network.segments) {
            assertTrue(segment.a in network.bounds, "${segment.a} outside ${network.bounds}")
            assertTrue(segment.b in network.bounds, "${segment.b} outside ${network.bounds}")
        }
    }

    @Test
    fun `an unknown way has no coverage`() {
        assertFalse(Coverage(oneStreet()).fractionOfWay(4242L) > 0.0)
    }
}
