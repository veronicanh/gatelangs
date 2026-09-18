package no.gatelangs.app.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import no.gatelangs.app.geo.BoundingBox
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MAX_MERCATOR_LATITUDE
import no.gatelangs.app.geo.TILE_SIZE
import no.gatelangs.app.geo.WebMercator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The map camera: where it looks and how far in.
 *
 * All rendering happens in *world pixels at the current fractional zoom*, which is what
 * makes smooth zooming simple — screen position is just world position minus the
 * camera's world position. Tiles, which only exist at integer zooms, are scaled into
 * that space when drawn (see [tileScale]).
 */
@Stable
class MapState(
    center: LatLon,
    zoom: Double,
    val minZoom: Double = MIN_ZOOM,
    val maxZoom: Double = MAX_ZOOM,
) {
    var center: LatLon by mutableStateOf(center)
        private set

    var zoom: Double by mutableStateOf(zoom.coerceIn(minZoom, maxZoom))
        private set

    /** Size of the drawing surface. Set by the map composable on layout. */
    var viewportSize: Size by mutableStateOf(Size.Zero)

    /** The integer zoom whose tiles are drawn — the nearest one, so they stay sharp. */
    val tileZoom: Int get() = zoom.roundToInt().coerceIn(0, MAX_ZOOM.toInt())

    /** How much a tile is scaled when drawn: in `[~0.71, ~1.41]` because [tileZoom] rounds. */
    val tileScale: Double get() = 2.0.pow(zoom - tileZoom)

    private val centerWorldX: Double get() = WebMercator.toWorldX(center.lon, zoom)
    private val centerWorldY: Double get() = WebMercator.toWorldY(center.lat, zoom)

    /** World pixel of the viewport's top-left corner. */
    private val originX: Double get() = centerWorldX - viewportSize.width / 2.0
    private val originY: Double get() = centerWorldY - viewportSize.height / 2.0

    fun screenOf(point: LatLon): Offset = Offset(
        x = (WebMercator.toWorldX(point.lon, zoom) - originX).toFloat(),
        y = (WebMercator.toWorldY(point.lat, zoom) - originY).toFloat(),
    )

    fun latLonOf(screen: Offset): LatLon = LatLon(
        lat = WebMercator.latAtWorldY(originY + screen.y, zoom),
        lon = WebMercator.lonAtWorldX(originX + screen.x, zoom),
    )

    /** Geographic bounds currently on screen. Empty viewport gives a degenerate box. */
    fun visibleBounds(): BoundingBox {
        if (viewportSize == Size.Zero) return BoundingBox(center.lat, center.lon, center.lat, center.lon)
        val topLeft = latLonOf(Offset.Zero)
        val bottomRight = latLonOf(Offset(viewportSize.width, viewportSize.height))
        return BoundingBox(
            south = bottomRight.lat,
            west = topLeft.lon,
            north = topLeft.lat,
            east = bottomRight.lon,
        )
    }

    /** Tiles needed to cover the viewport at [tileZoom]. */
    fun visibleTiles(): List<TileKey> {
        if (viewportSize == Size.Zero) return emptyList()
        val z = tileZoom
        val scale = tileScale
        val tileSpan = TILE_SIZE * scale
        val count = 1 shl z

        val firstX = floor(originX / tileSpan).toInt()
        val lastX = ceil((originX + viewportSize.width) / tileSpan).toInt() - 1
        val firstY = floor(originY / tileSpan).toInt()
        val lastY = ceil((originY + viewportSize.height) / tileSpan).toInt() - 1

        val keys = ArrayList<TileKey>()
        for (x in firstX..lastX) {
            for (y in firstY..lastY) {
                // No vertical wrap: above the north pole or below the south is nothing.
                if (y < 0 || y >= count) continue
                val wrappedX = ((x % count) + count) % count
                keys.add(TileKey(z, wrappedX, y))
            }
        }
        return keys
    }

    /**
     * Where tile [key] lands on screen, given the current camera.
     *
     * Derives the span from the key's own zoom rather than from [tileScale], so a tile
     * from a coarser level places correctly too — which is what lets a parent stand in
     * for a tile that has not loaded yet. At `key.zoom == tileZoom` this is exactly
     * [tileScale], so the ordinary case is unchanged.
     */
    fun screenRectOf(key: TileKey): TileRect {
        val tileSpan = TILE_SIZE * 2.0.pow(zoom - key.zoom)
        return TileRect(
            left = (key.x * tileSpan - originX).toFloat(),
            top = (key.y * tileSpan - originY).toFloat(),
            size = tileSpan.toFloat(),
        )
    }

    /** Moves the camera by a screen-space drag. */
    fun panBy(delta: Offset) {
        moveTo(
            LatLon(
                lat = WebMercator.latAtWorldY(centerWorldY - delta.y, zoom),
                lon = WebMercator.lonAtWorldX(centerWorldX - delta.x, zoom),
            )
        )
    }

    /**
     * Zooms by [factor] keeping the geography under [focus] pinned there — the behaviour
     * every map has, and the reason zooming cannot just change [zoom].
     */
    fun zoomBy(factor: Double, focus: Offset) {
        if (factor <= 0.0 || factor == 1.0) return
        val anchor = latLonOf(focus)
        val newZoom = (zoom + kotlin.math.log2(factor)).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return
        zoom = newZoom

        // Re-centre so `anchor` sits back under `focus` at the new zoom.
        val anchorScreen = screenOf(anchor)
        panBy(focus - anchorScreen)
    }

    fun moveTo(target: LatLon) {
        center = LatLon(
            lat = target.lat.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE),
            lon = target.lon,
        )
    }

    fun zoomTo(value: Double) {
        zoom = value.coerceIn(minZoom, maxZoom)
    }

    companion object {
        const val MIN_ZOOM = 3.0

        /** OSM raster tiles stop at 19. */
        const val MAX_ZOOM = 19.0
    }
}

/** A tile's placement on screen, in pixels. Square, so one [size]. */
data class TileRect(val left: Float, val top: Float, val size: Float)
