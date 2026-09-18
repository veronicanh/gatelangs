package no.gatelangs.app.ui

import androidx.compose.ui.unit.Dp

/**
 * How wide the map's overlay is allowed to get.
 *
 * The app owns its window, and a window the user sized is a window they meant: the panel and
 * the controls span it. A browser tab does not work that way — it is whatever width the
 * screen happens to be, routinely 1600dp on a laptop, and a readout stretched across all of
 * it puts the percentage and the street name a hand's width apart with nothing in between.
 *
 * [Dp.Unspecified] means no ceiling, which is what `widthIn` already reads as "unconstrained",
 * so the app side costs nothing at the call site.
 */
expect val mapOverlayMaxWidth: Dp
