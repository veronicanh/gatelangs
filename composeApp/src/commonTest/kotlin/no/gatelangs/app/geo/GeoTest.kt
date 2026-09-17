package no.gatelangs.app.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val OSLO = LatLon(59.9139, 10.7522)

class WebMercatorTest {

    @Test
    fun `round trips a coordinate through world pixels`() {
        for (zoom in listOf(0.0, 5.0, 12.0, 14.5, 19.0)) {
            val x = WebMercator.toWorldX(OSLO.lon, zoom)
            val y = WebMercator.toWorldY(OSLO.lat, zoom)
            assertEquals(OSLO.lon, WebMercator.lonAtWorldX(x, zoom), 1e-9, "lon at zoom $zoom")
            assertEquals(OSLO.lat, WebMercator.latAtWorldY(y, zoom), 1e-9, "lat at zoom $zoom")
        }
    }

    @Test
    fun `places the origin at the top left and the antimeridian at the far edge`() {
        val zoom = 3.0
        val size = WebMercator.worldSize(zoom)
        assertEquals(0.0, WebMercator.toWorldX(-180.0, zoom), 1e-9)
        assertEquals(size, WebMercator.toWorldX(180.0, zoom), 1e-6)
        assertEquals(size / 2, WebMercator.toWorldY(0.0, zoom), 1e-6, "equator sits halfway down")
        assertEquals(size / 2, WebMercator.toWorldX(0.0, zoom), 1e-6, "prime meridian sits halfway across")
    }

    @Test
    fun `clamps beyond the mercator latitude limit instead of running to infinity`() {
        val y = WebMercator.toWorldY(89.9, zoom = 4.0)
        assertTrue(y.isFinite(), "expected a finite pixel coordinate, got $y")
        assertEquals(WebMercator.toWorldY(MAX_MERCATOR_LATITUDE, 4.0), y, 1e-9)
    }

    @Test
    fun `agrees with the known tile for Oslo at zoom 14`() {
        // Cross-checked against the standard slippy-map formula, y = (1 - asinh(tan φ)/π)/2.
        assertEquals(8681, WebMercator.tileX(OSLO.lon, 14))
        assertEquals(4765, WebMercator.tileY(OSLO.lat, 14))
    }

    @Test
    fun `doubling the zoom doubles the world`() {
        assertEquals(TILE_SIZE, WebMercator.worldSize(0.0), 1e-9)
        assertEquals(TILE_SIZE * 2, WebMercator.worldSize(1.0), 1e-9)
        assertEquals(1 shl 10, WebMercator.tileCount(10))
    }
}

class MetricProjectionTest {

    private val projection = MetricProjection(OSLO)

    @Test
    fun `puts the origin at zero`() {
        val v = projection.project(OSLO)
        assertEquals(0.0, v.x, 1e-9)
        assertEquals(0.0, v.y, 1e-9)
    }

    @Test
    fun `round trips through the metres plane`() {
        val point = LatLon(59.9201, 10.7388)
        val back = projection.unproject(projection.project(point))
        assertEquals(point.lat, back.lat, 1e-12)
        assertEquals(point.lon, back.lon, 1e-12)
    }

    @Test
    fun `orients x east and y north`() {
        assertTrue(projection.x(OSLO.lon + 0.01) > 0, "east of origin must be positive x")
        assertTrue(projection.y(OSLO.lat + 0.01) > 0, "north of origin must be positive y")
    }

    @Test
    fun `stays far tighter than GPS noise across a city`() {
        // The whole design rests on this. Measured worst case is ~2.1 m at 7 km from the
        // origin (0.03%), against a 15 m match radius and GPS noise of 5-10 m — so the
        // projection is never what decides whether a street counts as walked. Keep the
        // origin near the data (RoadNetwork uses its bounding-box centre) and it holds.
        val points = listOf(
            LatLon(59.9139, 10.7822),  // ~1.7 km east
            LatLon(59.9539, 10.7522),  // ~4.5 km north
            LatLon(59.8839, 10.7122),  // ~4 km south-west
            LatLon(59.9639, 10.8322),  // ~7 km north-east
        )
        for (p in points) {
            val exact = haversineMeters(OSLO, p)
            val approximate = length(Vec2(0.0, 0.0), projection.project(p))
            val error = abs(exact - approximate)
            assertTrue(
                error < 3.0,
                "error of $error m at $p (haversine $exact, projected $approximate)",
            )
        }
    }

    @Test
    fun `converts metres back into degrees`() {
        val metres = 250.0
        val north = LatLon(OSLO.lat + projection.degreesLatFor(metres), OSLO.lon)
        assertEquals(metres, haversineMeters(OSLO, north), 0.5)

        val east = LatLon(OSLO.lat, OSLO.lon + projection.degreesLonFor(metres))
        assertEquals(metres, haversineMeters(OSLO, east), 0.5)
    }
}

class DistanceTest {

    private val a = Vec2(0.0, 0.0)
    private val b = Vec2(100.0, 0.0)

    @Test
    fun `measures perpendicular distance from the middle of a segment`() {
        // The case endpoint-distance gets wrong: mid-block is far from both ends.
        assertEquals(10.0, distanceToSegment(Vec2(50.0, 10.0), a, b), 1e-9)
    }

    @Test
    fun `clamps past the ends rather than extending the line`() {
        assertEquals(20.0, distanceToSegment(Vec2(120.0, 0.0), a, b), 1e-9)
        assertEquals(30.0, distanceToSegment(Vec2(-30.0, 0.0), a, b), 1e-9)
    }

    @Test
    fun `returns zero on the segment itself`() {
        assertEquals(0.0, distanceToSegment(Vec2(42.0, 0.0), a, b), 1e-9)
        assertEquals(0.0, distanceToSegment(a, a, b), 1e-9)
        assertEquals(0.0, distanceToSegment(b, a, b), 1e-9)
    }

    @Test
    fun `handles a degenerate segment whose ends coincide`() {
        val point = Vec2(3.0, 4.0)
        assertEquals(5.0, distanceToSegment(point, a, a), 1e-9)
    }

    @Test
    fun `is symmetric in the segment direction`() {
        val p = Vec2(30.0, 7.0)
        assertEquals(distanceToSegment(p, a, b), distanceToSegment(p, b, a), 1e-9)
    }

    @Test
    fun `measures haversine against a known distance`() {
        // One degree of latitude is very close to 111.2 km anywhere.
        val oneDegreeNorth = haversineMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0))
        assertEquals(111_195.0, oneDegreeNorth, 50.0)
        assertEquals(0.0, haversineMeters(OSLO, OSLO), 1e-9)
    }
}

class BearingTest {

    @Test
    fun `reports compass bearings from north clockwise`() {
        assertEquals(0.0, bearingDegrees(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1e-6)
        assertEquals(90.0, bearingDegrees(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-6)
        assertEquals(180.0, bearingDegrees(LatLon(1.0, 0.0), LatLon(0.0, 0.0)), 1e-6)
        assertEquals(270.0, bearingDegrees(LatLon(0.0, 1.0), LatLon(0.0, 0.0)), 1e-6)
    }

    @Test
    fun `treats opposite directions along one street as aligned`() {
        // Walking a street backwards still walks it, so 180 apart must read as zero.
        assertEquals(0.0, bearingDifference(90.0, 270.0), 1e-9)
        assertEquals(0.0, bearingDifference(0.0, 180.0), 1e-9)
        assertEquals(0.0, bearingDifference(45.0, 45.0), 1e-9)
    }

    @Test
    fun `reports a right angle as maximally misaligned`() {
        assertEquals(90.0, bearingDifference(0.0, 90.0), 1e-9)
        assertEquals(90.0, bearingDifference(350.0, 80.0), 1e-9)
    }

    @Test
    fun `wraps around the compass`() {
        assertEquals(20.0, bearingDifference(350.0, 10.0), 1e-9)
        assertEquals(20.0, bearingDifference(10.0, 350.0), 1e-9)
    }

    @Test
    fun `never exceeds ninety degrees`() {
        var angle = 0.0
        while (angle < 360.0) {
            val d = bearingDifference(angle, 137.0)
            assertTrue(d in 0.0..90.0, "bearingDifference($angle, 137) was $d")
            angle += 7.0
        }
    }
}
