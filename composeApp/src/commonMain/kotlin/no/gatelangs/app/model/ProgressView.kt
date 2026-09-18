package no.gatelangs.app.model

/** The two ways of slicing the same coverage. */
enum class Grouping(val label: String) {
    DISTRICT("Bydeler"),
    STREET("Gater"),
}

/**
 * What the breakdown is ranked by, and which way round each one naturally runs.
 *
 * "Naturally" is the point of the defaults: asked to sort by how finished something is you
 * mean the finished ones first, asked to sort by name you mean A first. Starting both
 * descending would make one of the two wrong on the very first tap.
 */
enum class SortKey(val defaultDescending: Boolean) {
    PERCENT(defaultDescending = true),
    NAME(defaultDescending = false),
}

/**
 * What the chip says.
 *
 * The name chip carries its direction in the label itself — "A–Å" and "Å–A" are the two
 * things it can mean, and no arrow explains that better than the letters do. The percent
 * chip cannot: "% fullført" is the same phrase either way round, so that one takes an
 * arrow, and [directionLabel] is what the arrow is read out as.
 */
fun SortKey.labelFor(descending: Boolean): String = when (this) {
    SortKey.PERCENT -> "% fullført"
    SortKey.NAME -> if (descending) "Å–A" else "A–Å"
}

/** Whether the arrow is the only thing distinguishing this key's two directions. */
val SortKey.needsArrow: Boolean get() = this == SortKey.PERCENT

/** Spoken in place of the bare glyph: the arrow *is* the difference, so it must be said. */
fun SortKey.directionLabel(descending: Boolean): String = when (this) {
    SortKey.PERCENT -> if (descending) "høy til lav" else "lav til høy"
    SortKey.NAME -> if (descending) "Å til A" else "A til Å"
}

/** A [SortKey] and which way round it runs. */
data class SortOrder(val key: SortKey, val descending: Boolean = key.defaultDescending) {
    /**
     * One tap on a chip.
     *
     * The chip already in force flips; any other takes over at its own natural direction.
     * That is the whole interaction — no menu, and no second control for direction.
     */
    fun tapped(next: SortKey): SortOrder =
        if (next == key) copy(descending = !descending) else SortOrder(next)

    companion object {
        val Default = SortOrder(SortKey.PERCENT)
    }
}

/** Which rows are worth showing at all. */
enum class ProgressFilter(val label: String) {
    ALL("Alle"),
    UNFINISHED("Skjul fullførte"),
}

/** How the breakdown screen is currently sliced, ranked and narrowed. */
data class ProgressView(
    val grouping: Grouping = Grouping.DISTRICT,
    val sort: SortOrder = SortOrder.Default,
    val filter: ProgressFilter = ProgressFilter.ALL,
) {
    fun sortedBy(key: SortKey) = copy(sort = sort.tapped(key))

    fun groupedBy(grouping: Grouping) = copy(grouping = grouping)

    /** A filter chip is a toggle: tapping the one already on turns it off rather than nothing. */
    fun toggleUnfinished() = copy(
        filter = if (filter == ProgressFilter.UNFINISHED) ProgressFilter.ALL else ProgressFilter.UNFINISHED,
    )
}

/**
 * The point at which a row reads "100,0 %".
 *
 * Tied to what the screen prints rather than to 1.0, because coverage is a sum of a few
 * thousand doubles and the last segment of a finished street lands a whisker short of its
 * own length often enough to matter. "Skjul fullførte" leaving a row on screen that says
 * 100,0 % — or hiding one that does not — is the bug this number exists to prevent.
 *
 * Deliberately not [Milestones.Companion.CLEARED_ENOUGH], which is the same idea doing a
 * different job: that one decides when to congratulate you and wants to fire a hair early.
 * This one decides what you can see, and has to agree with the printed number exactly.
 */
private const val FINISHED_FRACTION = 0.9995

val Progress.isFinished: Boolean get() = fraction >= FINISHED_FRACTION

fun ProgressFilter.accepts(row: Progress): Boolean =
    this == ProgressFilter.ALL || !row.isFinished

/**
 * Folds one character to the letter someone hunting for it would type.
 *
 * **æ, ø and å are not in here, and must never be.** They are letters of the alphabet, not
 * accented forms of a and o — Ålesundgata does not begin with an A any more than Bergen
 * begins with an A, and a search that answered "a" with every å in Oslo would be answering a
 * question nobody asked. Everyone typing Norwegian street names has the three keys.
 *
 * What *is* folded is the marks that have no key of their own here: ü, é, ô, ç and their
 * relatives, so "gruner" still finds Grünerløkka. ö and ä go with them — in the languages
 * that use them those are a and o wearing a diaeresis, the same as ü, which is the one thing
 * separating them from the three letters above.
 *
 * One character in, one out, never "ae" for "æ", so an offset into the folded string is still
 * an offset into the original. That is what keeps emboldening the matched part of a name a
 * later possibility rather than an off-by-two.
 */
private fun fold(c: Char): Char = when (c) {
    'ü' -> 'u'
    'ö' -> 'o'
    'ä' -> 'a'
    'é', 'è', 'ê', 'ë' -> 'e'
    'á', 'à', 'â' -> 'a'
    'ó', 'ò', 'ô' -> 'o'
    'í', 'ì', 'î' -> 'i'
    'ú', 'ù', 'û' -> 'u'
    'ç' -> 'c'
    else -> c
}

/**
 * The form a name is matched in: lowercased, and stripped of the marks.
 *
 * `lowercase()` without a locale is the right call rather than a shortcut — Kotlin's common
 * one is locale-invariant Unicode case mapping, so Æ Ø Å fold the same on the JVM and in the
 * browser, and there is no Turkish-İ to trip over.
 */
internal fun searchKey(text: String): String {
    val lower = text.lowercase()
    return buildString(lower.length) { for (c in lower) append(fold(c)) }
}

/** Whether [foldedQuery] — already through [searchKey] — appears in this row's name. */
fun Progress.matches(foldedQuery: String): Boolean =
    foldedQuery.isEmpty() || searchKey(name).contains(foldedQuery)

/**
 * Sorts the way a Norwegian index does: æ, ø and å after z, in that order, and ü filed
 * under y.
 *
 * Kotlin compares strings by UTF-16 code unit, which already puts the three after z — they
 * are U+00E6, U+00F8 and U+00E5 — and then gets their order wrong among themselves, å, æ, ø
 * rather than æ, ø, å. The three sentinels are the characters immediately above 'z'.
 *
 * Deliberately *not* [searchKey]: folding ø to o for the sort would file Østerdalsgata under
 * O, which is where it emphatically does not belong.
 */
internal fun collationKey(name: String): String {
    val lower = name.lowercase()
    return buildString(lower.length) {
        for (c in lower) {
            append(
                when (c) {
                    'æ' -> '{' // 0x7B, one past 'z'
                    'ø' -> '|' // 0x7C
                    'å' -> '}' // 0x7D
                    'ü' -> 'y' // Grünerløkka files with the y's, not past å
                    else -> fold(c)
                }
            )
        }
    }
}

/**
 * "Gater uten navn" is not a place you can go, so it never competes for a position.
 *
 * Ranked on merit it would own the top of "% fullført, lav til høy" permanently — it is the
 * longest row in the snapshot and among the least walked — and that is the order someone
 * planning a walk actually uses. Pinned last it still shows its kilometres; it just stops
 * standing in front of the answer.
 */
internal val Progress.isResidual: Boolean get() = name == UNNAMED_ROADS

fun SortOrder.comparator(): Comparator<Progress> {
    val byKey: Comparator<Progress> = when (key) {
        SortKey.PERCENT -> compareBy { it.fraction }
        SortKey.NAME -> compareBy { collationKey(it.name) }
    }
    // The name has the last word on every tie, so the order never depends on what order the
    // rows happened to arrive in — which is what makes any of this testable at all.
    return compareBy<Progress> { if (it.isResidual) 1 else 0 }
        .then(if (descending) byKey.reversed() else byKey)
        .thenBy { collationKey(it.name) }
}

/**
 * The rows of one list, narrowed to what was asked for and put in the chosen order.
 *
 * Takes rows rather than the network on purpose: this runs on every keystroke, and
 * re-deriving four thousand segments per letter is the one thing that would make typing feel
 * slow. At a hundred and fifty rows there is nothing here worth debouncing.
 */
fun List<Progress>.arrange(view: ProgressView, query: String = ""): List<Progress> {
    val folded = searchKey(query.trim())
    return filter { view.filter.accepts(it) && it.matches(folded) }
        .sortedWith(view.sort.comparator())
}

/**
 * The rows that count toward "38 av 149 gater ferdig".
 *
 * The residual bucket is one row standing in for two dozen nameless ways, so counting it as
 * a street would be wrong twice: it inflates the denominator, and it can never be finished,
 * which would cap the tally one short of the whole for ever.
 */
fun List<Progress>.namedStreets(): List<Progress> = filter { !it.isResidual }

/** One bydel and the streets under it that survived the same narrowing. */
data class DistrictGroup(
    val district: Progress,
    val streets: List<Progress>,
    /**
     * Opened by the query rather than by a tap. True only when streets *inside* it matched:
     * [streets] is then just those, and the row must say so rather than offer a chevron.
     */
    val expandedByQuery: Boolean,
    /**
     * Named streets in the bydel before any narrowing — the denominator for both the tally
     * on the row and the "3 av 42 gater matcher" line. Counted before the filter on purpose:
     * "hvor mange gater er her" must not change because you ticked "skjul fullførte".
     */
    val totalStreets: Int,
    /** How many of those are walked end to end. */
    val finishedStreets: Int,
)

/**
 * Bydeler worth showing for [query], each carrying the streets worth showing.
 *
 * Three cases, and the middle one is the requirement: a bydel is a hit when its own name
 * matches, *or* when any street inside it does. Typing "park" turns up Frogner because
 * Parkveien is in it, already open on Parkveien alone — nobody should have to know which tab
 * a street lives on before they can look for it.
 *
 * A bydel matched only by its own name keeps all its streets and stays closed, because there
 * is no subset to show and an auto-opened list of forty is not an answer to anything.
 */
fun arrangeDistricts(
    districts: List<Progress>,
    streetsByDistrict: Map<String, List<Progress>>,
    view: ProgressView,
    query: String = "",
): List<DistrictGroup> {
    val folded = searchKey(query.trim())
    val order = view.sort.comparator()
    return districts
        .mapNotNull { district ->
            val all = streetsByDistrict[district.name].orEmpty()
            val named = all.namedStreets()
            val streets = all.filter(view.filter::accepts)
            val hits = if (folded.isEmpty()) emptyList() else streets.filter { it.matches(folded) }
            val shown = when {
                hits.isNotEmpty() -> hits
                district.matches(folded) && view.filter.accepts(district) -> streets
                else -> return@mapNotNull null
            }
            DistrictGroup(
                district = district,
                streets = shown.sortedWith(order),
                expandedByQuery = hits.isNotEmpty(),
                totalStreets = named.size,
                finishedStreets = named.count { it.isFinished },
            )
        }
        .sortedWith(compareBy(order) { it.district })
}
