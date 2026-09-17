package no.gatelangs.app.geo

import kotlin.math.cos

/** A point in a local metres-based plane. */
data class Vec2(val x: Double, val y: Double)

/**
 * Equirectangular projection to metres about a fixed [origin]: x runs east, y runs north.
 *
 * The point is to turn every distance question into plain Euclidean arithmetic. Over a
 * city-sized area (tens of km) the error against a proper geodesic is well under a
 * metre — an order of magnitude below GPS noise — so running haversine per segment per
 * fix would buy nothing and cost a great deal, since matching one fix touches dozens of
 * segments and fixes arrive every second.
 *
 * Build one per road network (origin at the network's centre) and reuse it.
 */
class MetricProjection(val origin: LatLon) {

    private val metresPerDegreeLat = EARTH_RADIUS_M * (1.0).toRadians()
    private val metresPerDegreeLon = metresPerDegreeLat * cos(origin.lat.toRadians())

    fun x(lon: Double): Double = (lon - origin.lon) * metresPerDegreeLon

    fun y(lat: Double): Double = (lat - origin.lat) * metresPerDegreeLat

    fun project(p: LatLon): Vec2 = Vec2(x(p.lon), y(p.lat))

    fun unproject(v: Vec2): LatLon = LatLon(
        lat = origin.lat + v.y / metresPerDegreeLat,
        lon = origin.lon + v.x / metresPerDegreeLon,
    )

    /** How many degrees of latitude span [metres]. Useful for padding a bounding box. */
    fun degreesLatFor(metres: Double): Double = metres / metresPerDegreeLat

    /** How many degrees of longitude span [metres], at this projection's latitude. */
    fun degreesLonFor(metres: Double): Double = metres / metresPerDegreeLon
}
