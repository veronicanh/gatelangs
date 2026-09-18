package no.gatelangs.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A row with the numbers that matter and nothing else — arranging never asks for more. */
private fun row(name: String, walked: Double, total: Double = 100.0) = Progress(name, walked, total)

private fun List<Progress>.names() = map { it.name }

class SortOrderTest {

    @Test
    fun `tapping the sort key already in force flips its direction`() {
        val start = SortOrder(SortKey.PERCENT, descending = true)
        assertFalse(start.tapped(SortKey.PERCENT).descending)
        assertTrue(start.tapped(SortKey.PERCENT).tapped(SortKey.PERCENT).descending)
    }

    @Test
    fun `tapping a different sort key starts it at its own natural direction, not the last one`() {
        // The percent chip left pointing up must not drag the name chip into Å–A with it:
        // the direction belongs to the key, which is the only reading that lets a chip's
        // label be trusted before you have tapped it.
        val flipped = SortOrder(SortKey.PERCENT, descending = false)
        assertEquals(SortOrder(SortKey.NAME, descending = false), flipped.tapped(SortKey.NAME))
    }

    @Test
    fun `percent starts high to low, names start A to Å`() {
        assertTrue(SortOrder(SortKey.PERCENT).descending)
        assertFalse(SortOrder(SortKey.NAME).descending)
        assertEquals(SortKey.PERCENT, SortOrder.Default.key)
    }

    @Test
    fun `only the percent chip needs an arrow to say which way it runs`() {
        // The name chip says it in letters, which is why it does not get one.
        assertTrue(SortKey.PERCENT.needsArrow)
        assertFalse(SortKey.NAME.needsArrow)
        assertEquals("A–Å", SortKey.NAME.labelFor(descending = false))
        assertEquals("Å–A", SortKey.NAME.labelFor(descending = true))
        assertEquals("% fullført", SortKey.PERCENT.labelFor(descending = true))
    }
}

class SortingTest {

    @Test
    fun `sorting by percent runs both ways`() {
        val rows = listOf(row("A", 10.0), row("B", 90.0), row("C", 50.0))

        assertEquals(
            listOf("B", "C", "A"),
            rows.arrange(ProgressView(sort = SortOrder(SortKey.PERCENT, descending = true))).names(),
        )
        assertEquals(
            listOf("A", "C", "B"),
            rows.arrange(ProgressView(sort = SortOrder(SortKey.PERCENT, descending = false))).names(),
        )
    }

    @Test
    fun `the name has the last word on ties, so equal rows never swap places between passes`() {
        // Coverage is rebuilt every few seconds while tracking. Without a total order the
        // list would reshuffle under the reader's thumb for no reason they could see.
        val rows = listOf(row("Sidegata", 50.0), row("Bakgata", 50.0), row("Midtgata", 50.0))
        val once = rows.arrange(ProgressView()).names()
        val again = rows.reversed().arrange(ProgressView()).names()

        assertEquals(listOf("Bakgata", "Midtgata", "Sidegata"), once)
        assertEquals(once, again, "the same rows in a different order must arrange the same")
    }

    @Test
    fun `æ ø and å sort after z, in that order`() {
        val rows = listOf(row("Ås", 0.0), row("Øvre", 0.0), row("Ærlig", 0.0), row("Zebra", 0.0))
        assertEquals(
            listOf("Zebra", "Ærlig", "Øvre", "Ås"),
            rows.arrange(ProgressView(sort = SortOrder(SortKey.NAME))).names(),
        )
    }

    @Test
    fun `Østerdalsgata sorts under Ø, not under O`() {
        // The trap for anyone who reuses the search fold as the sort key: searching wants ø
        // to be an o, and an index emphatically does not.
        val rows = listOf(row("Oslogata", 0.0), row("Pilestredet", 0.0), row("Østerdalsgata", 0.0))
        assertEquals(
            listOf("Oslogata", "Pilestredet", "Østerdalsgata"),
            rows.arrange(ProgressView(sort = SortOrder(SortKey.NAME))).names(),
        )
    }

    @Test
    fun `ü is filed under y, not past å`() {
        val rows = listOf(row("Ålesundgata", 0.0), row("Bülowsgate", 0.0), row("Bakergata", 0.0))
        assertEquals(
            listOf("Bakergata", "Bülowsgate", "Ålesundgata"),
            rows.arrange(ProgressView(sort = SortOrder(SortKey.NAME))).names(),
        )
    }

    @Test
    fun `Gater uten navn sinks to the bottom whichever way the list runs`() {
        // It is the longest row in the snapshot and among the least walked, so on merit it
        // would own the top of "% fullført, lav til høy" for ever — which is the one order
        // someone planning a walk actually reads.
        val rows = listOf(
            row(UNNAMED_ROADS, 1.0, total = 9000.0),
            row("Bakgata", 50.0),
            row("Ålegata", 90.0),
        )

        for (key in SortKey.entries) {
            for (descending in listOf(true, false)) {
                val arranged = rows.arrange(ProgressView(sort = SortOrder(key, descending))).names()
                assertEquals(
                    UNNAMED_ROADS,
                    arranged.last(),
                    "$key descending=$descending should still pin the residual last",
                )
            }
        }
    }
}

class SearchKeyTest {

    @Test
    fun `Grünerløkka is found by typing gruner`() {
        // The marks with no key of their own on a Norwegian keyboard come off.
        assertTrue(row("Grünerløkka", 0.0).matches(searchKey("gruner")))
        assertTrue(row("Bygdøy allé", 0.0).matches(searchKey("alle")))
        assertTrue(row("Bülowsgate", 0.0).matches(searchKey("bulow")))
    }

    @Test
    fun `a is not å, o is not ø, and ae is not æ`() {
        // The three are letters, not accents. Folding them would answer "a" with every å in
        // Oslo, and file Ålesundgata under A — which is not where anyone would look for it.
        assertFalse(row("Ålesundgata", 0.0).matches(searchKey("alesund")))
        assertFalse(row("Østerdalsgata", 0.0).matches(searchKey("osterdals")))
        assertFalse(row("Bygdøy allé", 0.0).matches(searchKey("bygdoy")))
        assertFalse(row("Ærlig vei", 0.0).matches(searchKey("aerlig")))

        // Typed properly, they match — everyone writing these names has the keys.
        assertTrue(row("Ålesundgata", 0.0).matches(searchKey("ålesund")))
        assertTrue(row("Østerdalsgata", 0.0).matches(searchKey("østerdals")))
        assertTrue(row("Bygdøy allé", 0.0).matches(searchKey("bygdøy")))
    }

    @Test
    fun `søk matches whichever way the case falls`() {
        assertTrue(row("Østerdalsgata", 0.0).matches(searchKey("ØSTER")))
        assertTrue(row("ØSTERDALSGATA", 0.0).matches(searchKey("øster")))
    }

    @Test
    fun `an empty query matches everything rather than nothing`() {
        assertTrue(row("Parkveien", 0.0).matches(searchKey("")))
    }

    @Test
    fun `folding is one character for one, so the folded length never changes`() {
        // The property that keeps emboldening the matched span possible: an offset into the
        // folded name has to still be an offset into the name itself.
        val names = listOf("Grünerløkka", "Bygdøy allé", "Ålesundgata", "Ærlig vei", "Østerdalsgata")
        for (name in names) {
            assertEquals(name.length, searchKey(name).length, "folding $name changed its length")
        }
    }
}

class ProgressFilterTest {

    @Test
    fun `hiding fullførte keeps the one at ninety-nine per cent`() {
        val rows = listOf(row("Nesten", 99.0), row("Ferdig", 100.0))
        assertEquals(listOf("Nesten"), rows.arrange(ProgressView(filter = ProgressFilter.UNFINISHED)).names())
    }

    @Test
    fun `a row a whisker short of its own length still counts as fullført`() {
        // Coverage is a sum of floating point lengths, so a finished street lands just under
        // its own total often enough to matter. This row prints "100,0 %"; if the filter
        // disagreed with the number on screen the filter would look broken, not the maths.
        val row = row("Avrundet", 99.97, total = 100.0)
        assertTrue(row.isFinished)
        assertEquals(emptyList(), listOf(row).arrange(ProgressView(filter = ProgressFilter.UNFINISHED)))
    }

    @Test
    fun `tapping the filter already on turns it off`() {
        val on = ProgressView().toggleUnfinished()
        assertEquals(ProgressFilter.UNFINISHED, on.filter)
        assertEquals(ProgressFilter.ALL, on.toggleUnfinished().filter)
    }

    @Test
    fun `the residual bucket is not counted as a street`() {
        // It stands in for two dozen nameless ways and can never be finished, so counting it
        // would both inflate the denominator and cap the tally one short of the whole for ever.
        val rows = listOf(row("Bakgata", 100.0), row(UNNAMED_ROADS, 0.0))
        assertEquals(listOf("Bakgata"), rows.namedStreets().names())
    }
}

class DistrictSearchTest {

    /** Nothing walked: none of what follows depends on coverage, only on the shape of it. */
    private fun freshTown(): Pair<Coverage, RoadNetwork> {
        val network = TestTown.network()
        return Coverage(network) to network
    }

    @Test
    fun `streetsByDistrict accounts for every metre exactly once`() {
        // The property that lets the summary card be trusted against the breakdown below it.
        val (coverage, network) = freshTown()
        val byDistrict = coverage.streetsByDistrict(network)

        for ((name, streets) in byDistrict) {
            assertEquals(
                network.lengthOf(network.segmentsByDistrict.getValue(name)),
                streets.sumOf { it.totalM },
                1e-6,
                "the streets of $name should add up to the bydel itself",
            )
        }
    }

    @Test
    fun `a bydel is a hit when a street inside it is, and opens on just that street`() {
        val (coverage, network) = freshTown()
        val groups = arrangeDistricts(
            districts = coverage.byDistrict(network),
            streetsByDistrict = coverage.streetsByDistrict(network),
            view = ProgressView(),
            query = "sidegata",
        )

        val vest = groups.single()
        assertEquals("Vest", vest.district.name)
        assertTrue(vest.expandedByQuery, "a street hit should open the bydel without a tap")
        assertEquals(listOf("Sidegata"), vest.streets.names(), "only the match belongs under it")
        assertTrue(vest.totalStreets > vest.streets.size, "the row has to be able to say 1 av N")
    }

    @Test
    fun `a bydel matched by its own name stays closed and keeps all its streets`() {
        // There is no subset to show, and auto-opening a list of forty answers nothing.
        val (coverage, network) = freshTown()
        val groups = arrangeDistricts(
            districts = coverage.byDistrict(network),
            streetsByDistrict = coverage.streetsByDistrict(network),
            view = ProgressView(),
            query = "vest",
        )

        val vest = groups.single()
        assertEquals("Vest", vest.district.name)
        assertFalse(vest.expandedByQuery, "a name hit should keep its chevron")
        assertEquals(
            coverage.streetsByDistrict(network).getValue("Vest").size,
            vest.streets.size,
            "a name hit has no subset to show, so it keeps the whole bydel",
        )
    }

    @Test
    fun `the tally on a bydel row counts named streets only, and ignores the filter`() {
        // "hvor mange gater er her" must not change because you ticked "skjul fullførte" —
        // and the nameless bucket is not a street, so it is in neither half of the ratio.
        val (coverage, network) = freshTown()
        coverage.restore(network.segmentsByStreet.getValue("Sidegata"))

        for (filter in ProgressFilter.entries) {
            val vest = arrangeDistricts(
                districts = coverage.byDistrict(network),
                streetsByDistrict = coverage.streetsByDistrict(network),
                view = ProgressView(filter = filter),
            ).single { it.district.name == "Vest" }

            assertEquals(2, vest.totalStreets, "Parkveien and Sidegata, not the nameless rest")
            assertEquals(1, vest.finishedStreets, "only Sidegata has been walked end to end")
        }
    }

    @Test
    fun `a bydel matched both ways opens on the matching streets rather than all of them`() {
        // Sentrum contains a Sentrumsveien, so "sentrum" hits the bydel by name *and* a
        // street inside it. The street hit has to win, or the one row you were looking for
        // is buried in the forty you were not.
        val groups = arrangeDistricts(
            districts = listOf(row("Sentrum", 0.0, total = 200.0)),
            streetsByDistrict = mapOf(
                "Sentrum" to listOf(row("Sentrumsveien", 0.0), row("Sidegata", 0.0)),
            ),
            view = ProgressView(),
            query = "sentrum",
        )

        val sentrum = groups.single()
        assertTrue(sentrum.expandedByQuery, "a street hit must not be drowned by the name hit")
        assertEquals(listOf("Sentrumsveien"), sentrum.streets.names())
        assertEquals(2, sentrum.totalStreets, "the row still reports 1 av 2")
    }

    @Test
    fun `clearing the query puts every bydel back`() {
        val (coverage, network) = freshTown()
        val all = arrangeDistricts(
            districts = coverage.byDistrict(network),
            streetsByDistrict = coverage.streetsByDistrict(network),
            view = ProgressView(),
            query = "",
        )
        assertEquals(coverage.byDistrict(network).size, all.size)
        assertTrue(all.none { it.expandedByQuery }, "nothing is opened by an empty query")
    }

    @Test
    fun `a bydel with nothing matching is dropped rather than shown empty`() {
        val (coverage, network) = freshTown()
        val groups = arrangeDistricts(
            districts = coverage.byDistrict(network),
            streetsByDistrict = coverage.streetsByDistrict(network),
            view = ProgressView(),
            query = "finnesikke",
        )
        assertEquals(emptyList(), groups)
    }
}
