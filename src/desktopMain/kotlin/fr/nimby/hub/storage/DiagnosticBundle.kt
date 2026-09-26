package fr.nimby.hub.storage

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

/** Explicit support export. Only diagnostic files are eligible, never a whole
 * installation, profile, save or settings directory. No network transmission.
 * Logs can contain local paths/IDs; the user chooses whether to share the ZIP.
 */
object DiagnosticBundle {
    fun roots(hub: Path): Map<String, Path> = linkedMapOf(
        "logs" to DiagnosticPaths.root(),
        "legacy-hub" to hub.resolve("logs"),
        "legacy-sdk" to Path.of(System.getenv("LOCALAPPDATA") ?: "", "NimbyRailsFranceSDK"),
        "legacy-loader" to Path.of(System.getenv("LOCALAPPDATA") ?: "", "NimbyRailsSDK"))

    fun export(destination: Path, roots: Map<String, Path>, environment: String, technical: String? = null): Int {
        require(destination.extension.lowercase() == "zip" && !destination.exists()) { "Choisissez un nouveau fichier .zip" }
        val candidates = mutableListOf<Pair<String, Path>>()
        val notes = mutableListOf<String>()
        roots.forEach { (label, root) ->
            require(label.matches(Regex("[a-zA-Z0-9_-]+")))
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) notes += "Absent diagnostic directory: $label"
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (attrs.isOther || attrs.isSymbolicLink) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && !attrs.isOther && file.name.matches(Regex(".*\\.(?:log(?:\\.[1-5])?|jsonl)")))
                        candidates += "$label/${root.relativize(file).joinToString("/")}" to file
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    notes += "Unreadable: $label/${root.relativize(file)} (${error.javaClass.simpleName})"
                    return FileVisitResult.CONTINUE
                }
            })
        }
        val ordered = candidates.sortedByDescending { runCatching { it.second.getLastModifiedTime().toMillis() }.getOrDefault(0) }
        val target = destination.toAbsolutePath()
        target.parent.createDirectories()
        val temporary = Files.createTempFile(target.parent, ".nrf-diagnostics-", ".tmp")
        var count = 0
        var remaining = 64L * 1024 * 1024
        try {
            ZipOutputStream(temporary.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry("environment.txt", environment.toByteArray(Charsets.UTF_8))
                if (technical != null) entry("technical.json", technical.toByteArray(Charsets.UTF_8))
                for ((name, path) in ordered) {
                    if (count >= 128 || remaining <= 0) { notes += "Skipped (export limit): $name"; continue }
                    // Recheck after enumeration; do not follow a substituted link.
                    val bytes = runCatching {
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        require(attrs.isRegularFile && !attrs.isOther && !attrs.isSymbolicLink)
                        val maximum = minOf(8L * 1024 * 1024, remaining).toInt()
                        path.inputStream().use { input ->
                            val skip = (attrs.size() - maximum).coerceAtLeast(0)
                            if (skip > 0) { input.skipNBytes(skip); notes += "Tail only: $name ($skip bytes omitted)" }
                            input.readNBytes(maximum)
                        }
                    }.getOrElse { notes += "Unreadable: $name (${it.javaClass.simpleName})"; continue }
                    entry(name, bytes); remaining -= bytes.size; count++
                }
                entry("collection.txt", ("Collected $count diagnostic files. Logs may contain local paths and object IDs.\nNo save games or settings included.\n" + notes.joinToString("\n")).toByteArray(Charsets.UTF_8))
            }
            Files.move(temporary, target) // Never overwrite an existing support report.
        } finally { temporary.deleteIfExists() }
        return count
    }
}
