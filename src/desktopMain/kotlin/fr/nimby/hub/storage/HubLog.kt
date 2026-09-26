package fr.nimby.hub.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import kotlin.io.path.*

/** Each entry is closed immediately: errors survive a crash or a normal exit.
 * Rotation bounds disk use. This writer belongs to the single Hub instance;
 * headless manager processes use manager.log in the shared hub log directory.
 */
class HubLog(val directory: Path, private val name: String = "hub.log", private val maximumBytes: Long = 2 * 1024 * 1024,
             private val archives: Int = 5) {
    val file: Path = directory.resolve(name)

    @Synchronized fun append(message: String, failure: Throwable? = null): String? = try {
        directory.createDirectories()
        val entry = buildString {
            append(Instant.now()).append(" pid=").append(ProcessHandle.current().pid())
                .append(" thread=").append(Thread.currentThread().name).append("  ").append(message).append('\n')
            if (failure != null) append(failure.stackTraceToString()).append('\n')
        }.toByteArray(Charsets.UTF_8)
        if (file.exists() && file.fileSize() + entry.size > maximumBytes) {
            directory.resolve("$name.$archives").deleteIfExists()
            for (index in archives - 1 downTo 1) {
                val source = directory.resolve("$name.$index")
                if (source.exists()) Files.move(source, directory.resolve("$name.${index + 1}"), REPLACE_EXISTING)
            }
            Files.move(file, directory.resolve("$name.1"), REPLACE_EXISTING)
        }
        Files.write(file, entry, CREATE, APPEND)
        null
    } catch (error: Exception) {
        // Logging must not interrupt filesystem rollback. Surface the failure in
        // the UI/console instead of claiming the diagnostic was saved.
        "Journal non enregistré ($file) : ${error.message}".also(System.err::println)
    }
}
