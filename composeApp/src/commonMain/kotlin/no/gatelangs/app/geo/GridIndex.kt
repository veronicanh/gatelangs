package no.gatelangs.app.geo

import kotlin.math.floor

/**
 * Uniform grid hash over segments, in the metres plane of a [MetricProjection].
 *
 * Two queries carry the whole app:
 *
 *  - [near], for "which segments might this GPS fix have walked" — called once a second.
 *  - [inBounds], for "which segments are on screen" — called every frame.
 *
 * A uniform grid rather than an R-tree because road segments are short and roughly
 * evenly spread over a city, which is precisely the case where a grid wins: both
 * queries become arithmetic plus a hash lookup, and the whole thing is small enough to
 * have nothing subtle in it.
 *
 * Segments are registered in *every* cell their bounding box touches, so a segment
 * longer than a cell is still found from anywhere along it. That means a query can see
 * the same segment from several cells, hence the de-duplication in [collect].
 */
class GridIndex private constructor(
    val projection: MetricProjection,
    private val cellSizeM: Double,
    private val cells: Map<Long, IntArray>,
    private val segmentCount: Int,
    private val extent: CellExtent?,
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
        val extent = extent ?: return IntArray(0)

        // Clamp the query to the extent that actually holds segments.
        //
        // This is load-bearing, not a micro-optimisation. A zoomed-out viewport asks
        // about a range far larger than the data: at the minimum zoom the visible box
        // spans hundreds of degrees of longitude, which is ~235 000 x ~378 000 cells —
        // 89 billion lookups per frame against roughly 1 200 occupied cells. Unclamped,
        // the nested loop below simply never returns and the app locks up.
        val fromX = maxOf(cellOf(minX), extent.minX)
        val toX = minOf(cellOf(maxX), extent.maxX)
        val fromY = maxOf(cellOf(minY), extent.minY)
        val toY = minOf(cellOf(maxY), extent.maxY)
        if (fromX > toX || fromY > toY) return IntArray(0)

        val found = LinkedHashSet<Int>()
        val cellsInRange = (toX - fromX + 1).toLong() * (toY - fromY + 1).toLong()

        if (cellsInRange > cells.size) {
            // The range is mostly empty even after clamping, which happens whenever the
            // viewport covers most of a sparse network. Walking the occupied cells is
            // then strictly cheaper than probing the range.
            for ((key, bucket) in cells) {
                val cx = (key shr 32).toInt()
                val cy = key.toInt()
                if (cx in fromX..toX && cy in fromY..toY) {
                    for (index in bucket) found.add(index)
                }
            }
        } else {
            for (cx in fromX..toX) {
                for (cy in fromY..toY) {
                    val bucket = cells[key(cx, cy)] ?: continue
                    for (index in bucket) found.add(index)
                }
            }
        }
        return found.toIntArray()
    }

    /**
     * Coordinates far outside the data would overflow an Int, so the result is clamped.
     * Callers intersect it with [extent] anyway, which makes any saturated value harmless.
     */
    private fun cellOf(metres: Double): Int {
        val cell = floor(metres / cellSizeM)
        return when {
            cell.isNaN() -> 0
            cell <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
            cell >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            else -> cell.toInt()
        }
    }

    /** Number of segments indexed. */
    fun size(): Int = segmentCount

    /** Occupied cell count — diagnostics only, to sanity-check the cell size. */
    fun occupiedCells(): Int = cells.size

    private data class CellExtent(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

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

            var minCellX = Int.MAX_VALUE
            var maxCellX = Int.MIN_VALUE
            var minCellY = Int.MAX_VALUE
            var maxCellY = Int.MIN_VALUE

            segments.forEachIndexed { index, segment ->
                val a = projection.project(segment.a)
                val b = projection.project(segment.b)
                val fromX = floor(minOf(a.x, b.x) / cellSizeM).toInt()
                val toX = floor(maxOf(a.x, b.x) / cellSizeM).toInt()
                val fromY = floor(minOf(a.y, b.y) / cellSizeM).toInt()
                val toY = floor(maxOf(a.y, b.y) / cellSizeM).toInt()

                if (fromX < minCellX) minCellX = fromX
                if (toX > maxCellX) maxCellX = toX
                if (fromY < minCellY) minCellY = fromY
                if (toY > maxCellY) maxCellY = toY

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
                extent = if (buckets.isEmpty()) {
                    null
                } else {
                    CellExtent(minCellX, maxCellX, minCellY, maxCellY)
                },
            )
        }

        private fun key(cx: Int, cy: Int): Long =
            (cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFFL)
    }
}
