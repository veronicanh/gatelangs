package no.gatelangs.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Gatelangs palette. The basemap tiles carry most of the colour on screen, so the
 * app chrome stays deliberately quiet and the *road overlay* gets the saturation —
 * a walked street has to read as "walked" at a glance, over any tile underneath.
 *
 * Every role named here is one the app actually paints with. The ones *not* named fall back
 * to Material's stock baseline, a lavender scheme belonging to no part of this app — so
 * `tertiary`, `error` and the whole `surfaceContainer*` family are off limits until somebody
 * sets them.
 *
 * That is not a style rule. `secondaryContainer` and `outlineVariant` were both unset until
 * recently, and Material reaches for them without being asked: `secondaryContainer` is the
 * default track of every `LinearProgressIndicator` and the selected half of the bydel/gate
 * toggle, `outlineVariant` the default `HorizontalDivider` and chip border. Stock #4A4458
 * and #49454F were on screen the whole time, on a palette that is otherwise green and
 * near-black. `Card` is still unusable for the same reason — its container is
 * `surfaceContainerLow`. Use `Surface(shape, tonalElevation)` instead, which derives from
 * `surface` and `primary`, both of which are ours.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F0C6),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DA),
    onSecondaryContainer = Color(0xFF0A1F16),
    background = Color(0xFFF6FBF6),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC1CBC2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD5AB),
    onPrimary = Color(0xFF003821),
    primaryContainer = Color(0xFF005231),
    onPrimaryContainer = Color(0xFFA7F0C6),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1E352A),
    secondaryContainer = Color(0xFF35493E),
    onSecondaryContainer = Color(0xFFCFE9DA),
    background = Color(0xFF101410),
    onBackground = Color(0xFFDFE4DE),
    surface = Color(0xFF101410),
    onSurface = Color(0xFFDFE4DE),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C0),
    outline = Color(0xFF8B938B),
    outlineVariant = Color(0xFF3A423B),
)

/**
 * Colours for the road overlay drawn on the map canvas. These are *not* part of the
 * Material scheme: they are picked to stay legible on top of raster tiles rather than
 * on top of [MaterialTheme]'s own surfaces.
 *
 * [unwalked] is the loud one, and that inversion is the point. Green-on-grey rewarded
 * you for where you had been, but the useful question when you are standing in the
 * street is *where haven't I been*, and the answer was drawn in 40% grey underneath
 * everything else. Amber reads as work outstanding, and it fades from the map as the
 * city gets finished.
 *
 * [walked] is a plain grey line. Grey is the colour of a road that no longer needs
 * anything from you: it carries no meaning of its own, so it does not compete with the
 * amber for attention, and a finished part of the city settles back into the basemap.
 * Mid grey rather than dark, because the basemap under it is nearly black and a road
 * has to stay distinguishable from the ground it is drawn on.
 */
data class MapColors(
    val walked: Color,
    val unwalked: Color,
    val currentPosition: Color,
    val positionHalo: Color,
)

private val LightMapColors = MapColors(
    walked = Color(0xFF515A53),
    unwalked = Color(0xFF17874B),
    currentPosition = Color(0xFF0B64D6),
    positionHalo = Color(0x330B64D6),
)

private val DarkMapColors = MapColors(
    walked = Color(0xFF515A53),
    unwalked = Color(0xFF17874B),
    currentPosition = Color(0xFF63A8FF),
    positionHalo = Color(0x3363A8FF),
)

val LocalMapColors = staticCompositionLocalOf { LightMapColors }

/**
 * Whether the dark theme is in force, for the things Material cannot answer for — chiefly
 * which basemap to fetch, which is a network concern rather than a colour.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun GatelangsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMapColors provides if (darkTheme) DarkMapColors else LightMapColors,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
