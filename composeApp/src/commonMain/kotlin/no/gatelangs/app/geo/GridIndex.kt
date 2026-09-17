package no.gatelangs.app.geo

import kotlin.math.floor

/**
 * Uniform grid hash over segments, in the metres plane of a [MetricProjection].
 *
 * Two queries carry the whole app:
 *
 *  - [near], for "which segments might this GPS fix have walked" — called once a second,
 *    so it must not touch the full segment list.
 *  - [inBounds], for "which segments are on screen" — called every frame.
 *
 * A uniform grid rather than an R-tree because road segments are short and roughly
 * evenly spread over a city, which is precisely the case where a grid wins: both
 * queries become arithmetic plus a hash lookup, and the whole thing is ~60 lines with
 * nothing to get subtly wrong during a build/rebuild.
 *
 * Segments are registered in *every* cell their bounding box touches, so a segment
 * longer than a cell is still found from anywhere along it. That means a query can see
 * the same segment from several cells, hence the de-duplication in both queries.
 */
class GridIndex private constructor(
    val projection: MetricProjection,
    private val cellSizeM: Double,
    private val cells: Map<Long, IntArray>,
    private val segmentCount: Int,
) {

    /**
     * Indices of segments whose bounding box lies within [radiusM] of [point].
     *
     * These are *candidates*: the caller still measures each one with
     * [squaredDistanceToSegment]. The grid only narrows the field.
     */
    fun near(point: LatLon, radiusM: Double): IntArray {
        val p = projection.project(point)
        return collect(
            minX = p.x - radiusM,
            minY = p.y - radiusM,
            maxX = p.x + radiusM,
            maxY = p.y + radiusM,
        )
    }

    /** Indices of segments that may intersect [box] — for drawing the current viewport. */
    fun inBounds(box: BoundingBox): IntArray {
        val sw = projection.project(LatLon(box.south, box.west))
        val ne = projection.project(LatLon(box.north, box.east))
        return collect(minX = sw.x, minY = sw.y, maxX = ne.x, maxY = ne.y)
    }

    private fun collect(minX: Double, minY: Double, maxX: Double, maxY: Double): IntArray {
        val fromX = cellOf(minX)
        val toX = cellOf(maxX)
        val fromY = cellOf(minY)
        val toY = cellOf(maxY)

        val found = LinkedHashSet<Int>()
        for (cx in fromX..toX) {
            for (cy in fromY..toY) {
                val bucket = cells[key(cx, cy)] ?: continue
                for (index in bucket) found.add(index)
            }
        }
        return found.toIntArray()
    }

    private fun cellOf(metres: Double): Int = floor(metres / cellSizeM).toInt()

    /** Number of segments indexed. */
    fun size(): Int = segmentCount

    /** Occupied cell count — diagnostics only, to sanity-check the cell size. */
    fun occupiedCells(): Int = cells.size

    companion object {
        /**
         * 50 m: comfortably longer than the ~25 m segments and than any plausible GPS
         * match radius, so [near] almost always touches a 2x2 block of cells.
         */
        const val DEFAULT_CELL_SIZE_M = 50.0

        fun build(
            segments: List<Segment>,
            origin: LatLon,
            cellSizeM: Double = DEFAULT_CELL_SIZE_M,
        ): GridIndex {
            require(cellSizeM > 0.0) { "cell size must be positive, was $cellSizeM" }
            val projection = MetricProjection(origin)
            val buckets = HashMap<Long, MutableList<Int>>()

            segments.forEachIndexed { index, segment ->
                val a = projection.project(segment.a)
                val b = projection.project(segment.b)
                val fromX = floor(minOf(a.x, b.x) / cellSizeM).toInt()
                val toX = floor(maxOf(a.x, b.x) / cellSizeM).toInt()
                val fromY = floor(minOf(a.y, b.y) / cellSizeM).toInt()
                val toY = floor(maxOf(a.y, b.y) / cellSizeM).toInt()
                for (cx in fromX..toX) {
                    for (cy in fromY..toY) {
                        buckets.getOrPut(key(cx, cy)) { ArrayList() }.add(index)
                    }
                }
            }

            return GridIndex(
                projection = projection,
                cellSizeM = cellSizeM,
                cells = buckets.mapValues { (_, list) -> list.toIntArray() },
                segmentCount = segments.size,
            )
        }

        private fun key(cx: Int, cy: Int): Long =
            (cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFFL)
    }
}
