package no.gatelangs.app.data

import kotlinx.coroutines.test.runTest
import no.gatelangs.app.map.createHttpClient
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.Fix
import no.gatelangs.app.resources.Res
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the offline snapshot end to end.
 *
 * This is the path the demo falls back to when Overpass is slow or rate-limited, which
 * is routine rather than exceptional — so it is worth a test that actually parses the
 * committed file rather than a fixture.
 */
class BundledRoadsTest {

    private fun repository() = RoadRepository(createHttpClient())

    @Test
    fun `loads the committed Oslo snapshot`() = runTest {
        val network = repository().loadBundled()

        assertTrue(network.segments.size > 1_500, "only ${network.segments.size} segments")
        assertTrue(network.totalLengthM > 40_000.0, "only ${network.totalLengthM / 1000} km of road")
        assertTrue(network.segmentsByWay.size > 500, "only ${network.segmentsByWay.size} ways")
    }

    @Test
    fun `nearly every street in the snapshot has a name`() = runTest {
        // Streets-only is what makes this true. When footways were in the set, 29% of the
        // length carried a name and a per-street percentage meant nothing; if this drops
        // back it means pavement geometry has crept into the snapshot again.
        val network = repository().loadBundled()
        val named = network.streetNames.values.count { !it.isNullOrBlank() }
        val share = named.toDouble() / network.streetNames.size
        assertTrue(share > 0.85, "only ${(share * 100).toInt()}% of ways are named")
    }

    @Test
    fun `the bydel outlines load alongside the roads`() = runTest {
        // They live in their own file now — districts-oslo.json — so this also checks the
        // two are still wired together. The progress screen breaks the city into bydeler;
        // without them it degrades to a flat list of every street in Oslo.
        val network = repository().loadBundled()
        assertTrue(
            network.districts.size >= 15,
            "only ${network.districts.size} bydeler loaded",
        )
        assertTrue(
            network.districts.all { d -> d.rings.isNotEmpty() && d.rings.all { it.size >= 4 } },
            "a bydel arrived without a usable outline",
        )
    }

    @Test
    fun `the road snapshot no longer carries outlines of its own`() = runTest {
        // The point of the split: regenerating roads from Overpass must not be able to
        // take the bydeler with it, because no live fetch can put them back.
        val roads = Res.readBytes(RoadRepository.BUNDLED_PATH).decodeToString()
        assertFalse(
            "\"districts\"" in roads,
            "districts are back in ${RoadRepository.BUNDLED_PATH}",
        )
    }

    @Test
    fun `nearly every metre of road falls inside a bydel`() = runTest {
        // Oslo's bydeler tile the whole municipality, so anything left over is a rounding
        // artefact of simplifying the outlines rather than a real gap. Measured at 0.35%.
        val network = repository().loadBundled()
        val assigned = network.segmentsByDistrict.values.sumOf { network.lengthOf(it) }
        val share = assigned / network.totalLengthM
        assertTrue(share > 0.98, "only ${(share * 100).toInt()}% of road landed in a bydel")
    }

    @Test
    fun `every segment lies inside the snapshot's own bounds`() = runTest {
        val network = repository().loadBundled()
        for (segment in network.segments) {
            assertTrue(segment.a in network.bounds && segment.b in network.bounds)
        }
    }

    @Test
    fun `no segment exceeds the length cap`() = runTest {
        val network = repository().loadBundled()
        val longest = network.segmentLengths.max()
        assertTrue(longest <= 25.5, "longest segment was $longest m")
    }

    @Test
    fun `walking the snapshot moves coverage off zero`() = runTest {
        // A smoke test of the real pipeline: real geometry, real index, real matcher.
        val network = repository().loadBundled()
        val coverage = Coverage(network)

        val start = network.segments.first().a
        coverage.record(Fix(start.lat, start.lon, accuracyM = 5.0, timestampMs = 0))

        assertTrue(coverage.walkedSegmentCount() > 0, "a fix on a real street marked nothing")
        assertTrue(coverage.fraction() > 0.0)
    }
}
