package no.gatelangs.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import no.gatelangs.app.location.KeyboardWalker
import no.gatelangs.app.location.WalkDirection

/**
 * Lets [walker] be driven by the keyboard for as long as this node's subtree holds focus.
 *
 * `onPreviewKeyEvent` rather than `onKeyEvent`, and on the outermost node: pressing Start
 * leaves focus on the button, and the arrow keys would otherwise be spent walking focus
 * around the controls instead of walking down a street. Previewing from the top catches
 * them wherever focus has ended up, and consuming them stops the default behaviour.
 *
 * [refocusOn] re-takes focus whenever it changes — pass whatever means "the user just
 * clicked a control", so the keys keep working afterwards.
 */
@Composable
fun Modifier.walkerKeyControls(walker: KeyboardWalker?, refocusOn: Any?): Modifier {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(refocusOn) {
        // Throws if the node is not attached yet, which is not worth crashing over: the
        // user can click the map and carry on.
        runCatching { focusRequester.requestFocus() }
    }

    return this
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event -> handleWalkKey(walker, event) }
}

/** Returns whether the event was ours, which is what stops it being handled again. */
private fun handleWalkKey(walker: KeyboardWalker?, event: KeyEvent): Boolean {
    if (walker == null) return false

    // Read off every event rather than tracked as a press and a release of its own: a
    // shift release that arrives while another window has focus would otherwise leave
    // the walker sprinting.
    walker.sprint(event.isShiftPressed)

    val direction = event.key.toWalkDirection() ?: return false
    when (event.type) {
        KeyEventType.KeyDown -> walker.press(direction)
        KeyEventType.KeyUp -> walker.release(direction)
        else -> return false
    }
    return true
}

private fun Key.toWalkDirection(): WalkDirection? = when (this) {
    Key.W, Key.DirectionUp -> WalkDirection.NORTH
    Key.S, Key.DirectionDown -> WalkDirection.SOUTH
    Key.A, Key.DirectionLeft -> WalkDirection.WEST
    Key.D, Key.DirectionRight -> WalkDirection.EAST
    else -> null
}
