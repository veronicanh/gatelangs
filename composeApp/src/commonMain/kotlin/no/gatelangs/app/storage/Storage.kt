package no.gatelangs.app.storage

/**
 * A tiny key-value store.
 *
 * Deliberately not SQLDelight: it has no usable Wasm driver, and the only thing worth
 * persisting here is a set of integers. Thirty lines of expect/actual beats a database
 * dependency that only works on half the targets.
 */
expect class Storage() {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

/**
 * Packs walked segment ids into a compact string.
 *
 * Runs of consecutive walked segments are the norm — you walk a street, not scattered
 * pieces of one — so run-length encoding as `start+count` turns thousands of ids into a
 * few hundred characters. That matters because localStorage caps out around 5 MB.
 */
object WalkedCodec {

    fun encode(ids: IntArray): String {
        if (ids.isEmpty()) return ""
        val sorted = ids.sortedArray()
        val out = StringBuilder()
        var runStart = sorted[0]
        var runLength = 1

        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) {
                runLength++
            } else {
                out.appendRun(runStart, runLength)
                runStart = sorted[i]
                runLength = 1
            }
        }
        out.appendRun(runStart, runLength)
        return out.toString()
    }

    fun decode(encoded: String): IntArray {
        if (encoded.isBlank()) return IntArray(0)
        val out = ArrayList<Int>()
        for (run in encoded.split(',')) {
            if (run.isBlank()) continue
            val plus = run.indexOf('+')
            val start = (if (plus < 0) run else run.substring(0, plus)).toIntOrNull() ?: continue
            val count = if (plus < 0) 1 else run.substring(plus + 1).toIntOrNull() ?: 1
            for (offset in 0 until count) out.add(start + offset)
        }
        return out.toIntArray()
    }

    private fun StringBuilder.appendRun(start: Int, length: Int) {
        if (isNotEmpty()) append(',')
        append(start)
        if (length > 1) append('+').append(length)
    }
}
