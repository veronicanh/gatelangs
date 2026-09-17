package no.gatelangs.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.map.MapState
import no.gatelangs.app.map.TileCache
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.RoadNetwork
import no.gatelangs.app.ui.theme.LocalMapColors
import kotlin.math.pow

/**
 * The map: basemap tiles, the road overlay, and the current position, drawn bottom-up
 * on one [Canvas].
 *
 * Only what is on screen is drawn — the grid index turns "which roads are visible" into
 * a cell lookup, so a city with a hundred thousand segments costs the same per frame as
 * a neighbourhood with a thousand.
 */
@Composable
fun MapCanvas(
    state: MapState,
    tiles: TileCache,
    network: RoadNetwork?,
    coverage: Coverage?,
    position: LatLon?,
    positionAccuracyM: Double?,
    /**
     * Read only so Compose re-runs this when coverage changes: [Coverage] mutates a
     * BooleanArray in place, which is invisible to snapshot state. Bumping an Int is
     * cheaper than making 7000 segments individually observable.
     */
    @Suppress("UNUSED_PARAMETER") coverageRevision: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMapColors.current

    // Requesting tiles is a side effect, so it belongs here and not in the draw pass.
    val visible = state.visibleTiles()
    LaunchedEffect(visible) { tiles.prefetch(visible) }

    Canvas(
        modifier = modifier
            .onSizeChanged { state.viewportSize = it.toSize() }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    if (pan != Offset.Zero) state.panBy(pan)
                    if (zoomChange != 1f) state.zoomBy(zoomChange.toDouble(), centroid)
                }
            }
            .pointerInput(Unit) {
                // Mouse wheel and trackpad scroll — desktop and the browser both send
                // these as Scroll events, which detectTransformGestures does not see.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        // A mouse wheel reports +/-1 per notch, but a trackpad reports
                        // accumulated pixels and can arrive in the tens. Unclamped, one
                        // flick is 2^(-40 * 0.35) and slams straight into the zoom limit.
                        val ticks = change.scrollDelta.y
                            .coerceIn(-MAX_SCROLL_TICKS_PER_EVENT, MAX_SCROLL_TICKS_PER_EVENT)
                        if (ticks == 0f) continue
                        // Scroll up (negative) zooms in.
                        state.zoomBy(2.0.pow(-ticks * ZOOM_PER_SCROLL_TICK), change.position)
                        change.consume()
                    }
                }
            },
    ) {
        drawTiles(state, tiles)
        if (network != null) drawRoads(state, network, coverage, colors.walked, colors.unwalked)
        if (position != null) {
            drawPosition(state, position, positionAccuracyM, colors.currentPosition, colors.positionHalo)
        }
    }
}

private fun DrawScope.drawTiles(state: MapState, tiles: TileCache) {
    for (key in state.visibleTiles()) {
        val image = tiles[key] ?: continue
        val rect = state.screenRectOf(key)
        val size = IntSize(
            // Ceil the drawn size so neighbouring tiles never leave a hairline seam
            // between them from independent rounding.
            width = (rect.size + 1).toInt(),
            height = (rect.size + 1).toInt(),
        )
        drawImage(
            image = image,
            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
            dstSize = size,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun DrawScope.drawRoads(
    state: MapState,
    network: RoadNetwork,
    coverage: Coverage?,
    walkedColor: Color,
    unwalkedColor: Color,
) {
    val ids = network.index.inBounds(state.visibleBounds())
    val width = strokeWidthFor(state.zoom)

    // Two passes, walked first, so unwalked road sits on top at every junction rather
    // than depending on the order the grid happens to return. Deliberately the opposite
    // way round from before: what is left to walk is the thing worth looking at, so it
    // gets the top layer and the heavier stroke, and finished road sinks behind it as a
    // thinner grey line.
    for (pass in 0..1) {
        val drawingWalked = pass == 0
        for (id in ids) {
            val isWalked = coverage?.isWalked(id) == true
            if (isWalked != drawingWalked) continue
            val segment = network.segments[id]
            drawLine(
                color = if (isWalked) walkedColor else unwalkedColor,
                start = state.screenOf(segment.a),
                end = state.screenOf(segment.b),
                strokeWidth = if (isWalked) width else width * 1.4f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawPosition(
    state: MapState,
    position: LatLon,
    accuracyM: Double?,
    dotColor: Color,
    haloColor: Color,
) {
    val screen = state.screenOf(position)

    if (accuracyM != null && accuracyM > 0) {
        // Convert the accuracy radius into screen pixels via a point that far north.
        val edge = LatLon(
            lat = position.lat + accuracyM / METRES_PER_DEGREE_LAT,
            lon = position.lon,
        )
        val radiusPx = (screen - state.screenOf(edge)).getDistance()
        if (radiusPx > 1f) drawCircle(color = haloColor, radius = radiusPx, center = screen)
    }

    drawCircle(color = Color.White, radius = 9f, center = screen)
    drawCircle(color = dotColor, radius = 6f, center = screen)
}

/** Roads thicken with zoom so they stay visible when far out and proportionate when close. */
private fun strokeWidthFor(zoom: Double): Float = when {
    zoom < 13 -> 1.5f
    zoom < 15 -> 2.5f
    zoom < 17 -> 4f
    else -> 6f
}

private const val ZOOM_PER_SCROLL_TICK = 0.35

/** Caps one scroll event at ~one zoom level, so a trackpad flick cannot overshoot. */
private const val MAX_SCROLL_TICKS_PER_EVENT = 3f
private const val METRES_PER_DEGREE_LAT = 111_195.0
