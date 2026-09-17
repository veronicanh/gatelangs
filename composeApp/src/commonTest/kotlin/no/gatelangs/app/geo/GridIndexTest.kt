package no.gatelangs.app.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ORIGIN = LatLon(59.9139, 10.7522)

/** Builds a segment [metresEast]/[metresNorth] from the origin, running [lengthM] east. */
private fun eastwardSegment(
    projection: MetricProjection,
    metresEast: Double,
    metresNorth: Double,
    lengthM: Double,
    wayId: Long = 1L,
): Segment = Segment(
    wayId = wayId,
    a = projection.unproject(Vec2(metresEast, metresNorth)),
    b = projection.unproject(Vec2(metresEast + lengthM, metresNorth)),
)

class GridIndexTest {

    private val projection = MetricProjection(ORIGIN)

    @Test
    fun `finds a segment the query point sits on`() {
        val segment = eastwardSegment(projection, 0.0, 0.0, 100.0)
        val index = GridIndex.build(listOf(segment), ORIGIN)

        val onIt = projection.unproject(Vec2(50.0, 0.0))
        assertTrue(0 in index.near(onIt, radiusM = 10.0).toList())
    }

    @Test
    fun `finds a segment far longer than one cell from anywhere along it`() {
        // The reason segments register in every cell their bbox touches.
        val segment = eastwardSegment(projection, 0.0, 0.0, lengthM = 2_000.0)
        val index = GridIndex.build(listOf(segment), ORIGIN, cellSizeM = 50.0)

        for (along in listOf(10.0, 500.0, 1_234.0, 1_990.0)) {
            val point = projection.unproject(Vec2(along, 3.0))
            assertTrue(
                0 in index.near(point, radiusM = 15.0).toList(),
                "missed the segment $along m along it",
            )
        }
    }

    @Test
    fun `returns each segment at most once`() {
        val segment = eastwardSegment(projection, 0.0, 0.0, lengthM = 500.0)
        val index = GridIndex.build(listOf(segment), ORIGIN, cellSizeM = 50.0)

        // A radius spanning many cells, all of which hold this same segment.
        val hits = index.near(projection.unproject(Vec2(250.0, 0.0)), radiusM = 200.0)
        assertEquals(hits.toList().distinct().size, hits.size, "duplicates in $hits")
    }

    @Test
    fun `excludes segments comfortably outside the radius`() {
        val near = eastwardSegment(projection, 0.0, 0.0, 100.0, wayId = 1L)
        val far = eastwardSegment(projection, 0.0, 5_000.0, 100.0, wayId = 2L)
        val index = GridIndex.build(listOf(near, far), ORIGIN)

        val hits = index.near(projection.unproject(Vec2(50.0, 0.0)), radiusM = 20.0).toList()
        assertTrue(0 in hits)
        assertFalse(1 in hits, "a segment 5 km away must not be a candidate")
    }

    @Test
    fun `is a filter, not an answer`() {
        // The grid returns bbox candidates; exact distance is the caller's job. A point
        // diagonally outside a cell can still be handed back, and that is correct.
        val segment = eastwardSegment(projection, 0.0, 0.0, 100.0)
        val index = GridIndex.build(listOf(segment), ORIGIN)

        val point = projection.unproject(Vec2(50.0, 40.0))
        val candidates = index.near(point, radiusM = 45.0).toList()
        assertTrue(0 in candidates)

        val a = projection.project(segment.a)
        val b = projection.project(segment.b)
        assertEquals(40.0, distanceToSegment(projection.project(point), a, b), 1e-6)
    }

    @Test
    fun `returns segments within a viewport`() {
        val inside = eastwardSegment(projection, 0.0, 0.0, 100.0, wayId = 1L)
        val outside = eastwardSegment(projection, 0.0, 10_000.0, 100.0, wayId = 2L)
        val index = GridIndex.build(listOf(inside, outside), ORIGIN)

        val pad = projection.degreesLatFor(500.0)
        val viewport = BoundingBox(
            south = ORIGIN.lat - pad,
            west = ORIGIN.lon - pad,
            north = ORIGIN.lat + pad,
            east = ORIGIN.lon + pad,
        )
        val hits = index.inBounds(viewport).toList()
        assertTrue(0 in hits)
        assertFalse(1 in hits)
    }

    @Test
    fun `handles an empty network`() {
        val index = GridIndex.build(emptyList(), ORIGIN)
        assertEquals(0, index.size())
        assertEquals(0, index.near(ORIGIN, radiusM = 100.0).size)
    }

    @Test
    fun `indexes every segment of a grid of streets`() {
        val segments = buildList {
            for (row in 0 until 20) {
                for (col in 0 until 20) {
                    add(eastwardSegment(projection, col * 25.0, row * 25.0, 25.0, wayId = row.toLong()))
                }
            }
        }
        val index = GridIndex.build(segments, ORIGIN)
        assertEquals(400, index.size())
        assertTrue(index.occupiedCells() > 0)

        // Every segment must be findable from its own midpoint.
        segments.forEachIndexed { id, segment ->
            val mid = projection.unproject(
                Vec2(
                    (projection.project(segment.a).x + projection.project(segment.b).x) / 2,
                    (projection.project(segment.a).y + projection.project(segment.b).y) / 2,
                )
            )
            assertTrue(id in index.near(mid, radiusM = 5.0).toList(), "segment $id not found")
        }
    }
}

class SegmentationTest {

    private val projection = MetricProjection(ORIGIN)

    @Test
    fun `leaves a short span as a single segment`() {
        val points = listOf(
            projection.unproject(Vec2(0.0, 0.0)),
            projection.unproject(Vec2(10.0, 0.0)),
        )
        assertEquals(1, segmentize(1L, points, projection).size)
    }

    @Test
    fun `splits a long straight so coverage stays honest`() {
        // Without this, stepping onto one end would credit the whole block.
        val points = listOf(
            projection.unproject(Vec2(0.0, 0.0)),
            projection.unproject(Vec2(400.0, 0.0)),
        )
        val segments = segmentize(1L, points, projection, maxLengthM = 25.0)
        assertEquals(16, segments.size)
        for (s in segments) {
            val len = length(projection.project(s.a), projection.project(s.b))
            assertTrue(len <= 25.0 + 1e-6, "segment of $len m exceeded the cap")
        }
    }

    @Test
    fun `lands exactly on the original nodes`() {
        val start = projection.unproject(Vec2(0.0, 0.0))
        val end = projection.unproject(Vec2(300.0, 0.0))
        val segments = segmentize(1L, listOf(start, end), projection)

        assertEquals(start, segments.first().a)
        assertEquals(end, segments.last().b, "the last piece must land on the node, not an interpolation")
    }

    @Test
    fun `keeps the polyline connected across pieces`() {
        val points = listOf(
            projection.unproject(Vec2(0.0, 0.0)),
            projection.unproject(Vec2(120.0, 0.0)),
            projection.unproject(Vec2(120.0, 90.0)),
        )
        val segments = segmentize(1L, points, projection)
        for (i in 0 until segments.lastIndex) {
            assertEquals(segments[i].b, segments[i + 1].a, "gap after piece $i")
        }
    }

    @Test
    fun `preserves total length`() {
        val points = listOf(
            projection.unproject(Vec2(0.0, 0.0)),
            projection.unproject(Vec2(237.0, 0.0)),
            projection.unproject(Vec2(237.0, 141.0)),
        )
        val total = segmentize(1L, points, projection).sumOf {
            length(projection.project(it.a), projection.project(it.b))
        }
        assertEquals(237.0 + 141.0, total, 0.5)
    }

    @Test
    fun `drops degenerate geometry`() {
        assertTrue(segmentize(1L, emptyList(), projection).isEmpty())
        assertTrue(segmentize(1L, listOf(ORIGIN), projection).isEmpty())
        assertTrue(segmentize(1L, listOf(ORIGIN, ORIGIN), projection).isEmpty())
    }

    @Test
    fun `carries the way id onto every piece`() {
        val points = listOf(
            projection.unproject(Vec2(0.0, 0.0)),
            projection.unproject(Vec2(200.0, 0.0)),
        )
        assertTrue(segmentize(77L, points, projection).all { it.wayId == 77L })
    }
}
