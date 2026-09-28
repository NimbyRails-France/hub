package fr.nimby.hub

import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class KotlinKitsTest {
    private class Fixture {
        val root = Files.createTempDirectory("nrf downloaded kit ")
        val version = "0.8.0-alpha.2"
        val archive = root.resolve("kit.zip")
        fun zip(metadataVersion: String = version, extraEntry: String? = null): KotlinKit {
            val files = mutableMapOf(
                "sdk.json" to """{"format":1,"target":"mingw_x64","sdkVersion":"$metadataVersion","gradlePluginVersion":"$version"}""",
                "bin/NimbyRailsFranceSDK.dll" to "SDK", "bin/NimbyKotlinMod.dll" to "adapter", "bin/kotlin_loader_test.exe" to "loader",
                "klib/nimby-mod-api.klib" to "API", "bridge/Exports.kt" to "bridge",
                "gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/$version/fr.nimbyrails.mod.gradle.plugin-$version.pom" to "plugin")
            extraEntry?.let { files[it] = "malicious path" }
            ZipOutputStream(archive.outputStream()).use { zip -> files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            } }
            return KotlinKit(version, "windows-x64", DistributionLocation.page("sdk", version) + "NimbyRailsFranceSDK-kotlin-$version-windows-x64.zip", archive.fileSize(), archive.sha256())
        }
        val store = SettingsStore(root.resolve("data"))
        fun settings(): HubSettings {
            val oldKit = root.resolve("previous").createDirectories().apply { resolve("keep.txt").writeText("previous working kit") }
            val project = Project("signals", "native-mod", "1.0.0", rootFolder = "Signals", gameSha256 = listOf("a".repeat(64)),
                loaderApi = 1, modId = "Signals", module = "Signals.dll", sdkMin = "0.8.0-alpha.1", sdkMaxExclusive = "0.9.0")
            return HubSettings(developerMode = true, profile = HubProfile.DEVELOP,
                paths = HubPaths(kotlinSdk = oldKit.toString()),
                development = DevelopmentSettings(projects = mapOf("signals" to LocalProject(project, root.resolve("sources").toString(), "", "packageMod")),
                    builds = mapOf("signals" to BuildResult("Ready", true)),
                    prepared = mapOf("sdk" to InstalledProject("sdk", "sdk", "0.8.0-alpha.1", root.resolve("runtime").toString()))))
        }
    }
    private class Source(val kit: KotlinKit, val archive: Path) : ReleaseSource {
        var downloads = 0
        override suspend fun kotlinKits(channel: String) = listOf(kit)
        override suspend fun catalogue(channels: Map<String, String>) = error("No catalogue needed")
        override suspend fun project(id: String, channel: String): Project = error("No runtime download")
        override suspend fun hub(channel: String): HubRelease = error("No Hub update")
        override suspend fun listen(onEvent: suspend () -> Unit) = awaitCancellation()
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
            assertEquals(kit.url, url); assertEquals(kit.sha256, hash); assertEquals(kit.size, size)
            downloads++; Files.copy(archive, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Test fun flatKitsRejectTraversalCorruptionAndWrongMetadata() {
        if (!Host.windows) return
        val f = Fixture()
        val good = f.zip()
        val output = f.root.resolve("valid")
        KotlinKits.prepare(good, f.archive, output)
        assertEquals("API", output.resolve("klib/nimby-mod-api.klib").readText())
        assertFails { KotlinKits.prepare(good, f.archive, output) }
        for (name in listOf("../escape", "C:/escape", "/absolute", "bin/../../escape", "bin/NUL")) {
            val kit = f.zip(extraEntry = name)
            assertFails { KotlinKits.prepare(kit, f.archive, f.root.resolve("bad")) }
            assertFalse(f.root.resolve("bad").exists())
            assertFalse(f.root.resolve("escape").exists())
        }
        val wrong = f.zip(metadataVersion = "0.8.0-alpha.1")
        assertFails { KotlinKits.prepare(wrong, f.archive, f.root.resolve("wrong")) }
        f.zip()
        f.archive.appendText("corrupt")
        assertFails { KotlinKits.prepare(good, f.archive, f.root.resolve("corrupt")) }
    }

    @Test fun downloadSelectsVerifiedKitInvalidatesModsAndKeepsRuntimeAndOldKit() = runBlocking {
        if (!Host.windows) return@runBlocking
        val f = Fixture(); val kit = f.zip(); val original = f.settings(); f.store.write(original)
        val source = Source(kit, f.archive)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = HubController(f.store, scope, source, journal = HubLog(f.root.resolve("logs")))
        try {
            controller.loadKotlinKits()
            withTimeout(10_000) { controller.state.first { !it.busy } }
            assertEquals(listOf(kit), controller.state.value.kotlinKits)
            controller.downloadKotlinKit(kit)
            withTimeout(10_000) { controller.state.first { !it.busy } }
            assertNull(controller.state.value.operationError)
            val next = f.store.read()
            assertEquals(1, source.downloads)
            assertEquals(kit.version, controller.state.value.kotlinKitVersion)
            assertFalse(next.development.builds.getValue("signals").ready)
            assertEquals(original.development.prepared, next.development.prepared)
            assertEquals(original.activeDevelopment, next.activeDevelopment)
            assertEquals("previous working kit", Path(original.paths.kotlinSdk, "keep.txt").readText())
            assertNotEquals(original.paths.kotlinSdk, next.paths.kotlinSdk)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun invalidDownloadNeverReplacesTheWorkingSelection() = runBlocking {
        if (!Host.windows) return@runBlocking
        val f = Fixture(); val kit = f.zip(); f.archive.appendText("corruption")
        f.store.write(f.settings()); val original = f.store.read()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = HubController(f.store, scope, Source(kit, f.archive), journal = HubLog(f.root.resolve("logs")))
        try {
            controller.downloadKotlinKit(kit)
            withTimeout(10_000) { controller.state.first { !it.busy } }
            assertNotNull(controller.state.value.operationError)
            assertEquals(original.paths, f.store.read().paths)
            assertEquals(original.development, f.store.read().development)
        } finally { controller.close(); scope.cancel() }
    }
}
