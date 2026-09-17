package no.gatelangs.app.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkedCodecTest {

    @Test
    fun `round trips an arbitrary set of ids`() {
        val ids = intArrayOf(3, 4, 5, 9, 20, 21, 22, 23, 100)
        assertContentEquals(ids, WalkedCodec.decode(WalkedCodec.encode(ids)))
    }

    @Test
    fun `collapses a run into start plus count`() {
        assertEquals("10+4", WalkedCodec.encode(intArrayOf(10, 11, 12, 13)))
    }

    @Test
    fun `writes a lone id without a count`() {
        assertEquals("7", WalkedCodec.encode(intArrayOf(7)))
    }

    @Test
    fun `handles an empty set`() {
        assertEquals("", WalkedCodec.encode(IntArray(0)))
        assertEquals(0, WalkedCodec.decode("").size)
        assertEquals(0, WalkedCodec.decode("   ").size)
    }

    @Test
    fun `sorts and round trips unordered input`() {
        val encoded = WalkedCodec.encode(intArrayOf(5, 1, 3, 2, 4))
        assertEquals("1+5", encoded)
        assertContentEquals(intArrayOf(1, 2, 3, 4, 5), WalkedCodec.decode(encoded))
    }

    @Test
    fun `stays compact for a realistic walk`() {
        // Walking streets produces long consecutive runs, which is the whole reason for
        // run-length encoding: localStorage caps out around 5 MB.
        val ids = (0 until 4000).filter { (it / 60) % 2 == 0 }.toIntArray()
        val encoded = WalkedCodec.encode(ids)
        assertContentEquals(ids, WalkedCodec.decode(encoded))
        assertTrue(
            encoded.length < ids.size,
            "encoding ${ids.size} ids took ${encoded.length} chars — no better than a plain list",
        )
    }

    @Test
    fun `ignores malformed runs rather than throwing`() {
        // Persisted state is the one input that arrives from a previous version of the
        // app, so it must never be able to crash startup.
        assertContentEquals(intArrayOf(1, 5), WalkedCodec.decode("1,,banana,5"))
        assertContentEquals(intArrayOf(9), WalkedCodec.decode("9+x"))
    }
}
