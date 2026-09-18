package no.gatelangs.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A little wider than the 420dp the desktop build opens at, so a phone browser is unaffected
 * — below this the ceiling never binds and the layout is exactly what it always was — and a
 * laptop gets a column rather than a band stretched across the whole screen.
 */
actual val contentMaxWidth: Dp = 460.dp
