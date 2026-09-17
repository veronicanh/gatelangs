package no.gatelangs.app.location

import kotlinx.coroutines.flow.Flow
import no.gatelangs.app.model.Fix

/** A stream of position fixes. */
interface LocationSource {
    val label: String
    fun fixes(): Flow<Fix>
}

/**
 * The platform's real GPS, or null when it has none.
 *
 * Desktop returns null — a laptop has no GPS, and pretending otherwise would be worse
 * than saying so. The caller substitutes [KeyboardWalker], which is both the dev loop
 * and how the app is demoed indoors.
 */
expect fun createRealLocationSource(): LocationSource?
