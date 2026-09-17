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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    DISTRICT("Etter bydel"),
    STREET("Etter gate"),
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

    // Opened from the map's readout, this screen should land on the thing that was tapped.
    // Seeded into the remembered state rather than pushed in by an effect, which would
    // render the wrong half for one frame and then swap it; the whole composition is
    // discarded when the screen closes, so these initialisers run again on every entry.
    //
    // DEAD-CODE(progress-deep-link) — `focus` is always null as of 2026-09-17, so every
    // use of it below falls through to the behaviour this screen had before it existed.
    // See ProgressFocus in MapViewModel.kt for why it is still here and when to delete it.
    val focus = viewModel.progressFocus
    var grouping by remember(canGroupByDistrict) {
        mutableStateOf(
            when {
                !canGroupByDistrict -> Grouping.STREET
                focus?.street != null -> Grouping.STREET
                else -> Grouping.DISTRICT
            }
        )
    }

    // Built only for the half on show. Every street in Oslo is a long list to sort, and
    // sorting it while looking at the bydeler would be work nobody asked for.
    val streets = remember(coverageRevision, network, grouping) {
        if (grouping == Grouping.STREET) coverage.byStreet(network) else emptyList()
    }
    // DEAD-CODE(progress-deep-link): the initial value is always null.
    var openDistrict by remember { mutableStateOf(focus?.district) }

    // Every named street in the snapshot, ranked by completion, is a list the street you
    // are standing on sits somewhere in the middle of. Highlighting it without scrolling
    // to it would be no help at all.
    //
    // DEAD-CODE(progress-deep-link): `focusStreet` is always null, so the effect below
    // returns immediately and the list simply opens at the top. `listState` itself is not
    // dead — a LazyColumn wants one either way.
    val listState = rememberLazyListState()
    val focusStreet = focus?.street
    LaunchedEffect(focusStreet) {
        val name = focusStreet ?: return@LaunchedEffect
        val index = streets.indexOfFirst { it.name == name }
        // Keyed on the name alone, never on `streets`: tracking carries on while this
        // screen is open, so that list is a new object every few seconds and keying on it
        // would yank the view back to the focused street again and again.
        //
        // Not animated — a jump across thousands of rows is a blur, not an animation.
        // The +1 is the "Totalt" header, which is a lazy item of its own.
        if (index >= 0) listState.scrollToItem(index + 1)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = viewModel::showMap) { Text("‹  Kart") }
            Text(
                "Detaljer om fremgang",
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

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                ProgressRow(
                    progress = Progress(
                        name = "Totalt",
                        walkedM = coverage.walkedLengthMeters(),
                        totalM = network.totalLengthM,
                    ),
                    emphasis = true,
                )
                HorizontalDivider()
            }

            items(streets, key = { "street-" + it.name }) { street ->
                // Marks where the scroll landed. Without it you arrive at the right place
                // with no idea which of the rows on screen you asked for.
                //
                // DEAD-CODE(progress-deep-link): always false today.
                val focused = street.name == focusStreet
                ProgressRow(
                    progress = street,
                    modifier = if (focused) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        Modifier
                    },
                    emphasis = focused,
                )
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
            // Material draws a dot at the far end of the track by default. On a screen
            // that is mostly a stack of these, it reads as a column of stray marks.
            drawStopIndicator = {},
        )
        Text(
            // What is left, not what is done: it is the number that tells you whether to
            // put your shoes on.
            "${(progress.remainingM / 1000).toTenths()} km igjen  ·  " +
                "${(progress.totalM / 1000).toTenths()} km totalt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
