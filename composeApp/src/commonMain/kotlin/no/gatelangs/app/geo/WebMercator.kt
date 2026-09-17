package no.gatelangs.app.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Edge length of a raster tile, in pixels. Every standard OSM-style tile server uses 256. */
const val TILE_SIZE = 256.0

/**
 * Web Mercator (EPSG:3857) in *world pixel* coordinates: the whole world is a square
 * of `TILE_SIZE * 2^zoom` pixels, origin top-left at (−180°, +85.05°).
 *
 * This is the projection tiles are defined in, so it is what rendering uses. It is
 * deliberately *not* what distance measurement uses — Mercator distorts scale badly
 * with latitude, so metres come from [MetricProjection] instead.
 *
 * [zoom] is a Double rather than an Int so the camera can sit between integer zoom
 * levels smoothly; tile *fetching* rounds it down separately.
 */
object WebMercator {

    /** Width (and height) of the world in pixels at [zoom]. */
    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    fun toWorldX(lon: Double, zoom: Double): Double =
        (lon + 180.0) / 360.0 * worldSize(zoom)

    fun toWorldY(lat: Double, zoom: Double): Double {
        val clamped = lat.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val sinLat = sin(clamped.toRadians())
        // 0.5 − artanh(sin φ) / 2π, written out to avoid needing artanh.
        val fraction = 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
        return fraction * worldSize(zoom)
    }

    fun lonAtWorldX(x: Double, zoom: Double): Double =
        x / worldSize(zoom) * 360.0 - 180.0

    fun latAtWorldY(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * (y / worldSize(zoom))
        return atan(sinh(n)).toDegrees()
    }

    /** Tile column containing [lon] at integer [zoom]. */
    fun tileX(lon: Double, zoom: Int): Int =
        floor(toWorldX(lon, zoom.toDouble()) / TILE_SIZE).toInt()

    /** Tile row containing [lat] at integer [zoom]. */
    fun tileY(lat: Double, zoom: Int): Int =
        floor(toWorldY(lat, zoom.toDouble()) / TILE_SIZE).toInt()

    /** Number of tiles along one axis at [zoom]. */
    fun tileCount(zoom: Int): Int = 1 shl zoom
}
