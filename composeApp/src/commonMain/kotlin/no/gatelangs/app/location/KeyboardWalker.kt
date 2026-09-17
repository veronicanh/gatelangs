package no.gatelangs.app.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2
import no.gatelangs.app.model.Coverage
import no.gatelangs.app.model.Fix
import kotlin.math.sqrt

/** One of the four directions a key can push the walker in. Absolute, not relative. */
enum class WalkDirection { NORTH, SOUTH, EAST, WEST }

/**
 * A position you drive yourself, with WASD or the arrow keys.
 *
 * The map is north-up and does not rotate, so the keys are absolute rather than a
 * steering wheel: W goes north whichever way you were last going. Tank controls need you
 * to know which way you are pointing, and on a map with no compass that is exactly what
 * you do not know. Holding two keys goes diagonally, at the same speed rather than the
 * 1.41x that falling out of the arithmetic would give you.
 *
 * Nothing constrains it to the road network. It is a tool for driving the matching
 * pipeline by hand, and a position that slides along roads on its own would hide the
 * very thing you would be using it to look at.
 */
class KeyboardWalker(
    start: LatLon,
    private val projection: MetricProjection,
    private val speedMps: Double = MAP_SPEED_MPS,
    private val sprintMultiplier: Double = SPRINT_MULTIPLIER,
    private val tickMs: Long = TICK_MS,
    private val accuracyM: Double = 6.0,
) : LocationSource {

    override val label: String = "WASD / arrows  ·  shift to sprint"

    /**
     * Which directions are currently held.
     *
     * Not synchronised, and does not need to be: key events arrive on the UI thread and
     * the flow below is collected on `viewModelScope`, which is the same main dispatcher
     * on both desktop and web.
     */
    private val held = mutableSetOf<WalkDirection>()
    private var sprinting = false
    private var here: LatLon = start

    fun press(direction: WalkDirection) {
        held.add(direction)
    }

    fun release(direction: WalkDirection) {
        held.remove(direction)
    }

    fun sprint(on: Boolean) {
        sprinting = on
    }

    /**
     * Drops every held key.
     *
     * Needed because key-up can go missing — alt-tab away mid-stride and the release
     * never arrives, leaving the walker sliding north for as long as the app is open.
     */
    fun releaseAll() {
        held.clear()
        sprinting = false
    }

    override fun fixes(): Flow<Fix> = flow {
        var clock = 0L
        emit(fixAt(here, clock)) // so the marker appears before a key is touched

        while (true) {
            delay(tickMs)
            clock += tickMs

            val push = pushVector() ?: continue // standing still emits nothing
            val metres = speedMps * (if (sprinting) sprintMultiplier else 1.0) * (tickMs / 1000.0)
            val from = projection.project(here)
            here = projection.unproject(
                Vec2(from.x + push.x * metres, from.y + push.y * metres)
            )
            emit(fixAt(here, clock))
        }
    }

    /** The held keys as a unit vector in the metres plane, or null if none are held. */
    private fun pushVector(): Vec2? {
        var x = 0.0
        var y = 0.0
        if (WalkDirection.EAST in held) x += 1.0
        if (WalkDirection.WEST in held) x -= 1.0
        if (WalkDirection.NORTH in held) y += 1.0
        if (WalkDirection.SOUTH in held) y -= 1.0

        // Opposite keys held together cancel out, which is the same as standing still.
        val length = sqrt(x * x + y * y)
        if (length == 0.0) return null
        return Vec2(x / length, y / length)
    }

    private fun fixAt(point: LatLon, clock: Long): Fix = Fix(
        lat = point.lat,
        lon = point.lon,
        accuracyM = accuracyM,
        timestampMs = clock,
    )

    companion object {
        /** One position update every this long. Four a second reads as continuous motion. */
        const val TICK_MS = 250L

        /**
         * Deliberately far faster than walking, and it cannot sensibly be slower.
         *
         * [Coverage] works out direction of travel from consecutive fixes and discards it
         * below [Coverage.MIN_HEADING_DISTANCE_M] — sensible for real GPS, where a
         * smaller step is indistinguishable from noise. With the bearing gate off, a fix
         * credits every segment within 15 m of it, including the pavement and the street
         * running parallel, so coverage lights up road you never went near.
         *
         * At [TICK_MS] this puts about 4.5 m between fixes, which keeps the gate fed, and
         * only roads you are actually travelling along get credited. It also means a
         * demo crosses a neighbourhood in seconds rather than in real time, which is the
         * other thing you want from this.
         */
        const val MAP_SPEED_MPS = 18.0

        /** Held shift, for crossing town. */
        const val SPRINT_MULTIPLIER = 3.0
    }
}
