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
    private val dlls = listOf("NimbyRailsFranceSDK.dll", "libwinpthread-1.dll",
        "NimbyRailsFranceTextureBridge-experimental-v4.dll", "NimbySignalUiBridge-experimental-v1.dll",
        "NimbyAutomaticDrivingBridge-v1.dll", "NimbyConstructionBridge-experimental-v1.dll",
        "NimbyRailsFranceClockBridge-0.7.1.dll", "NimbyModMetadataBridge-v1.dll")

    @Test fun preexistingDllsAreAcceptedOnlyWhenIdenticalToDistribution() {
        val root = Files.createTempDirectory("nrf-preflight-")
        val game = root.resolve("game").createDirectory()
        val loader = root.resolve("loader").createDirectory()
        game.resolve("SDL3.dll").writeText("original")
        val original = game.resolve("SDL3.dll").sha256()
        game.resolve("unrelated.dll").writeText("foreign unrelated DLL")
        for (name in dlls) {
            loader.resolve(name).writeText("distribution $name")
            game.resolve(name).writeBytes(loader.resolve(name).readBytes())
            WindowsSdkRepair.requireClean(game, loader, original)
            game.resolve(name).writeText("different DLL")
            val error = assertFails { WindowsSdkRepair.requireClean(game, loader, original) }
            assertContains(error.message.orEmpty(), name)
            assertFalse(error.message.orEmpty().contains("Réparer le chargeur SDK"))
            assertEquals("different DLL", game.resolve(name).readText())
            game.resolve(name).writeBytes(loader.resolve(name).readBytes())
            loader.resolve(name).deleteExisting()
            assertFails { WindowsSdkRepair.requireClean(game, loader, original) }
            loader.resolve(name).writeBytes(game.resolve(name).readBytes())
        }
        assertEquals("foreign unrelated DLL", game.resolve("unrelated.dll").readText())
        game.resolve("SDL3.dll").writeText("unknown SDL")
        assertFails { WindowsSdkRepair.requireClean(game, loader, original) }
    }

    @Test fun repairPreservesEverySharedDllAndUnderstandsAdditionalBridges() {
        for (sharedFiles in listOf(false, true)) {
            val f = Fixture()
            val fields = mapOf("textureBridgeSha256" to dlls[2], "signalUiBridgeSha256" to dlls[3],
                "automaticDrivingBridgeSha256" to dlls[4], "constructionBridgeSha256" to dlls[5])
            dlls.forEach { f.game.resolve(it).writeText("fixture $it") }
            f.manifest()
            val m = hubJson.parseToJsonElement(f.game.resolve(WindowsSdkRepair.MANIFEST).readText()).jsonObject.toMutableMap()
            m["format"] = JsonPrimitive(if(sharedFiles) "3" else "2")
            fields.forEach { (key, name) -> m[key] = JsonPrimitive(f.game.resolve(name).sha256()) }
            m["additionalBridges"] = JsonObject(dlls.takeLast(2).associateWith { JsonPrimitive(f.game.resolve(it).sha256()) })
            if (sharedFiles) m["sharedFiles"] = JsonObject(dlls.associateWith { JsonPrimitive(f.game.resolve(it).sha256()) })
            f.game.resolve(WindowsSdkRepair.MANIFEST).writeText(JsonObject(m).toString())
            val before = f.snapshot()
            f.repair().repair(f.game)
            dlls.forEach { name ->
                if(sharedFiles) assertEquals(before[name], f.game.resolve(name).sha256())
                else assertFalse(f.game.resolve(name).exists(), name)
            }
        }
    }

    @Test fun malformedSharedOwnershipCannotProtectOrDeleteArbitraryFiles() {
        for (name in listOf("SDL3.dll", "../outside.dll", "keep.txt")) {
            val f = Fixture()
            val m = hubJson.parseToJsonElement(f.game.resolve(WindowsSdkRepair.MANIFEST).readText()).jsonObject.toMutableMap()
            m["format"] = JsonPrimitive("3")
            m["sharedFiles"] = buildJsonObject { put(name, f.original) }
            f.game.resolve(WindowsSdkRepair.MANIFEST).writeText(JsonObject(m).toString())
            val before = f.snapshot()
            assertFails { f.repair().repair(f.game) }
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun missingOwnedDllsAndSteamRestoredSdlCanBeRecovered() {
        for (kind in listOf("sdk", "pthread", "sdl", "all-owned", "steam-restored", "steam-restored-no-backup")) {
            val f = Fixture()
            when(kind) {
                "sdk" -> f.game.resolve("NimbyRailsFranceSDK.dll").deleteExisting()
                "pthread" -> f.game.resolve("libwinpthread-1.dll").deleteExisting()
                "sdl" -> f.game.resolve("SDL3.dll").deleteExisting()
                "all-owned" -> listOf("SDL3.dll", "NimbyRailsFranceSDK.dll", "libwinpthread-1.dll").forEach { f.game.resolve(it).deleteExisting() }
                else -> {
                    f.game.resolve("SDL3.dll").writeBytes(f.game.resolve("NimbyRailsSDL3Original.dll").readBytes())
                    if(kind.endsWith("no-backup")) f.game.resolve("NimbyRailsSDL3Original.dll").deleteExisting()
                }
            }
            f.repair().repair(f.game)
            assertEquals(f.original, f.game.resolve("SDL3.dll").sha256(), kind)
            assertEquals(setOf("SDL3.dll", "NimbyRails.exe", "keep.txt"), f.snapshot().keys, kind)
        }
    }

    @Test fun pendingInstallRepairsBeforeAndAfterSdlReplacement() {
        for (phase in listOf("intent", "stage", "renamed", "installed")) {
            val f = Fixture()
            Files.move(f.game.resolve(WindowsSdkRepair.MANIFEST), f.game.resolve(WindowsSdkRepair.PENDING))
            if(phase != "installed") {
                f.game.resolve("SDL3.NimbySDK.tmp").writeBytes(f.game.resolve("SDL3.dll").readBytes())
                f.game.resolve("SDL3.dll").deleteExisting()
                f.game.resolve("NimbyRailsFranceSDK.dll").deleteExisting()
                f.game.resolve("libwinpthread-1.dll").deleteExisting()
                if(phase != "renamed") Files.move(f.game.resolve("NimbyRailsSDL3Original.dll"), f.game.resolve("SDL3.dll"))
                if(phase == "intent") f.game.resolve("SDL3.NimbySDK.tmp").deleteExisting()
            }
            f.repair().repair(f.game)
            assertEquals(f.original, f.game.resolve("SDL3.dll").sha256(), phase)
            assertEquals(setOf("SDL3.dll", "NimbyRails.exe", "keep.txt"), f.snapshot().keys, phase)
        }
    }

    @Test fun missingOriginalAndProxySdlCannotBeGuessed() {
        val f = Fixture()
        f.game.resolve("NimbyRailsSDL3Original.dll").deleteExisting()
        val before = f.snapshot()
        assertFails { f.repair().repair(f.game) }
        assertEquals(before, f.snapshot())
    }

    @Test fun interruptedRepairRetainsSharedOwnership() {
        val f = Fixture()
        val name = "libwinpthread-1.dll"
        val sum = f.game.resolve(name).sha256()
        val m = hubJson.parseToJsonElement(f.game.resolve(WindowsSdkRepair.MANIFEST).readText()).jsonObject.toMutableMap()
        m["format"] = JsonPrimitive("3")
        m["sharedFiles"] = buildJsonObject { put(name, sum) }
        f.game.resolve(WindowsSdkRepair.MANIFEST).writeText(JsonObject(m).toString())
        assertFails { f.repair(log = { if(it.startsWith("Sauvegarde de réparation")) error("interruption") }).repair(f.game) }
        f.game.resolve(name).writeText("external change")
        val before = f.snapshot()
        assertFails { f.repair().repair(f.game) }
        assertEquals(before, f.snapshot())
        f.game.resolve(name).writeText("dependency")
        f.repair().repair(f.game)
        assertEquals(sum, f.game.resolve(name).sha256())
    }

    @Test fun recognizedLegacyDependencyCanMigrateButUnknownVersionCannot() {
        val f = Fixture()
        f.game.resolve(WindowsSdkRepair.MANIFEST).deleteExisting()
        f.game.resolve("SDL3.dll").writeBytes(f.game.resolve("NimbyRailsSDL3Original.dll").readBytes())
        f.game.resolve("NimbyRailsSDL3Original.dll").deleteExisting()
        f.game.resolve("NimbyRailsFranceSDK.dll").deleteExisting()
        val loader = f.root.resolve("loader").createDirectory()
        loader.resolve("libwinpthread-1.dll").writeText("new dependency")
        val known = mapOf("libwinpthread-1.dll" to setOf(f.game.resolve("libwinpthread-1.dll").sha256()))
        WindowsSdkRepair.requireClean(f.game, loader, f.original, known)
        assertFails { WindowsSdkRepair.requireClean(f.game, loader, f.original, emptyMap()) }
        assertEquals("dependency", f.game.resolve("libwinpthread-1.dll").readText())
    }

    @Test fun migratedDllIsRestoredAcrossInstallationAndRepairInterruptions() {
        for (phase in listOf("installed", "intent", "renamed", "missing", "interrupted-repair")) {
            val f = Fixture()
            val name = "libwinpthread-1.dll"
            val prior = f.game.resolve("$name.nrf-before-sdk").apply { writeText("previous dependency") }
            val oldHash = prior.sha256()
            val m = hubJson.parseToJsonElement(f.game.resolve(WindowsSdkRepair.MANIFEST).readText()).jsonObject.toMutableMap()
            m["format"] = JsonPrimitive("3")
            m["replacedFiles"] = buildJsonObject { put(name, oldHash) }
            f.game.resolve(WindowsSdkRepair.MANIFEST).writeText(JsonObject(m).toString())
            if(phase == "intent") {
                f.game.resolve(name).writeBytes(prior.readBytes()); prior.deleteExisting()
            }
            if(phase in setOf("renamed", "missing")) f.game.resolve(name).deleteExisting()
            if(phase != "installed") Files.move(f.game.resolve(WindowsSdkRepair.MANIFEST), f.game.resolve(WindowsSdkRepair.PENDING))
            if(phase == "interrupted-repair") {
                assertFails { f.repair(log = { if(it.startsWith("Sauvegarde de réparation")) error("interruption") }).repair(f.game) }
                f.game.resolve(name).writeBytes(prior.readBytes())
                prior.deleteExisting()
            }
            f.repair().repair(f.game)
            assertEquals(oldHash, f.game.resolve(name).sha256(), phase)
            assertFalse(prior.exists())
            assertEquals(f.original, f.game.resolve("SDL3.dll").sha256())
        }
    }
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

    @Test fun developmentConstructionBridgeIsVerifiedBackedUpAndRemoved() {
        val f=Fixture()
        val name="NimbyConstructionBridge-experimental-v1.dll"
        f.game.resolve(name).writeText("construction fixture")
        val hash=f.game.resolve(name).sha256()
        f.manifest("constructionBridgeSha256" to hash)
        val backup=f.repair().repair(f.game)
        assertEquals(hash,backup.resolve(name).sha256())
        assertFalse(f.game.resolve(name).exists())
        assertEquals(f.original,f.game.resolve("SDL3.dll").sha256())
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
