package no.gatelangs.app.model

/**
 * A road OSM never named, as one road.
 *
 * Distinct from [UNNAMED_ROADS], which is the city-wide bucket every unnamed road is
 * summed into on the progress screen. The two carry different numbers and must not be
 * confused: standing on one unnamed stretch is not standing on all of them.
 */
const val UNNAMED_ROAD = "Gate uten navn"

/**
 * Where the walker is: names only, no measurements.
 *
 * Names rather than percentages because this is held in a `mutableStateOf` and reassigned
 * on every fix. Walking 300 m down Parkveien is fifteen different segment ids but one
 * unchanged Whereabouts, so structural equality makes the assignment a no-op and the map's
 * readout does not recompose once a second for pixels that never move. The percentages are
 * derived where they are drawn, keyed on the coverage revision.
 *
 * [wayId] carries the road's identity only when it has no name. OSM splits Trondheimsveien
 * into 27 ways, and keeping the way id for named streets would make every seam between two
 * of them look like a change of location.
 */
data class Whereabouts(
    val street: String?,
    val wayId: Long?,
    val district: String?,
)

/**
 * Where the last fix put the walker, or null when it put them nowhere.
 *
 * Null rather than an empty Whereabouts so "off the road network" is one value the caller
 * cannot forget to check, and so the readout has a single thing to collapse on.
 */
fun Coverage.whereabouts(network: RoadNetwork): Whereabouts? {
    val id = currentSegment
    if (id !in network.segments.indices) return null

    val districtIndex = network.districtOfSegment[id]
    val district = if (districtIndex < 0) null else network.districts[districtIndex].name

    val street = network.streetNameOf(id)
    return Whereabouts(
        street = street,
        wayId = if (street == null) network.segments[id].wayId else null,
        district = district,
    )
}

/**
 * How far along the whole street under the walker is.
 *
 * The whole street city-wide, every OSM way carrying the name — not the stretch inside the
 * bydel they happen to be standing in. A street has one completion figure wherever on it
 * you are, and being told Markveien is 64% done should not change because you crossed a
 * boundary halfway up it.
 *
 * An unnamed road falls back to its own way, which is the largest honest unit available.
 */
fun Coverage.streetProgress(network: RoadNetwork, where: Whereabouts): Progress? {
    val name = where.street
    if (name != null) {
        val ids = network.segmentsByStreet[name] ?: return null
        return progressOf(network, name, ids)
    }
    val wayId = where.wayId ?: return null
    val ids = network.segmentsByWay[wayId] ?: return null
    return progressOf(network, UNNAMED_ROAD, ids)
}

/** How far along the bydel under the walker is, or null if they are in none. */
fun Coverage.districtProgress(network: RoadNetwork, name: String?): Progress? {
    val ids = network.segmentsByDistrict[name ?: return null] ?: return null
    return progressOf(network, name, ids)
}
