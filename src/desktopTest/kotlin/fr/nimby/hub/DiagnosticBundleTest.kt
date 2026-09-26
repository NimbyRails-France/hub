package fr.nimby.hub

import fr.nimby.hub.storage.DiagnosticBundle
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*

class DiagnosticBundleTest {
    @Test fun includesRuntimeAndHubLogsWithoutProfilesSavesOrOverwrite() {
        val root = Files.createTempDirectory("nrf-bundle-")
        val logs = root.resolve("logs").createDirectory()
        logs.resolve("tco.log").writeText("échec TCO")
        logs.resolve("sdk.log.1").writeText("SDK précédent")
        logs.resolve("settings.json").writeText("private settings")
        logs.resolve("world.save").writeText("private save")
        val zip = root.resolve("support.zip")
        assertEquals(2, DiagnosticBundle.export(zip, mapOf("runtime" to logs), "TCO 0.6.0", "{\"schema\":1}"))
        ZipFile(zip.toFile()).use { archive ->
            assertEquals(setOf("technical.json", "environment.txt", "runtime/tco.log", "runtime/sdk.log.1", "collection.txt"), archive.entries().asSequence().map { it.name }.toSet())
            assertEquals("échec TCO", archive.getInputStream(archive.getEntry("runtime/tco.log")).reader(Charsets.UTF_8).readText())
        }
        assertFails { DiagnosticBundle.export(zip, mapOf("runtime" to logs), "overwrite") }
    }
}
