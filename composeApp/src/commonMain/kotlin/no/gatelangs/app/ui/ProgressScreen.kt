package no.gatelangs.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.gatelangs.app.model.Progress
import no.gatelangs.app.model.byDistrict
import no.gatelangs.app.model.byStreet
import no.gatelangs.app.model.streetsIn

/** The two ways of slicing the same coverage. */
private enum class Grouping(val label: String) {
    DISTRICT("By bydel"),
    STREET("By street"),
}

/**
 * How far the city has come, sliced either by bydel or by street.
 *
 * Two answers to two different questions, which is why this is a switch rather than one
 * list. By bydel is "where should I go next" — the fifteen-odd districts Oslo actually
 * divides itself into, each opening into the streets inside it. By street is "how is
 * Parkveien doing" — every street in the snapshot in one ranking, which is a list you
 * scan for a name rather than read top to bottom.
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
    val districts = remember(coverageRevision, network) { coverage.byDistrict(network) }

    // A snapshot with no district outlines has nothing to group by, so there is nothing
    // to switch between either: the switch disappears rather than offering an empty half.
    val canGroupByDistrict = districts.isNotEmpty()
    var grouping by remember(canGroupByDistrict) {
        mutableStateOf(if (canGroupByDistrict) Grouping.DISTRICT else Grouping.STREET)
    }

    // Built only for the half on show. Every street in Oslo is a long list to sort, and
    // sorting it while looking at the bydeler would be work nobody asked for.
    val streets = remember(coverageRevision, network, grouping) {
        if (grouping == Grouping.STREET) coverage.byStreet(network) else emptyList()
    }
    var openDistrict by remember { mutableStateOf<String?>(null) }

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

        if (canGroupByDistrict) {
            GroupingToggle(
                selected = grouping,
                onSelect = { grouping = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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

            items(streets, key = { "street-" + it.name }) { street ->
                ProgressRow(progress = street)
                HorizontalDivider()
            }

            if (grouping == Grouping.DISTRICT) {
                items(districts, key = { it.name }) { district ->
                    val open = openDistrict == district.name
                    ProgressRow(
                        progress = district,
                        modifier = Modifier.clickable { openDistrict = if (open) null else district.name },
                        trailing = if (open) "▾" else "▸",
                    )
                    if (open) {
                        val inDistrict = remember(coverageRevision, network, district.name) {
                            coverage.streetsIn(network, district.name)
                        }
                        for (street in inDistrict) {
                            ProgressRow(progress = street, indented = true)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * A two-way switch built out of [Surface] rather than `SegmentedButton`.
 *
 * Material's segmented button is still an experimental API; two clickable surfaces in a
 * pill need no opt-in and look the same here.
 */
@Composable
private fun GroupingToggle(
    selected: Grouping,
    onSelect: (Grouping) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(pill)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        for (option in Grouping.entries) {
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shape = pill,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
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
