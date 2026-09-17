package no.gatelangs.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class Storage actual constructor() {

    private val directory = File(System.getProperty("user.home"), ".gatelangs")

    actual suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val file = File(directory, "$key.txt")
        if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    actual suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                File(directory, "$key.txt").writeText(value)
            }
        }
    }
}
