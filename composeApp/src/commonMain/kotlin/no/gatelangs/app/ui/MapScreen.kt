package no.gatelangs.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import no.gatelangs.app.data.RoadSource
import no.gatelangs.app.model.Achievement
import no.gatelangs.app.model.Progress
import no.gatelangs.app.model.UNNAMED_ROAD
import no.gatelangs.app.model.districtProgress
import no.gatelangs.app.model.streetProgress
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
            // Not while the breakdown is open: the keys belong to that list, and walking
            // on blind through a screen you cannot see the map behind is not the point.
            active = viewModel.screen == Screen.MAP,
            // Every one of these is a click on a control, and a click takes focus with it.
            // Take it back, or the first keypress afterwards does nothing — which is the
            // whole failure, whether the control was Start, a switch, or the way back from
            // the breakdown.
            refocusOn = listOf(
                viewModel.screen,
                viewModel.isTracking,
                viewModel.followPosition,
                viewModel.useKeyboard,
            ),
        )
    ) {
        when (val state = viewModel.loadState) {
            is LoadState.Loading -> CenteredMessage {
                CircularProgressIndicator()
                Text("Laster gatenettet…", style = MaterialTheme.typography.bodyMedium)
            }

            is LoadState.Failed -> CenteredMessage {
                Text("Kunne ikke laste veiene", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::load) { Text("Prøv igjen") }
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
                    CoveragePanel(viewModel, state, revision)
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

/** The last rows the readout had, kept so they survive the collapse animation. */
private class LastPlace {
    var district: Progress? = null
    var street: Progress? = null
}

/**
 * The city, the bydel and the street, in that order of size.
 *
 * Three tiers rather than a switcher between them, because the interesting thing is how
 * differently they move: the city figure barely twitches over a whole walk, while the
 * street under your feet can go from nothing to finished in ten minutes. Seeing both at
 * once is the feedback; having to tap to compare them is not.
 *
 * The lower two lines collapse when there is no road underfoot, so standing still off the
 * network returns the card to its resting size.
 *
 * A readout, deliberately: nothing here is tappable but the button at the bottom, which
 * opens the breakdown at the top of it. The two lines below the bar used to deep-link into
 * that breakdown at the bydel or street named — if that is ever wanted again, this is
 * where it goes, and the plumbing is still in place behind
 * DEAD-CODE(progress-deep-link). If it is not wanted, delete that plumbing rather than
 * carrying it: `grep -rn progress-deep-link composeApp/src`.
 */
@Composable
private fun CoveragePanel(viewModel: MapViewModel, state: LoadState.Ready, coverageRevision: Int) {
    val coverage = viewModel.coverage ?: return
    val fraction = coverage.fraction()
    val network = state.network
    val here = viewModel.whereabouts

    // Keyed on the revision, and that is exactly right rather than merely cheap: mark() is
    // the only writer of walked state and the only thing that makes record() return a
    // non-empty array, so a percentage can change if and only if the revision bumped.
    // Which road you are on is the part that moves independently — hence the name keys.
    val districtProgress = remember(coverageRevision, network, here?.district) {
        coverage.districtProgress(network, here?.district)
    }
    val streetProgress = remember(coverageRevision, network, here?.street, here?.wayId) {
        here?.let { coverage.streetProgress(network, it) }
    }

    // Outlives `here`, so the lines still have something to draw on the way out — the same
    // reason AchievementBanner keeps its achievement past the visibility flag.
    //
    // A plain holder rather than remembered state: nothing needs to recompose *because* of
    // it, since every input that changes it already recomposes this on its own, and writing
    // snapshot state during composition would only buy an extra invalidation pass.
    val shown = remember { LastPlace() }
    if (here != null) {
        shown.district = districtProgress
        shown.street = streetProgress
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        // Deliberately not a clickable Surface. That is one semantics node with
        // Role.Button covering the whole card, which would swallow the lines below it and
        // ripple across everything on every tap. The way in is the labelled link instead.
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                // Oslo by name: there is no city in the model to ask — DEFAULT_AREA is a
                // bare bbox — and both the snapshot and the Overpass query are Oslo.
                "Gatelangs i Oslo",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Du har gått ${(fraction * 100).toTenths()} % av byen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                drawStopIndicator = {},
            )

            AnimatedVisibility(
                visible = here != null,
                // A height animation, not a slide: the requirement is that the card go
                // back to its old size, and a slide would leave the gap behind.
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    // The breathing room lives inside the collapsing block rather than
                    // around it, so it folds away with the lines it separates instead of
                    // leaving a gap behind when there is no road underfoot.
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.district?.let { district ->
                        WhereLine(
                            label = "Bydel: ${district.name}",
                            fraction = district.fraction,
                        )
                    }
                    shown.street?.let { street ->
                        WhereLine(
                            // UNNAMED_ROAD already reads as a full label on its own, so it
                            // is used bare rather than as "Gate: Gate uten navn".
                            label = if (street.name == UNNAMED_ROAD) {
                                street.name
                            } else {
                                "Gate: ${street.name}"
                            },
                            fraction = street.fraction,
                        )
                    }
                }
            }

            // The bundled snapshot is now the normal path, so silence is the good news.
            // Overpass only runs when the snapshot could not be read, which is worth
            // saying out loud because it means the roads may not match what is saved.
            if (state.source == RoadSource.OVERPASS) {
                Text(
                    "Live fra Overpass — det innebygde datasettet kunne ikke leses",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(
                onClick = viewModel::showProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Tonal rather than filled: the card is a readout, and the one thing on
                // this screen that should look like the primary action is Start å gå.
                Text("Detaljer om fremgang  ›")
            }
        }
    }
}

/**
 * One "you are here" line: what you are standing in, how much of it you have walked, and a
 * bar saying the same thing at a glance.
 *
 * The percentage is pushed to the right rather than following the name, so it stays put
 * while a long street name ellipsises — "Professor Lochmanns vei" is not unusual, and a
 * number that moves around with the text is one you have to hunt for on every glance.
 */
@Composable
private fun WhereLine(
    label: String,
    fraction: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(fraction * 100).toTenths()} %",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { fraction.toFloat() },
            // Thinner than the city's bar, so the hierarchy still reads at a glance.
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            drawStopIndicator = {},
        )
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
                Text(
                    when {
                        viewModel.isTracking -> "Stopp"
                        // Naming what the button will actually do. The keyboard walker is
                        // a simulation and saying so is honest; real GPS is a walk, and
                        // calling that a simulation would be a lie on the one platform
                        // where the position is genuinely yours.
                        viewModel.useKeyboard -> "Start simulering"
                        else -> "Start å gå"
                    }
                )
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = viewModel.followPosition,
                        onCheckedChange = { viewModel.followPosition = it },
                    )
                    Text("  Følg", style = MaterialTheme.typography.bodySmall)
                }
                // Only worth asking where there is something to choose between: with no
                // GPS the keyboard is the only way to move at all.
                if (viewModel.hasRealGps) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = viewModel.useKeyboard,
                            onCheckedChange = viewModel::setUseKeyboard,
                        )
                        Text("  Tastatur", style = MaterialTheme.typography.bodySmall)
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
