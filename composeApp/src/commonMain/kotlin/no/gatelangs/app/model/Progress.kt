package no.gatelangs.app.model

/** Streets with no name in OSM, gathered into one row so the totals still add up. */
const val UNNAMED_ROADS = "Gater uten navn"

/** One row of the breakdown: something with a name, and how much of it has been walked. */
data class Progress(
    val name: String,
    val walkedM: Double,
    val totalM: Double,
) {
    val fraction: Double get() = if (totalM <= 0.0) 0.0 else walkedM / totalM

    val remainingM: Double get() = (totalM - walkedM).coerceAtLeast(0.0)
}

/**
 * Most finished first, and among equals the longest first.
 *
 * Ranking by fraction rather than by metres walked is what makes the list answer "how
 * far have I come" — a long street half done sits above a short one barely started,
 * which is not what raw distance would tell you.
 */
private val MOST_COMPLETE = compareByDescending<Progress> { it.fraction }
    .thenByDescending { it.totalM }

internal fun Coverage.progressOf(network: RoadNetwork, name: String, ids: IntArray) =
    Progress(name = name, walkedM = walkedLengthOf(ids), totalM = network.lengthOf(ids))

/** Progress for each bydel in the network. */
fun Coverage.byDistrict(network: RoadNetwork): List<Progress> =
    network.segmentsByDistrict
        .map { (name, ids) -> progressOf(network, name, ids) }
        .sortedWith(MOST_COMPLETE)

/** Progress for every named street in the network, plus one row for the unnamed rest. */
fun Coverage.byStreet(network: RoadNetwork): List<Progress> =
    progressByStreet(network, network.segments.indices.toList())

/**
 * The streets of one bydel.
 *
 * Grouped from that bydel's own segments rather than by looking each street up whole, so
 * a street running through two bydeler appears in both, each time reporting only the part
 * that is actually there.
 */
fun Coverage.streetsIn(network: RoadNetwork, district: String): List<Progress> =
    progressByStreet(network, network.segmentsByDistrict[district]?.toList().orEmpty())

private fun Coverage.progressByStreet(network: RoadNetwork, ids: List<Int>): List<Progress> =
    ids.groupBy { network.streetNameOf(it) ?: UNNAMED_ROADS }
        .map { (name, segments) -> progressOf(network, name, segments.toIntArray()) }
        .sortedWith(MOST_COMPLETE)
