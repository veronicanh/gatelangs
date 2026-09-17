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

        assertTrue(network.segments.size > 5_000, "only ${network.segments.size} segments")
        assertTrue(
            network.totalLengthM in 150_000.0..250_000.0,
            "implausible total length: ${network.totalLengthM / 1000} km",
        )
        assertTrue(network.segmentsByWay.size > 1_000, "only ${network.segmentsByWay.size} ways")
        assertTrue(
            network.streetNames.values.count { !it.isNullOrBlank() } > 300,
            "suspiciously few named streets",
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
