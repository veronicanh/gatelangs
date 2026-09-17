package no.gatelangs.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import no.gatelangs.app.data.RoadSource
import kotlin.math.roundToInt

@Composable
fun MapScreen(viewModel: MapViewModel = viewModel { MapViewModel() }) {
    Box(Modifier.fillMaxSize()) {
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

            is LoadState.Ready -> {
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
                    Controls(viewModel)
                }
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
            if (state.source == RoadSource.BUNDLED) {
                Text(
                    "Offline snapshot — Overpass was unreachable",
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

/** One decimal place, without pulling in a formatting library for two call sites. */
private fun Double.toTenths(): String {
    val scaled = (this * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}
