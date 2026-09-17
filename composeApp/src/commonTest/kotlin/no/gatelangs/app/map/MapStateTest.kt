package no.gatelangs.app.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import no.gatelangs.app.geo.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val OSLO = LatLon(59.9225, 10.7600)

private fun state(zoom: Double = 15.0, width: Float = 800f, height: Float = 600f) =
    MapState(OSLO, zoom).apply { viewportSize = Size(width, height) }

class MapStateTest {

    @Test
    fun `puts the centre in the middle of the viewport`() {
        val map = state()
        val screen = map.screenOf(map.center)
        assertEquals(400f, screen.x, 0.01f)
        assertEquals(300f, screen.y, 0.01f)
    }

    @Test
    fun `round trips screen to geography and back`() {
        val map = state()
        for (point in listOf(Offset(0f, 0f), Offset(800f, 600f), Offset(137f, 412f))) {
            val back = map.screenOf(map.latLonOf(point))
            assertEquals(point.x, back.x, 0.01f)
            assertEquals(point.y, back.y, 0.01f)
        }
    }

    @Test
    fun `panning moves the map with the drag`() {
        val map = state()
        val before = map.latLonOf(Offset.Zero)
        map.panBy(Offset(100f, 0f))
        // Dragging right reveals what was to the west, so the left edge moves west.
        assertTrue(map.latLonOf(Offset.Zero).lon < before.lon)
    }

    @Test
    fun `panning by a delta and back returns to the start`() {
        val map = state()
        val start = map.center
        map.panBy(Offset(120f, -80f))
        map.panBy(Offset(-120f, 80f))
        assertEquals(start.lat, map.center.lat, 1e-9)
        assertEquals(start.lon, map.center.lon, 1e-9)
    }

    @Test
    fun `zooming keeps the geography under the cursor pinned there`() {
        // The behaviour every map has, and the whole reason zoomBy is not just zoom++.
        val map = state()
        val focus = Offset(650f, 180f)
        val anchor = map.latLonOf(focus)

        map.zoomBy(factor = 2.0, focus = focus)

        val after = map.screenOf(anchor)
        assertTrue(abs(after.x - focus.x) < 0.5f, "x drifted to ${after.x} from ${focus.x}")
        assertTrue(abs(after.y - focus.y) < 0.5f, "y drifted to ${after.y} from ${focus.y}")
    }

    @Test
    fun `zooming at the centre leaves the centre alone`() {
        val map = state()
        val before = map.center
        map.zoomBy(2.0, Offset(400f, 300f))
        assertEquals(before.lat, map.center.lat, 1e-6)
        assertEquals(before.lon, map.center.lon, 1e-6)
        assertEquals(16.0, map.zoom, 1e-9)
    }

    @Test
    fun `clamps zoom to the supported range`() {
        val map = state()
        map.zoomTo(99.0)
        assertEquals(MapState.MAX_ZOOM, map.zoom, 1e-9)
        map.zoomTo(-5.0)
        assertEquals(MapState.MIN_ZOOM, map.zoom, 1e-9)
    }

    @Test
    fun `visible bounds contain the centre and the corners`() {
        val map = state()
        val bounds = map.visibleBounds()
        assertTrue(map.center in bounds)
        assertTrue(map.latLonOf(Offset(1f, 1f)) in bounds)
        assertTrue(map.latLonOf(Offset(799f, 599f)) in bounds)
    }

    @Test
    fun `visible tiles cover the whole viewport`() {
        val map = state()
        val tiles = map.visibleTiles().toSet()
        assertTrue(tiles.isNotEmpty())

        // Every corner and the centre must fall inside some tile's drawn rectangle.
        val probes = listOf(
            Offset(1f, 1f), Offset(799f, 1f), Offset(1f, 599f),
            Offset(799f, 599f), Offset(400f, 300f),
        )
        for (probe in probes) {
            val covered = tiles.any { key ->
                val rect = map.screenRectOf(key)
                probe.x >= rect.left && probe.x <= rect.left + rect.size &&
                    probe.y >= rect.top && probe.y <= rect.top + rect.size
            }
            assertTrue(covered, "no tile covers $probe")
        }
    }

    @Test
    fun `asks for tiles at the nearest integer zoom`() {
        assertEquals(15, state(zoom = 15.0).tileZoom)
        assertEquals(15, state(zoom = 14.6).tileZoom)
        assertEquals(14, state(zoom = 14.4).tileZoom)
    }

    @Test
    fun `tile scale stays near one so tiles are never badly stretched`() {
        var zoom = 8.0
        while (zoom <= 18.0) {
            val scale = state(zoom = zoom).tileScale
            assertTrue(scale in 0.7..1.45, "scale $scale at zoom $zoom")
            zoom += 0.1
        }
    }

    @Test
    fun `asks for no tiles before the viewport is measured`() {
        val map = MapState(OSLO, 15.0)
        assertTrue(map.visibleTiles().isEmpty())
    }

    @Test
    fun `never asks for a tile outside the world`() {
        val map = MapState(LatLon(85.0, 179.9), 4.0).apply { viewportSize = Size(1600f, 1200f) }
        val count = 1 shl map.tileZoom
        for (key in map.visibleTiles()) {
            assertTrue(key.x in 0 until count, "tile x ${key.x} outside 0..${count - 1}")
            assertTrue(key.y in 0 until count, "tile y ${key.y} outside 0..${count - 1}")
        }
    }
}

class TileSourceTest {

    @Test
    fun `fills in the tile template`() {
        val url = TileSource.OpenStreetMap.urlFor(TileKey(zoom = 14, x = 8681, y = 4765))
        assertEquals("https://tile.openstreetmap.org/14/8681/4765.png", url)
    }
}
