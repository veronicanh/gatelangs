package no.gatelangs.app.ui

import kotlin.math.roundToInt

/**
 * One decimal place, comma-separated as Norwegian writes it.
 *
 * The single place every number in the app is formatted, which is what makes the decimal
 * comma one change rather than twenty — and a full stop here is the detail that would make
 * the whole interface read as translated rather than as Norwegian.
 */
internal fun Double.toTenths(): String {
    val scaled = (this * 10).roundToInt()
    return "${scaled / 10},${scaled % 10}"
}

/** A fraction in 0..1, as the percentage the screens print. */
internal fun percentOf(fraction: Double): String = "${(fraction * 100).toTenths()} %"

/** Metres, as the kilometres the screens print. Includes "km" */
internal fun labeledKmOf(meters: Double): String = "${(meters / 1000).toTenths()} km"

/** Metres, as the kilometres the screens print. Without "km" */
internal fun kmOf(meters: Double): String = (meters / 1000).toTenths()

