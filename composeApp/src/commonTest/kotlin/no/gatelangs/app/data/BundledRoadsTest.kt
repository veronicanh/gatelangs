package no.gatelangs.app.data

import kotlinx.coroutines.test.runTest
import no.gatelangs.app.map.createHttpClient
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.Fix
import kotlin.test.Test
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
    fun `the snapshot carries the places to group progress by`() = runTest {
        // The progress screen breaks the city into strøk, and they travel in the same
        // file as the roads. A snapshot without them silently degrades to a flat list of
        // every street in Oslo.
        val network = repository().loadBundled()
        assertTrue(
            network.neighbourhoods.size >= 10,
            "only ${network.neighbourhoods.size} neighbourhoods in the snapshot",
        )
        assertTrue(
            network.neighbourhoods.all { it.centre in network.bounds },
            "a neighbourhood sits outside the roads it is meant to group",
        )
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
