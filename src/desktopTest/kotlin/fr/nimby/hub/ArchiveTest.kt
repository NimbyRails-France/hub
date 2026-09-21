package fr.nimby.hub

import fr.nimby.hub.install.Archives
import fr.nimby.hub.model.*
import fr.nimby.hub.storage.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ArchiveTest {
    @Test fun preservesUnixLaunchersWithoutPrivilegedArchivePermissions() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("nrf-zip-executable-")
        try {
            val archive = root.resolve("app.zip")
            ZipArchiveOutputStream(archive).use { output ->
                val entry = ZipArchiveEntry("Fixture/bin/start").apply { unixMode = 0x89ED } // regular, setuid, 0755
                output.putArchiveEntry(entry); output.write("#!/bin/sh\nexit 0\n".toByteArray()); output.closeArchiveEntry()
            }
            val stage = root.resolve("stage").createDirectory()
            Archives.extract(archive, stage, "Fixture")
            val launcher = stage.resolve("bin/start")
            assertTrue(Files.isExecutable(launcher))
            assertEquals(0, (Files.getAttribute(launcher, "unix:mode") as Int) and 0xC00)
            assertEquals(0, ProcessBuilder(launcher.toString()).start().waitFor())
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun rejectsSymbolicLinksAndCaseCollisions() {
        val root = Files.createTempDirectory("nrf-zip-links-")
        try {
            val zip = root.resolve("links.zip")
            ZipArchiveOutputStream(zip).use { output ->
                val entry = ZipArchiveEntry("Fixture/link").apply { unixMode = 0xA1FF }
                output.putArchiveEntry(entry); output.write("../outside".toByteArray()); output.closeArchiveEntry()
            }
            val stage = root.resolve("stage").createDirectory()
            assertFails { Archives.extract(zip, stage, "Fixture") }
            ZipOutputStream(zip.outputStream()).use { output ->
                for (name in listOf("Fixture/Thing.txt", "Fixture/thing.txt")) {
                    output.putNextEntry(ZipEntry(name)); output.write(1); output.closeEntry()
                }
            }
            assertFails { Archives.extract(zip, stage, "Fixture") }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun archive(root: Path, name: String): Path = root.resolve("fixture.zip").also { path ->
        ZipOutputStream(path.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write("fixture".toByteArray()); zip.closeEntry() }
    }
    @Test fun safeExtractionAndTraversalRejection() {
        val root = Files.createTempDirectory("nrf-archive-test-")
        try {
            val stage = root.resolve("stage").createDirectory()
            Archives.extract(archive(root, "Fixture/data.txt"), stage, "Fixture")
            assertEquals("fixture", stage.resolve("data.txt").readText())
            for (name in listOf("Fixture/../escape.txt", "Fixture/C:/evil", "Other/data", "Fixture/sub/../../evil", "Fixture/CON", "Fixture/data:stream", "Fixture/a./evil")) {
                assertFails("Should reject $name") { Archives.extract(archive(root, name), stage, "Fixture") }
            }
            assertFalse(root.resolve("escape.txt").exists())
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun profileBOMAndInstalledRecordPreserved() {
        val root = Files.createTempDirectory("nrf-settings-test-")
        try {
            root.resolve("settings.json").writeText("\uFEFF" + """{"installed":{"sdk":{"id":"sdk","kind":"sdk","version":"0.7.3","directory":"C:/sdk","loaderApi":1}},"automatic":false}""")
            val store = SettingsStore(root)
            val before = store.read()
            store.write(before.copy(developerMode = true))
            val after = store.read()
            assertTrue(after.developerMode)
            assertFalse(after.automatic)
            assertEquals(before.installed, after.installed)
            root.resolve("settings.json").writeText("not json")
            assertFails { store.read() }
            assertEquals("not json", root.resolve("settings.json").readText())
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
