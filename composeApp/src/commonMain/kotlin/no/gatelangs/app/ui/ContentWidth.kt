package no.gatelangs.app.ui

import androidx.compose.ui.unit.Dp

/**
 * How wide either screen's content is allowed to get.
 *
 * The app owns its window, and a window the user sized is a window they meant: content spans
 * it. A browser tab does not work that way — it is whatever width the screen happens to be,
 * routinely 1600dp on a laptop — and neither screen has anything to do with that much room.
 * The map's readout became a band with the street name at one end and the percentage at the
 * other; the breakdown became rows of two words and a number separated by a hand's width of
 * nothing. Both are the same problem: a column of short lines does not get better by being
 * stretched, it gets harder to track from one line to the next.
 *
 * [Dp.Unspecified] means no ceiling, which is what `widthIn` already reads as "unconstrained",
 * so the app side costs nothing at the call site.
 */
expect val contentMaxWidth: Dp
