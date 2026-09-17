package no.gatelangs.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreetGroupingTest {

    @Test
    fun `a street split across ways is one street`() {
        val network = TestTown.network()
        assertEquals(
            400.0,
            network.lengthOf(network.segmentsByStreet.getValue("Parkveien")),
            1.0,
            "both Parkveien ways should count as the same street",
        )
        assertTrue("Parkveien" in network.segmentsByStreet)
        assertEquals(2, network.segmentsByWay.keys.count { network.streetNames[it] == "Parkveien" })
    }

    @Test
    fun `walking one of a street's ways gets it half way`() {
        val network = TestTown.network()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByWay.getValue(1L))

        assertEquals(0.5, coverage.fractionOfStreet("Parkveien"), 0.02)
        assertEquals(1.0, coverage.fractionOfWay(1L), 1e-9)
    }

    @Test
    fun `a street percentage is by length, not by segment count`() {
        // The two metrics agree only when segments are equal length, which segmentize
        // does not promise: it caps at 25 m but leaves shorter spans alone. The headline
        // figure is by length, so this one has to be too or the screen contradicts itself.
        val network = TestTown.network()
        val coverage = Coverage(network)
        val ids = network.segmentsByStreet.getValue("Parkveien")
        coverage.restore(ids.take(3).toIntArray())

        val expected = network.lengthOf(ids.take(3).toIntArray()) / network.lengthOf(ids)
        assertEquals(expected, coverage.fractionOfStreet("Parkveien"), 1e-9)
    }

    @Test
    fun `an unwalked street reports nothing, an unknown one too`() {
        val coverage = Coverage(TestTown.network())
        assertEquals(0.0, coverage.fractionOfStreet("Sidegata"), 1e-9)
        assertEquals(0.0, coverage.fractionOfStreet("Nowhere gate"), 1e-9)
    }
}

class DistrictTest {

    @Test
    fun `every segment joins exactly one district`() {
        val network = TestTown.network()
        assertTrue(network.districtOfSegment.all { it >= 0 }, "a segment was left unassigned")
        assertEquals(
            network.segments.size,
            network.segmentsByDistrict.values.sumOf { it.size },
            "segments must be counted once, not shared between districts",
        )
    }

    @Test
    fun `district lengths add up to the whole network`() {
        // The property that lets the breakdown be trusted against the headline number.
        val network = TestTown.network()
        assertEquals(
            network.totalLengthM,
            network.segmentsByDistrict.values.sumOf { network.lengthOf(it) },
            1e-6,
        )
    }

    @Test
    fun `segments join the district they lie inside`() {
        val network = TestTown.network()
        val west = network.segmentsByDistrict.getValue("Vest")
        val east = network.segmentsByDistrict.getValue("Øst")

        // Parkveien runs east from the origin, so its far end belongs to Øst and its
        // near end to Vest.
        val parkveien = network.segmentsByStreet.getValue("Parkveien")
        assertTrue(parkveien.any { it in west.toSet() }, "the west end should be in Vest")
        assertTrue(parkveien.any { it in east.toSet() }, "the east end should be in Øst")

        // Sidegata sits entirely in the west half.
        assertTrue(network.segmentsByStreet.getValue("Sidegata").all { it in west.toSet() })
    }

    @Test
    fun `a street crossing a boundary reports only its local part in each`() {
        val network = TestTown.network()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByWay.getValue(1L)) // the western half only

        val inWest = coverage.streetsIn(network, "Vest").single { it.name == "Parkveien" }
        val inEast = coverage.streetsIn(network, "Øst").single { it.name == "Parkveien" }

        assertTrue(inWest.fraction > 0.9, "the walked half should read as done in Vest")
        assertEquals(0.0, inEast.fraction, 1e-9, "the unwalked half must not be credited in Øst")
        assertEquals(
            network.lengthOf(network.segmentsByStreet.getValue("Parkveien")),
            inWest.totalM + inEast.totalM,
            1e-6,
            "the two halves should account for the whole street",
        )
    }

    @Test
    fun `unnamed road gets a row of its own so the totals still add up`() {
        val network = TestTown.network()
        val coverage = Coverage(network)

        val streets = coverage.byStreet(network)
        assertTrue(streets.any { it.name == UNNAMED_ROADS }, "the unnamed way vanished")
        assertEquals(
            network.totalLengthM,
            streets.sumOf { it.totalM },
            1e-6,
            "every metre of the network should appear in exactly one row",
        )
    }

    @Test
    fun `the breakdown is ranked most finished first`() {
        val network = TestTown.network()
        val coverage = Coverage(network)
        coverage.restore(network.segmentsByWay.getValue(3L)) // all of Sidegata

        val streets = coverage.byStreet(network)
        assertEquals("Sidegata", streets.first().name)
        assertTrue(streets.zipWithNext().all { (a, b) -> a.fraction >= b.fraction })
    }

    @Test
    fun `progress reports what is left`() {
        val progress = Progress(name = "Parkveien", walkedM = 150.0, totalM = 400.0)
        assertEquals(0.375, progress.fraction, 1e-9)
        assertEquals(250.0, progress.remainingM, 1e-9)
    }

    @Test
    fun `a network with no districts degrades quietly`() {
        // The live Overpass path comes back without outlines by design.
        val network = RoadNetwork.from(
            listOf(RawWay(1L, "Enegata", listOf(TestTown.at(0.0, 0.0), TestTown.at(100.0, 0.0))))
        )
        assertTrue(network.segmentsByDistrict.isEmpty())
        assertTrue(Coverage(network).byDistrict(network).isEmpty())
        assertTrue(network.districtOfSegment.all { it < 0 })
    }

    @Test
    fun `road outside every district is left unassigned rather than forced into one`() {
        // Nearest-centre had no way to say "none of them". Outlines do, and a road filed
        // under a bydel it is nowhere near would be worse than a gap.
        val network = RoadNetwork.from(
            ways = listOf(
                RawWay(1L, "Langtvekkgata", listOf(TestTown.at(900.0, 0.0), TestTown.at(1000.0, 0.0))),
            ),
            districts = listOf(District("Vest", listOf(TestTown.box(west = -50.0, east = 200.0)))),
        )
        assertTrue(network.districtOfSegment.all { it < 0 })
        assertTrue(network.segmentsByDistrict.isEmpty())
    }
}
