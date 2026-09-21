package fr.nimby.hub.install

import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission
import fr.nimby.hub.platform.Host
import kotlin.io.path.*

/** Never follows archive links, overwrites duplicate entries, or trusts expanded sizes. */
object Archives {
    fun extract(archive: Path, stage: Path, rootFolder: String) {
        val root = stage.toAbsolutePath().normalize()
        val prefix = "$rootFolder/"
        val names = mutableSetOf<String>()
        var count = 0
        var expanded = 0L
        ZipFile.builder().setPath(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= 30_000) { "Trop de fichiers dans l'archive" }
                val name = entry.name.replace('\\', '/')
                require(name.startsWith(prefix) && !entry.isUnixSymlink && zip.canReadEntryData(entry)) { "Archive invalide ou lien interdit" }
                val relative = name.removePrefix(prefix)
                if (relative.isEmpty()) continue
                val parts = relative.trimEnd('/').split('/')
                require(parts.all { segment ->
                    segment.isNotEmpty() && segment !in setOf(".", "..") &&
                        segment.none { it in ":<>\"|?*" || it.code < 32 } &&
                        !segment.endsWith('.') && !segment.endsWith(' ') &&
                        !Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?").matches(segment)
                }) { "Chemin d'archive dangereux" }
                val output = root.resolve(relative).normalize()
                require(output.startsWith(root) && output != root) { "Chemin hors du dossier de préparation" }
                require(names.add(relative.trimEnd('/').lowercase())) { "Entrée d'archive dupliquée" }
                if (entry.isDirectory) { output.createDirectories(); continue }
                output.parent.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(output, CREATE_NEW).use { target ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val size = input.read(bytes)
                            if (size < 0) break
                            expanded += size
                            require(expanded <= 2_147_483_648L) { "Archive décompressée supérieure à 2 Go" }
                            target.write(bytes, 0, size)
                        }
                    }
                }
                if (!Host.windows && entry.unixMode and 0b001001001 != 0) {
                    // ZIPs must preserve the launcher and bundled Java helpers.
                    // Only grant execution to the installing user; never copy
                    // setuid/setgid or world-writable archive permissions.
                    Files.setPosixFilePermissions(output, Files.getPosixFilePermissions(output) + PosixFilePermission.OWNER_EXECUTE)
                }
            }
        }
    }
}
