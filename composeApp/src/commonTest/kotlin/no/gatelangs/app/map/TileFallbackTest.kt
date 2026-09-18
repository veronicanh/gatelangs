package no.gatelangs.app.map

import androidx.compose.ui.geometry.Size
import no.gatelangs.app.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val OSLO = LatLon(59.9225, 10.7600)

/**
 * The geometry and key arithmetic behind drawing a coarse tile in place of one that has
 * not loaded. All of it is pure, and all of it is wrong in ways that look plausible on
 * screen — an off-by-one on the sub-rect is a map that is subtly in the wrong place.
 */
class TileFallbackTest {

    @Test
    fun `a parent covers the same ground one level out`() {
        assertEquals(TileKey(14, 4340, 2382), TileKey(15, 8681, 4765).parent())
        // The neighbouring tile shares that parent — four children to one parent is the
        // whole reason one loaded parent can cover a viewport.
        assertEquals(TileKey(14, 4340, 2382), TileKey(15, 8680, 4764).parent())
    }

    @Test
    fun `the root tile has no parent`() {
        assertNull(TileKey(0, 0, 0).parent())
    }

    @Test
    fun `ancestors are distinct and shrink by four at each level`() {
        // A 4x4 block of tiles: 16 of them, covered by 4 parents and 1 grandparent.
        val block = buildList {
            for (x in 8680..8683) for (y in 4764..4767) add(TileKey(15, x, y))
        }
        val out = block.ancestors(levels = 2)

        assertEquals(out.size, out.toSet().size, "no key should be requested twice")
        assertEquals(4, out.count { it.zoom == 14 })
        assertEquals(1, out.count { it.zoom == 13 })
    }

    @Test
    fun `asking for no levels asks for nothing`() {
        assertTrue(listOf(TileKey(15, 8681, 4765)).ancestors(levels = 0).isEmpty())
    }

    @Test
    fun `ancestors stop at the root rather than running past it`() {
        // Two levels requested, but only one exists above zoom 1.
        assertEquals(listOf(TileKey(0, 0, 0)), listOf(TileKey(1, 1, 1)).ancestors(levels = 2))
    }

    @Test
    fun `a parent tile is placed over its child, at twice the span`() {
        val map = MapState(OSLO, 15.0).apply { viewportSize = Size(800f, 600f) }
        val child = map.visibleTiles().first()
        val parent = child.parent()!!

        val childRect = map.screenRectOf(child)
        val parentRect = map.screenRectOf(parent)

        assertEquals(childRect.size * 2f, parentRect.size, 0.01f)
        // The child sits inside its parent, offset by whichever quadrant it occupies.
        val offsetX = (child.x % 2) * childRect.size
        val offsetY = (child.y % 2) * childRect.size
        assertEquals(childRect.left, parentRect.left + offsetX, 0.01f)
        assertEquals(childRect.top, parentRect.top + offsetY, 0.01f)
    }

    @Test
    fun `placing a tile at the drawn zoom is unchanged by the generalisation`() {
        val map = MapState(OSLO, 15.4).apply { viewportSize = Size(800f, 600f) }
        for (key in map.visibleTiles()) {
            // At the rounded zoom the span is TILE_SIZE * tileScale, as it always was.
            assertEquals(256.0 * map.tileScale, map.screenRectOf(key).size.toDouble(), 0.01)
        }
    }
}
