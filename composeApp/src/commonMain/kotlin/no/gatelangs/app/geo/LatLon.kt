package no.gatelangs.app.geo

import kotlin.math.PI

/** Degrees to radians. Not in the Kotlin common stdlib, so we carry our own. */
internal fun Double.toRadians(): Double = this * PI / 180.0

/** Radians to degrees. */
internal fun Double.toDegrees(): Double = this * 180.0 / PI

/** Mean Earth radius (IUGG). Good to ~0.1% anywhere, which is far better than GPS. */
const val EARTH_RADIUS_M = 6_371_008.8

/**
 * The latitude limit of Web Mercator. Beyond this the projection runs to infinity,
 * so tile schemes clamp here — the resulting world is square.
 */
const val MAX_MERCATOR_LATITUDE = 85.05112878

data class LatLon(val lat: Double, val lon: Double)

/**
 * One walkable stretch of road. Ways from OSM are flattened into these at load time,
 * each short enough (~25 m) that "walked" is a meaningful granularity.
 *
 * [wayId] is the OSM way this came from, kept so coverage can be grouped back up into
 * whole streets.
 */
data class Segment(val wayId: Long, val a: LatLon, val b: LatLon)

data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south <= north) { "south ($south) must not exceed north ($north)" }
        require(west <= east) { "west ($west) must not exceed east ($east)" }
    }

    val center: LatLon get() = LatLon((south + north) / 2.0, (west + east) / 2.0)

    operator fun contains(p: LatLon): Boolean =
        p.lat in south..north && p.lon in west..east

    companion object {
        /** Tight box around [points]. Throws on an empty list — an empty box is meaningless. */
        fun around(points: List<LatLon>): BoundingBox {
            require(points.isNotEmpty()) { "cannot build a bounding box from no points" }
            var south = Double.MAX_VALUE
            var west = Double.MAX_VALUE
            var north = -Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            for (p in points) {
                if (p.lat < south) south = p.lat
                if (p.lat > north) north = p.lat
                if (p.lon < west) west = p.lon
                if (p.lon > east) east = p.lon
            }
            return BoundingBox(south, west, north, east)
        }
    }
}
