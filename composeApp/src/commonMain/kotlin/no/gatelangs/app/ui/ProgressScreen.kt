package no.gatelangs.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.gatelangs.app.model.DistrictGroup
import no.gatelangs.app.model.Grouping
import no.gatelangs.app.model.Progress
import no.gatelangs.app.model.ProgressFilter
import no.gatelangs.app.model.ProgressView
import no.gatelangs.app.model.SortKey
import no.gatelangs.app.model.arrange
import no.gatelangs.app.model.arrangeDistricts
import no.gatelangs.app.model.byDistrict
import no.gatelangs.app.model.byStreet
import no.gatelangs.app.model.directionLabel
import no.gatelangs.app.model.isFinished
import no.gatelangs.app.model.labelFor
import no.gatelangs.app.model.namedStreets
import no.gatelangs.app.model.needsArrow
import no.gatelangs.app.model.streetsByDistrict
import no.gatelangs.app.ui.theme.LocalMapColors
import kotlin.math.roundToInt

/**
 * How far the city has come, sliced either by bydel or by street.
 *
 * Two answers to two different questions, which is why this is a switch rather than one
 * list. By bydel is "where should I go next" — the fifteen-odd districts Oslo actually
 * divides itself into, each opening into the streets inside it. By street is "how is
 * Parkveien doing" — every street in the snapshot in one ranking.
 *
 * The chrome is arranged around one number: how much of the screen is left for the list.
 * At 360×600 — which is what a phone browser gives you, since `index.html` turns off
 * zooming — a row is 71dp, so every pinned control costs most of a row. Only the search box
 * and the line that says what the search did are pinned; the summary, the switch and the
 * sort chips are all the first three items of the list itself and scroll away, because
 * choosing a sort is something you do once and reading the results is something you do all
 * the way down.
 */
@Composable
fun ProgressScreen(
    viewModel: MapViewModel,
    state: LoadState.Ready,
    /**
     * Read so the tallies recompute as coverage lands. Coverage mutates in place, so this is
     * the only thing telling Compose anything changed.
     */
    coverageRevision: Int,
) {
    val coverage = viewModel.coverage ?: return
    val network = state.network

    // Keyed on the revision so these run once per change rather than once per frame. Each is
    // one pass over the segment array — a few thousand doubles, cheaper than the machinery
    // needed to keep three running totals correct.
    val districts = remember(coverageRevision, network) { coverage.byDistrict(network) }
    val streets = remember(coverageRevision, network) { coverage.byStreet(network) }
    // Built whole rather than per-bydel-on-demand: searching has to look inside all of them
    // at once, and this is the same work eighteen separate lookups would do anyway.
    val streetsByDistrict = remember(coverageRevision, network) { coverage.streetsByDistrict(network) }

    // A snapshot with no district outlines has nothing to group by, so there is nothing to
    // switch between either: the switch disappears rather than offering an empty half.
    val canGroupByDistrict = districts.isNotEmpty()

    // Opened from the map's readout, this screen should land on the thing that was tapped.
    // Seeded into the remembered state rather than pushed in by an effect, which would render
    // the wrong half for one frame and then swap it; the whole composition is discarded when
    // the screen closes, so these initialisers run again on every entry — which is the point.
    // Sort and filter are a reading position, not a setting, and a list that comes back
    // filtered from ten minutes ago looks broken rather than remembered.
    val focus = viewModel.progressFocus
    var view by remember(canGroupByDistrict) {
        mutableStateOf(
            ProgressView(
                grouping = when {
                    !canGroupByDistrict -> Grouping.STREET
                    focus?.street != null -> Grouping.STREET
                    else -> Grouping.DISTRICT
                },
            )
        )
    }
    var query by remember { mutableStateOf("") }
    // Several at once, not one: comparing two bydeler is the natural thing to want, and an
    // accordion answers that by closing the one you were reading.
    var openDistricts by remember { mutableStateOf(setOfNotNull(focus?.district)) }

    // Never written during composition — the clamp is a rendering decision, not a state change.
    val grouping = if (canGroupByDistrict) view.grouping else Grouping.STREET
    val searching = query.isNotBlank()

    val arrangedStreets = remember(streets, view, query, grouping) {
        if (grouping == Grouping.STREET) streets.arrange(view, query) else emptyList<Progress>()
    }
    val groups = remember(districts, streetsByDistrict, view, query, grouping) {
        if (grouping == Grouping.DISTRICT) {
            arrangeDistricts(districts, streetsByDistrict, view, query)
        } else {
            emptyList<DistrictGroup>()
        }
    }

    val bodyRows = remember(grouping, arrangedStreets, groups, openDistricts) {
        buildBodyRows(grouping, arrangedStreets, groups, openDistricts)
    }

    // Everything above the first breakdown row, counted from the same conditions that emit
    // them rather than written down as a number — a stale "+1 for the Totalt header" is
    // exactly the bug that survives a redesign.
    val headerItems = 1 + (if (canGroupByDistrict) 1 else 0) + 1

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(bodyRows)

    LaunchedEffect(focus, headerItems) {
        val target = focus ?: return@LaunchedEffect
        // Opened *at* something, so nothing may stand between you and it. The sort survives —
        // it only moves a row, never hides it — but a filter or a leftover query can remove
        // the row outright, and then the scroll lands somewhere arbitrary and reads as a bug.
        query = ""
        view = view.copy(filter = ProgressFilter.ALL)
        // Let that reset land: the list to index into is the one the reset produces, not the
        // one that was on screen when this effect started.
        withFrameNanos {}
        val index = currentRows.indexOfFirst { row ->
            if (target.street != null) {
                row.kind != BodyRow.Kind.DISTRICT && row.progress.name == target.street
            } else {
                row.kind == BodyRow.Kind.DISTRICT && row.progress.name == target.district
            }
        }
        // Not animated — a jump across hundreds of rows is a blur, not an animation. Nothing
        // found is not a reason to jump to the top; staying put is the lesser surprise.
        if (index >= 0) listState.scrollToItem(headerItems + index)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = viewModel::showMap,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("‹  Kart") }
            SearchField(
                query = query,
                onQuery = { query = it },
                modifier = Modifier.weight(1f),
            )
        }

        ResultLine(
            count = bodyRows.count { it.kind != BodyRow.Kind.SHOW_ALL && it.kind != BodyRow.Kind.CHILD },
            childCount = bodyRows.count { it.kind == BodyRow.Kind.CHILD },
            query = query,
            grouping = grouping,
            view = view,
            onScrollToControls = { scope.launch { listState.animateScrollToItem(0) } },
        )

        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item("summary") {
                val named = streets.namedStreets()
                TotalCard(
                    walkedM = coverage.walkedLengthMeters(),
                    totalM = network.totalLengthM,
                    finished = named.count { it.isFinished },
                    of = named.size,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (canGroupByDistrict) {
                item("tabs") {
                    GroupingToggle(
                        selected = grouping,
                        onSelect = { view = view.groupedBy(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            item("controls") {
                ControlRow(view = view, onView = { view = it })
                HorizontalDivider()
            }

            items(
                bodyRows,
                // The key has to carry the parent: Parkveien exists as a top-level row and as
                // a child of two different bydeler, and a duplicate key is a crash, not a glitch.
                key = { "${it.kind}-${it.parent.orEmpty()}-${it.progress.name}" },
            ) { row ->
                BreakdownRow(
                    row = row,
                    query = query,
                    onToggle = {
                        val name = row.progress.name
                        openDistricts = if (name in openDistricts) openDistricts - name else openDistricts + name
                    },
                    onShowAll = {
                        query = ""
                        openDistricts = openDistricts + row.progress.name
                    },
                )
            }

            if (bodyRows.isEmpty()) {
                item("empty") {
                    EmptyState(
                        query = query,
                        filter = view.filter,
                        searching = searching,
                        onReset = {
                            query = ""
                            view = view.copy(filter = ProgressFilter.ALL)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One flat list of everything the breakdown draws, built before the `LazyColumn` rather than
 * inside it.
 *
 * Flat is what makes "scroll to Parkveien" a single `indexOfFirst` instead of arithmetic over
 * nested groups — and it is what stopped an expanded bydel from being one enormous lazy item
 * with its children looped inside it.
 */
private data class BodyRow(
    val progress: Progress,
    val kind: Kind,
    /** The bydel a child hangs under. Part of the key: two bydeler can share a street. */
    val parent: String? = null,
    val expanded: Boolean = false,
    val expandable: Boolean = true,
    val matchCount: Int = 0,
    val totalStreets: Int = 0,
    /** Carries the rule off the bottom of an expanded group, so bydeler stay separated. */
    val lastInGroup: Boolean = false,
) {
    enum class Kind { DISTRICT, STREET, CHILD, SHOW_ALL }
}

private fun buildBodyRows(
    grouping: Grouping,
    streets: List<Progress>,
    groups: List<DistrictGroup>,
    openDistricts: Set<String>,
): List<BodyRow> = buildList {
    if (grouping == Grouping.STREET) {
        for (street in streets) add(BodyRow(street, BodyRow.Kind.STREET))
        return@buildList
    }
    for (group in groups) {
        val open = group.expandedByQuery || group.district.name in openDistricts
        add(
            BodyRow(
                progress = group.district,
                kind = BodyRow.Kind.DISTRICT,
                expanded = open,
                // While the query is driving the expansion the chevron would be a lie: tapping
                // it cannot close what the query opened. A dead affordance is worse than none,
                // so the row shows a count of matches instead.
                expandable = !group.expandedByQuery,
                matchCount = if (group.expandedByQuery) group.streets.size else 0,
                totalStreets = group.totalStreets,
            )
        )
        if (!open) continue
        for ((i, street) in group.streets.withIndex()) {
            add(
                BodyRow(
                    progress = street,
                    kind = BodyRow.Kind.CHILD,
                    parent = group.district.name,
                    // Only when nothing follows inside the group: with the "vis alle" row
                    // below, that one carries the rule instead.
                    lastInGroup = !group.expandedByQuery && i == group.streets.lastIndex,
                )
            )
        }
        if (group.expandedByQuery) {
            add(
                BodyRow(
                    progress = group.district,
                    kind = BodyRow.Kind.SHOW_ALL,
                    parent = group.district.name,
                    totalStreets = group.totalStreets,
                    lastInGroup = true,
                )
            )
        }
    }
}

/**
 * The one control pinned all the way down the list.
 *
 * Sharing the row with the back button rather than taking a row of its own is what keeps the
 * permanent chrome to one line: the back button already costs its height, and the field takes
 * the width that was going spare. Not `SearchBar`, which is still an experimental API and
 * wants to own the screen with a results surface of its own — a much bigger thing than a box
 * that narrows a list already on show. Outlined rather than filled because the filled
 * variant's container is `surfaceContainerHighest`, a role this theme does not set.
 *
 * Deliberately not focused on arrival: in a browser that raises the keyboard over half the
 * viewport before a single row has been seen, and on desktop it takes the arrow keys away
 * from the list.
 */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.onPreviewKeyEvent { event ->
            // Esc clears rather than closes, because there is nothing to close — the field is
            // always here. Consumed only when there is something to clear, so an empty field
            // lets Esc through to whatever else might want it.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                onQuery("")
                true
            } else {
                false
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text("Søk etter gate eller bydel", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = { onQuery("") },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) { Icon(Icons.Default.Close, contentDescription = "Tøm søket") }
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * What the search did, and what order the list is in.
 *
 * Pinned next to the field rather than left to scroll, because it is the feedback loop for
 * what you are typing and you look at where you typed. At rest it is a plain count; the
 * ratio only appears once something has been narrowed, since "149 gater" is not news.
 *
 * The sort readout is here because the chips themselves scroll away — you should never have
 * to wonder why the list is in this order — and tapping it brings them back.
 */
@Composable
private fun ResultLine(
    count: Int,
    childCount: Int,
    query: String,
    grouping: Grouping,
    view: ProgressView,
    onScrollToControls: () -> Unit,
) {
    val noun = if (grouping == Grouping.DISTRICT) {
        if (count == 1) "bydel" else "bydeler"
    } else {
        if (count == 1) "gate" else "gater"
    }
    val label = when {
        query.isBlank() -> "$count $noun"
        // A bydel search hits two kinds of thing at once, so it has to report both or the
        // number will not match what is on screen.
        grouping == Grouping.DISTRICT -> "$count $noun · $childCount gater"
        else -> "$count treff"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onScrollToControls,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            val arrow = if (view.sort.key.needsArrow) {
                if (view.sort.descending) " ↓" else " ↑"
            } else {
                ""
            }
            Text(
                view.sort.key.labelFor(view.sort.descending) + arrow,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * The headline, and the one place on this screen that borrows a colour from the map.
 *
 * A [Surface] with tonal elevation rather than a `Card`: Material's card container is
 * `surfaceContainerLow`, a role this theme never sets, so it arrives from the stock baseline
 * as a lavender-black belonging to no part of this app. Tonal elevation derives from
 * `surface` and `primary`, both of which are ours. Same 14dp corner as the cards on the map.
 *
 * Nothing here is ever time-based. Coverage is a `BooleanArray` with no timestamps, so
 * "+2,3 km denne uken", streaks and "ferdig om åtte måneder" are not merely missing, they are
 * uncomputable — and inventing them from session state would be a lie on the one screen whose
 * whole job is to be believed.
 */
@Composable
private fun TotalCard(
    walkedM: Double,
    totalM: Double,
    finished: Int,
    of: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = if (totalM <= 0.0) 0.0 else walkedM / totalM
    val outstanding = LocalMapColors.current.unwalked
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Detaljer om fremgang",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percentOf(fraction), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "$finished av $of gater ferdig",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                // The track is the road still to walk, so it is the map's amber, quietened.
                // This is the one bar on the screen big enough to carry it.
                trackColor = outstanding.copy(alpha = 0.30f),
                drawStopIndicator = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${kmOf(walkedM)} gått",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    // What is left, not what is done: the number that tells you whether to put
                    // your shoes on, in the colour the map paints road still to walk. Nothing
                    // else here is amber, which is what keeps it meaning that.
                    "${kmOf(totalM - walkedM)} igjen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = outstanding,
                )
            }
        }
    }
}

/**
 * A two-way switch built out of [Surface] rather than `SegmentedButton`.
 *
 * Material's segmented button is no longer experimental, but two clickable surfaces in a pill
 * were already here and look the same; swapping them would be churn, not a fix.
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .selectableGroup(),
    ) {
        for (option in Grouping.entries) {
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand),
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

/**
 * Chip colours off this app's own palette.
 *
 * Selected chips sit on `primaryContainer` so they read as *chosen* rather than merely
 * tinted. Material would reach for `secondaryContainer`, which is fine now that the theme
 * sets it — but the green container is the stronger signal, and saying so here means the next
 * chip added to this screen inherits the decision instead of the default.
 */
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * One row: how the list is ordered, then what is left out of it.
 *
 * Chips rather than a menu because a menu hides the answer behind a tap — this way the order
 * in force and its direction are both readable without touching anything, and changing either
 * is one tap. The divider is load-bearing: sorting and filtering are different kinds of
 * decision, and two kinds of control in one row only works if the row says which is which.
 *
 * The arrow appears only on the chip actually doing the sorting, which is what a sortable
 * table header has always done — on the others it would be a promise about an order the list
 * is not in. `A–Å` needs no arrow at all, because the letters already say which way it runs.
 */
@Composable
private fun ControlRow(
    view: ProgressView,
    onView: (ProgressView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.selectableGroup(),
        ) {
            for (key in SortKey.entries) {
                val active = key == view.sort.key
                // Inactive chips show the label they *would* take, which is their natural
                // direction — tapping one starts it there, so the label is never a surprise.
                val descending = if (active) view.sort.descending else key.defaultDescending
                FilterChip(
                    selected = active,
                    onClick = { onView(view.sortedBy(key)) },
                    label = { Text(key.labelFor(descending)) },
                    trailingIcon = if (!active || !key.needsArrow) {
                        null
                    } else {
                        {
                            Icon(
                                imageVector = if (descending) {
                                    Icons.Default.KeyboardArrowDown
                                } else {
                                    Icons.Default.KeyboardArrowUp
                                },
                                // Read out rather than left as decoration: for this key the
                                // arrow *is* the difference between the two orders.
                                contentDescription = key.directionLabel(descending),
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    },
                    colors = chipColors(),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }

        VerticalDivider(Modifier.height(24.dp))

        val hiding = view.filter == ProgressFilter.UNFINISHED
        FilterChip(
            selected = hiding,
            onClick = { onView(view.toggleUnfinished()) },
            label = { Text(ProgressFilter.UNFINISHED.label) },
            leadingIcon = if (!hiding) {
                null
            } else {
                {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            },
            colors = chipColors(),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

/** Where the rail down an expanded bydel's children sits, measured from the screen edge. */
private val CHILD_RAIL_X = 22.dp

/** One entry of the breakdown, whichever of the four kinds it is. */
@Composable
private fun BreakdownRow(
    row: BodyRow,
    query: String,
    onToggle: () -> Unit,
    onShowAll: () -> Unit,
) {
    val rail = MaterialTheme.colorScheme.outline
    when (row.kind) {
        BodyRow.Kind.STREET -> {
            ProgressRow(progress = row.progress)
            HorizontalDivider()
        }

        BodyRow.Kind.DISTRICT -> {
            ProgressRow(
                progress = row.progress,
                modifier = if (row.expandable) {
                    Modifier
                        .clickable(onClickLabel = "Vis gatene i ${row.progress.name}") { onToggle() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { stateDescription = if (row.expanded) "utvidet" else "sammenslått" }
                } else {
                    Modifier
                },
                expanded = if (row.expandable) row.expanded else null,
                // The line that makes the parent's numbers and the visible children
                // reconcile. Without it, three streets under a bydel that says 10,9 km totalt
                // invites you to conclude the bydel has three streets.
                note = if (row.matchCount > 0) {
                    "${row.matchCount} av ${row.totalStreets} gater matcher «${query.trim()}»"
                } else {
                    null
                },
            )
            if (!row.expanded) HorizontalDivider()
        }

        BodyRow.Kind.CHILD -> {
            ProgressRow(
                progress = row.progress,
                indented = true,
                modifier = Modifier.railed(rail),
            )
            if (row.lastInGroup) HorizontalDivider()
        }

        BodyRow.Kind.SHOW_ALL -> {
            // The natural next action once you have found the street: see what else is round
            // it. Keeps the bydel open and drops the query.
            Text(
                "Vis alle ${row.totalStreets} gater i ${row.progress.name}  ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .railed(rail)
                    .clickable { onShowAll() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(start = 32.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            )
            HorizontalDivider()
        }
    }
}

/**
 * A 2dp line down the left of a child row.
 *
 * Drawn rather than laid out, so it needs no intrinsic measurement and cannot disagree with
 * the row's height. Stacked, the adjacent segments read as one rail bracketing the group —
 * which is what stops three indented rows from looking like the whole bydel.
 */
private fun Modifier.railed(color: Color): Modifier = drawBehind {
    val x = CHILD_RAIL_X.toPx()
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 2.dp.toPx(),
    )
}

@Composable
private fun ProgressRow(
    progress: Progress,
    modifier: Modifier = Modifier,
    indented: Boolean = false,
    /** Null when the row does not expand; otherwise which way the chevron points. */
    expanded: Boolean? = null,
    note: String? = null,
) {
    val spoken = remember(progress, note) { spokenRow(progress, note) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (indented) 32.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (expanded != null) {
                Icon(
                    // An icon rather than the "▾"/"▸" characters this used to draw: those go
                    // through font fallback, and a browser on wasm is exactly where fallback
                    // is least predictable.
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                )
            }
            Text(
                percentOf(progress.fraction),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                // The row already says the number; Material would otherwise announce the bar
                // as a second, differently-worded progress reading.
                .clearAndSetSemantics {},
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // Material draws a dot at the far end of the track by default. On a screen that is
            // mostly a stack of these, it reads as a column of stray marks.
            drawStopIndicator = {},
        )
        Text(
            // What is left, not what is done: it is the number that tells you whether to put
            // your shoes on.
            "${kmOf(progress.remainingM)} igjen  ·  ${kmOf(progress.totalM)} totalt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a row says out loud.
 *
 * Built separately from the visible string rather than reusing it: "·" is read as "dot", as
 * "middle dot", or skipped entirely depending on the engine, and "%" fares little better. The
 * percentage is rounded to a whole number here because a screen reader saying "førtito komma
 * én prosent" for every row in a list of a hundred and fifty is noise, not precision.
 */
private fun spokenRow(progress: Progress, note: String?): String = buildString {
    append(progress.name)
    append(", ")
    append((progress.fraction * 100).roundToInt())
    append(" prosent gått, ")
    append(kmOf(progress.remainingM).replace(" km", " kilometer"))
    append(" igjen av ")
    append(kmOf(progress.totalM).replace(" km", " kilometer"))
    if (note != null) {
        append(". ")
        append(note)
    }
}

/**
 * Why the list is empty, and a way out of it.
 *
 * Never a bare "ingen treff": the filter is named whenever it is on, because "why is the list
 * empty" is almost always the chip nobody remembers turning on rather than the thing they
 * just typed. And a filter that empties the list on its own is not a failure to apologise
 * for — it means the city is finished, which is the whole point of the app.
 */
@Composable
private fun EmptyState(
    query: String,
    filter: ProgressFilter,
    searching: Boolean,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (searching) "Ingen treff for «${query.trim()}»" else "Alt er gått.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                searching && filter == ProgressFilter.UNFINISHED ->
                    "Filteret «${ProgressFilter.UNFINISHED.label}» er også på."
                searching -> "Sjekk stavemåten, eller prøv et kortere søk."
                else -> "Hele byen er ferdig."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (searching || filter != ProgressFilter.ALL) {
            TextButton(
                onClick = onReset,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text("Nullstill søk og filter") }
        }
    }
}
