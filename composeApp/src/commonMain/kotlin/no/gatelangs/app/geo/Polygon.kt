package no.gatelangs.app.geo

/**
 * A closed ring in the metres plane, ready to be asked what is inside it.
 *
 * Projected once at construction rather than per test: assigning a city's worth of road
 * to its districts is tens of thousands of points against a handful of rings, so the
 * per-point work is the only thing that matters.
 */
class Ring(val points: List<Vec2>) {

    private val minX: Double
    private val maxX: Double
    private val minY: Double
    private val maxY: Double

    init {
        require(points.size >= 3) { "a ring needs at least three points, got ${points.size}" }
        var lowX = Double.MAX_VALUE
        var highX = -Double.MAX_VALUE
        var lowY = Double.MAX_VALUE
        var highY = -Double.MAX_VALUE
        for (p in points) {
            if (p.x < lowX) lowX = p.x
            if (p.x > highX) highX = p.x
            if (p.y < lowY) lowY = p.y
            if (p.y > highY) highY = p.y
        }
        minX = lowX; maxX = highX; minY = lowY; maxY = highY
    }

    /**
     * Whether [point] falls inside, by ray casting: count the ring edges directly to the
     * left of the point, and an odd count means inside.
     *
     * The bounding box is checked first, which is what makes this cheap in the common
     * case — a segment is outside all but one or two districts, and those are rejected
     * on four comparisons rather than a walk round the whole outline.
     *
     * Points exactly on the edge may land either way. That is fine here: the question is
     * which district a street belongs to, and a street lying precisely along a boundary
     * has no right answer to begin with.
     */
    operator fun contains(point: Vec2): Boolean {
        if (point.x < minX || point.x > maxX || point.y < minY || point.y > maxY) return false

        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
