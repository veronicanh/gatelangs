package no.gatelangs.app.model

import no.gatelangs.app.geo.LatLon
import no.gatelangs.app.geo.MetricProjection
import no.gatelangs.app.geo.Vec2

/**
 * The little metric town the model tests are written against.
 *
 * An object rather than top-level functions on purpose: three other test files in this
 * package each keep their own file-private `at`, `box` and `town`, and adding
 * package-visible ones of the same name next door is the sort of shadowing that compiles
 * until somebody deletes the private one. `TestTown.network()` can only mean this.
 */
internal object TestTown {

    private val ORIGIN = LatLon(59.9139, 10.7522)
    private val PROJECTION = MetricProjection(ORIGIN)

    fun at(east: Double, north: Double): LatLon = PROJECTION.unproject(Vec2(east, north))

    /**
     * Parkveien arrives as two OSM ways, the way a real street does — the point of most of
     * what is asserted against this. Plus a side street, and a stretch OSM never named.
     *
     * Deliberately small: every metre of it is accounted for by hand in at least one
     * assertion, which is what makes a failure point at the code rather than at the fixture.
     */
    fun network(): RoadNetwork = RoadNetwork.from(
        ways = listOf(
            RawWay(1L, "Parkveien", listOf(at(0.0, 0.0), at(200.0, 0.0))),
            RawWay(2L, "Parkveien", listOf(at(200.0, 0.0), at(400.0, 0.0))),
            RawWay(3L, "Sidegata", listOf(at(0.0, 100.0), at(100.0, 100.0))),
            RawWay(4L, null, listOf(at(0.0, 200.0), at(50.0, 200.0))),
        ),
        districts = listOf(
            District("Vest", listOf(box(west = -50.0, east = 200.0))),
            District("Øst", listOf(box(west = 200.0, east = 500.0))),
        ),
    )

    /** A rectangle from [west] to [east], tall enough to hold every street in [network]. */
    fun box(west: Double, east: Double): List<LatLon> = listOf(
        at(west, -50.0),
        at(east, -50.0),
        at(east, 300.0),
        at(west, 300.0),
        at(west, -50.0),
    )
}
