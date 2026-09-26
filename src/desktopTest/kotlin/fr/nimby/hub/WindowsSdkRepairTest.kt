package fr.nimby.hub

import fr.nimby.hub.platform.windows.WindowsSdkRepair
import fr.nimby.hub.model.hubJson
import fr.nimby.hub.storage.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class WindowsSdkRepairTest {
    @Test fun powershellErrorsKeepAccentsAndQuotedPaths() {
        if (!fr.nimby.hub.platform.Host.windows) return
        val root = Files.createTempDirectory("nrf-é-'proxy-")
        val game = root.resolve("game").createDirectory()
        game.resolve(WindowsSdkRepair.MANIFEST).writeText("{}")
        val sdk = root.resolve("sdk").createDirectory()
        sdk.resolve("loader").createDirectory().resolve("install-proxy.ps1")
            .writeText("\uFEFFparam(\u0024Action,\u0024GameDirectory,\u0024SourceDirectory)\nthrow 'Échec : bibliothèque déjà présente'")
        val failure = assertFails { fr.nimby.hub.platform.Windows().proxy(sdk, game, "Remove") }
        assertContains(failure.message.orEmpty(), "Échec : bibliothèque déjà présente")
        assertFalse(failure.message.orEmpty().contains("Ã"))
    }

    private class Fixture {
        val root = Files.createTempDirectory("nrf-repair-é-")
        val game = root.resolve("game").createDirectory()
        val data = root.resolve("hub").createDirectory()
        val original = game.resolve("NimbyRailsSDL3Original.dll").apply { writeText("original fixture SDL") }.sha256()
        init {
            game.resolve("NimbyRails.exe").writeText("fake executable, never executed")
            game.resolve("SDL3.dll").writeText("existing proxy")
            game.resolve("NimbyRailsFranceSDK.dll").writeText("old SDK")
            game.resolve("libwinpthread-1.dll").writeText("dependency")
            game.resolve("keep.txt").writeText("unrelated file")
            manifest()
        }
        fun manifest(override: Pair<String, String>? = null) {
            val values = linkedMapOf("format" to "2", "originalSdlSha256" to original,
                "executableSha256" to game.resolve("NimbyRails.exe").sha256(), "proxySha256" to game.resolve("SDL3.dll").sha256(),
                "sdkSha256" to game.resolve("NimbyRailsFranceSDK.dll").sha256(), "pthreadSha256" to game.resolve("libwinpthread-1.dll").sha256())
            if (override != null) values[override.first] = override.second
            game.resolve(WindowsSdkRepair.MANIFEST).writeText(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
        }
        fun repair(closed: (Path) -> Unit = {}, log: (String) -> Unit = {}) = WindowsSdkRepair(data, closed, log, original)
        fun snapshot() = game.listDirectoryEntries().associate { it.name to it.sha256() }
    }

    @Test fun verifiedProxyRestoresOriginalAndKeepsEveryBackup() {
        val f = Fixture(); val before = f.snapshot()
        val backup = f.repair().repair(f.game)
        assertEquals(f.original, f.game.resolve("SDL3.dll").sha256())
        assertEquals("unrelated file", f.game.resolve("keep.txt").readText())
        assertFalse(f.game.resolve(WindowsSdkRepair.MANIFEST).exists())
        assertFalse(f.data.resolve(WindowsSdkRepair.JOURNAL).exists())
        for ((name, sum) in before.filterKeys { it !in setOf("keep.txt", "NimbyRails.exe") }) assertEquals(sum, backup.resolve(name).sha256())
    }

    @Test fun unknownModifiedMissingOrUnsafeFilesNeverChangeGame() {
        for (kind in listOf("no-manifest", "dll", "backup", "game", "traversal", "extra", "running")) {
            val f = Fixture()
            when (kind) {
                "no-manifest" -> f.game.resolve(WindowsSdkRepair.MANIFEST).deleteExisting()
                "dll" -> f.game.resolve("SDL3.dll").writeText("foreign SDL")
                "backup" -> f.game.resolve("NimbyRailsSDL3Original.dll").writeText("not original")
                "game" -> f.game.resolve("NimbyRails.exe").writeText("new game")
                "traversal" -> f.manifest("pthreadFile" to "../outside.dll")
                "extra" -> f.game.resolve("NimbySignalUiBridge-experimental-v1.dll").writeText("unrecorded bridge")
            }
            val before = f.snapshot()
            assertFails(kind) { f.repair(closed = { check(kind != "running") { "Game running" } }).repair(f.game) }
            assertEquals(before, f.snapshot(), kind)
            assertFalse(f.data.resolve(WindowsSdkRepair.JOURNAL).exists(), kind)
        }
    }

    @Test fun interruptedRepairResumesButRejectsForeignChanges() {
        val f = Fixture()
        // Interrupt after the durable journal is written, before mutations.
        assertFails { f.repair(log = { if (it.startsWith("Sauvegarde de réparation")) error("simulated process interruption") }).repair(f.game) }
        val journal = f.data.resolve(WindowsSdkRepair.JOURNAL)
        assertTrue(journal.exists())
        val plan = hubJson.decodeFromString<WindowsSdkRepair.Journal>(journal.readText())
        val backup = Path(plan.backup)
        // Simulate process termination halfway through the removal sequence.
        f.game.resolve("SDL3.dll").writeBytes(backup.resolve("NimbyRailsSDL3Original.dll").readBytes())
        f.game.resolve("NimbyRailsFranceSDK.dll").deleteExisting()
        f.game.resolve("libwinpthread-1.dll").writeText("foreign replacement")
        val before = f.snapshot()
        assertFails { f.repair().repair(f.game) }
        assertEquals(before, f.snapshot()); assertTrue(journal.exists())
        f.game.resolve("libwinpthread-1.dll").writeBytes(backup.resolve("libwinpthread-1.dll").readBytes())
        f.repair().repair(f.game)
        assertEquals(f.original, f.game.resolve("SDL3.dll").sha256()); assertFalse(journal.exists())
    }

    @Test fun noManifestDiagnosticIncludesActualAndExpectedHash() {
        val f = Fixture(); f.game.resolve(WindowsSdkRepair.MANIFEST).deleteExisting()
        val error = assertFails { WindowsSdkRepair.requireClean(f.game) }
        assertContains(error.message.orEmpty(), f.game.resolve("SDL3.dll").sha256())
        assertContains(error.message.orEmpty(), WindowsSdkRepair.ORIGINAL_SDL)
        assertContains(error.message.orEmpty(), "Réparer le chargeur SDK")
    }
}
