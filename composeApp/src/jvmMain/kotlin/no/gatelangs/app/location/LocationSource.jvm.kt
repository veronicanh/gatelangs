package no.gatelangs.app.location

/** A laptop has no GPS. The caller falls back to [SimulatedWalker]. */
actual fun createRealLocationSource(): LocationSource? = null
