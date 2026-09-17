package no.gatelangs.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.gatelangs.app.model.Progress
import no.gatelangs.app.model.byNeighbourhood
import no.gatelangs.app.model.byStreet
import no.gatelangs.app.model.streetsIn

/**
 * How far the city has come, by neighbourhood and then by street.
 *
 * Neighbourhoods first because 149 streets is a list you scroll past rather than read,
 * and because "Sofienberg is nearly done, Tøyen is barely started" is the shape of the
 * answer people actually want. Tapping one opens its streets.
 */
@Composable
fun ProgressScreen(
    viewModel: MapViewModel,
    state: LoadState.Ready,
    /**
     * Read so the tallies recompute as coverage lands. Coverage mutates in place, so
     * this is the only thing telling Compose anything changed.
     */
    coverageRevision: Int,
) {
    val coverage = viewModel.coverage ?: return
    val network = state.network

    // Keyed on the revision so this runs once per change rather than once per frame.
    // Summing every segment is a few tens of thousands of array reads, which is cheaper
    // than the machinery needed to keep a running total correct.
    val areas = remember(coverageRevision, network) { coverage.byNeighbourhood(network) }
    // A snapshot without place nodes still has streets, and a flat street list is a
    // worse answer than the two-level one but a far better one than a paragraph saying
    // there is nothing here.
    val streetsOnly = remember(coverageRevision, network, areas.isEmpty()) {
        if (areas.isEmpty()) coverage.byStreet(network) else emptyList()
    }
    var openArea by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = viewModel::showMap) { Text("‹  Map") }
            Text(
                "Progress",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ProgressRow(
                    progress = Progress(
                        name = "All of it",
                        walkedM = coverage.walkedLengthMeters(),
                        totalM = network.totalLengthM,
                    ),
                    emphasis = true,
                )
                HorizontalDivider()
            }

            items(streetsOnly, key = { "street-" + it.name }) { street ->
                ProgressRow(progress = street)
                HorizontalDivider()
            }

            items(areas, key = { it.name }) { area ->
                val open = openArea == area.name
                ProgressRow(
                    progress = area,
                    modifier = Modifier.clickable { openArea = if (open) null else area.name },
                    trailing = if (open) "▾" else "▸",
                )
                if (open) {
                    val streets = remember(coverageRevision, network, area.name) {
                        coverage.streetsIn(network, area.name)
                    }
                    for (street in streets) {
                        ProgressRow(progress = street, indented = true)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProgressRow(
    progress: Progress,
    modifier: Modifier = Modifier,
    indented: Boolean = false,
    emphasis: Boolean = false,
    trailing: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (indented) 32.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.name,
                style = if (emphasis) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "${(progress.fraction * 100).toTenths()} %",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
        )
        Text(
            // What is left, not what is done: it is the number that tells you whether to
            // put your shoes on.
            "${(progress.remainingM / 1000).toTenths()} km to go  ·  " +
                "${(progress.totalM / 1000).toTenths()} km in all",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
