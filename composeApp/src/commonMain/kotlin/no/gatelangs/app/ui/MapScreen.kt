package no.gatelangs.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import no.gatelangs.app.data.RoadSource
import no.gatelangs.app.model.Achievement
import no.gatelangs.app.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt

@Composable
fun MapScreen(viewModel: MapViewModel = viewModel { MapViewModel() }) {
    // One source of truth for dark: the theme decides, and the basemap follows.
    val dark = LocalIsDarkTheme.current
    LaunchedEffect(dark) { viewModel.setDarkBasemap(dark) }

    Box(
        Modifier.fillMaxSize().walkerKeyControls(
            walker = viewModel.keyboardWalker,
            // Starting or stopping means a click on the button, which takes focus
            // with it. Take it back, or the first keypress after Start does nothing.
            refocusOn = viewModel.isTracking,
        )
    ) {
        when (val state = viewModel.loadState) {
            is LoadState.Loading -> CenteredMessage {
                CircularProgressIndicator()
                Text("Loading the street network…", style = MaterialTheme.typography.bodyMedium)
            }

            is LoadState.Failed -> CenteredMessage {
                Text("Could not load roads", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::load) { Text("Try again") }
            }

            is LoadState.Ready -> if (viewModel.screen == Screen.PROGRESS) {
                ProgressScreen(viewModel, state, viewModel.coverageRevision)
            } else {
                // Reading the revision here is what ties the canvas to coverage changes:
                // Coverage mutates a BooleanArray in place, which Compose cannot observe.
                val revision = viewModel.coverageRevision

                MapCanvas(
                    state = viewModel.mapState,
                    tiles = viewModel.tiles,
                    network = state.network,
                    coverage = viewModel.coverage,
                    position = viewModel.position,
                    positionAccuracyM = viewModel.positionAccuracyM,
                    coverageRevision = revision,
                    modifier = Modifier.fillMaxSize(),
                )

                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    CoveragePanel(viewModel, state)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // In the layout rather than floating over it: the coverage panel
                        // owns the top corner and the marker owns the middle, and a
                        // banner that covered either would hide the thing it is
                        // congratulating you about.
                        AchievementBanner(
                            achievement = viewModel.achievement,
                            visible = viewModel.achievementVisible,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Controls(viewModel)
                        // Both OpenStreetMap and CARTO require this to be shown. It is a
                        // condition of using the tiles, not decoration.
                        Text(
                            viewModel.tiles.source.attribution,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Announces a street reaching a milestone, then gets out of the way.
 *
 * Finishing a street is the event worth celebrating, so it gets a filled card and four
 * seconds. Halfway and nearly-there are encouragement rather than news: a quiet pill,
 * two seconds, and no colour of its own.
 */
@Composable
private fun AchievementBanner(
    achievement: Achievement?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        // Outlives `visible`, which is the point: the content has to survive the exit.
        val current = achievement ?: return@AnimatedVisibility
        if (current.milestone.isCleared) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("🏁", style = MaterialTheme.typography.headlineSmall)
                    Column {
                        Text(
                            current.milestone.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            current.street,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
            ) {
                Text(
                    "${current.street}  ·  ${current.milestone.title.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CoveragePanel(viewModel: MapViewModel, state: LoadState.Ready) {
    val coverage = viewModel.coverage ?: return
    val fraction = coverage.fraction()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        // The overall number is the natural way in to the breakdown behind it.
        onClick = viewModel::showProgress,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${(fraction * 100).toTenths()} % walked",
                style = MaterialTheme.typography.headlineSmall,
            )
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            )
            Text(
                "${(coverage.walkedLengthMeters() / 1000).toTenths()} of " +
                    "${(state.network.totalLengthM / 1000).toTenths()} km  ·  " +
                    "${state.network.segments.size} segments",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "By bydel and street  ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // The bundled snapshot is now the normal path, so silence is the good news.
            // Overpass only runs when the snapshot could not be read, which is worth
            // saying out loud because it means the roads may not match what is saved.
            if (state.source == RoadSource.OVERPASS) {
                Text(
                    "Live from Overpass — the bundled snapshot could not be read",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Controls(viewModel: MapViewModel) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = viewModel::toggleTracking) {
                Text(if (viewModel.isTracking) "Stop" else "Start walking")
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = viewModel.followPosition,
                        onCheckedChange = { viewModel.followPosition = it },
                    )
                    Text("  Follow", style = MaterialTheme.typography.bodySmall)
                }
                // Only worth asking where there is something to choose between: with no
                // GPS the keyboard is the only way to move at all.
                if (viewModel.hasRealGps) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = viewModel.useKeyboard,
                            onCheckedChange = viewModel::setUseKeyboard,
                        )
                        Text("  Keyboard", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (viewModel.locationLabel.isNotEmpty()) {
                    Text(
                        viewModel.locationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

/** One decimal place, without pulling in a formatting library for a handful of call sites. */
internal fun Double.toTenths(): String {
    val scaled = (this * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}
