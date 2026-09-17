package no.gatelangs.app.model

/**
 * A point in a street's completion worth saying something about.
 *
 * Ordered least to most, and the order is load-bearing: [Milestones] compares by
 * [ordinal] to decide whether a street has moved up, so a street can never be
 * congratulated twice for the same thing or congratulated backwards.
 */
enum class Milestone(val fraction: Double, val title: String) {
    HALFWAY(0.5, "Halfway"),
    NEARLY(0.8, "Nearly there"),
    CLEARED(1.0, "Street cleared"),
    ;

    /** Whether this is the one worth interrupting for. */
    val isCleared: Boolean get() = this == CLEARED
}

/** One street reaching one milestone, once. */
data class Achievement(val street: String, val milestone: Milestone)

/**
 * Watches streets cross their milestones as coverage lands.
 *
 * Separate from [Coverage] on purpose. Coverage answers what has been walked and has to
 * stay cheap enough to run on every GPS fix; this answers what is worth saying about it,
 * and needs to remember what it has already said. Mixing the two would mean the
 * persistence layer had to carry announcement history too.
 *
 * Streets only — OSM leaves plenty of road unnamed, and "Unnamed roads 50%" is not an
 * achievement, it is a category.
 */
class Milestones(private val network: RoadNetwork) {

    /** The highest milestone each street has already been credited with. */
    private val reached = mutableMapOf<String, Milestone>()

    /**
     * Records where every street already stands, announcing nothing.
     *
     * Called after restoring saved coverage. Without it, reopening the app part way
     * through the city would fire a popup for every street already past halfway — the
     * milestones are about crossing a line, not about being over it.
     */
    fun seed(coverage: Coverage) {
        reached.clear()
        for (street in network.segmentsByStreet.keys) {
            milestoneFor(coverage.fractionOfStreet(street))?.let { reached[street] = it }
        }
    }

    /**
     * What [newlyWalked] just earned, in the order it was earned.
     *
     * Only the streets those segments belong to are re-measured, so this stays a handful
     * of array sums per fix rather than a sweep of the city. A street that jumps straight
     * from a third to finished reports [Milestone.CLEARED] alone — the two it skipped are
     * marked as reached but not announced, because three popups for one step is noise.
     */
    fun check(coverage: Coverage, newlyWalked: IntArray): List<Achievement> {
        if (newlyWalked.isEmpty()) return emptyList()

        val touched = LinkedHashSet<String>()
        for (id in newlyWalked) network.streetNameOf(id)?.let(touched::add)

        val earned = mutableListOf<Achievement>()
        for (street in touched) {
            val now = milestoneFor(coverage.fractionOfStreet(street)) ?: continue
            val before = reached[street]
            if (before != null && before.ordinal >= now.ordinal) continue
            reached[street] = now
            earned += Achievement(street, now)
        }
        return earned
    }

    private fun milestoneFor(fraction: Double): Milestone? = when {
        fraction >= CLEARED_ENOUGH -> Milestone.CLEARED
        fraction >= Milestone.NEARLY.fraction -> Milestone.NEARLY
        fraction >= Milestone.HALFWAY.fraction -> Milestone.HALFWAY
        else -> null
    }

    companion object {
        /**
         * Close enough to all of it.
         *
         * Coverage credits whole segments, so a finished street really has walked every
         * one — but the fraction is a sum of floating point lengths over another sum of
         * the same, and insisting it equal 1.0 exactly is asking for a street that is
         * never quite done. A tenth of a percent of any real street is well under a metre.
         */
        const val CLEARED_ENOUGH = 0.999
    }
}
