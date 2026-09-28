package fr.nimby.hub

import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.platform.windows.WindowsSdkBuilder
import fr.nimby.hub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class WindowsSdkBuilderTest {
    private class Fixture {
        val root = Files.createTempDirectory("nrf sdk source with spaces ")
        val sources = root.resolve("sources").createDirectory()
        val game = root.resolve("game").createDirectory()
        val gameHash = Host.game(game).apply { writeText("fixture game") }.sha256()
        val version = "0.8.0-alpha.2"
        init {
            sources.resolve("VERSION").writeText(version)
            sources.resolve("CMakeLists.txt").writeText("fixture")
            sources.resolve("hub-local.json").writeText("""{"builder":"windows-sdk","gameSha256":["$gameHash"]}""")
            sources.resolve("tools/windows").createDirectories().resolve("build-for-hub.ps1").writeText("""
                param([string]${'$'}OutputDirectory)
                ${'$'}ErrorActionPreference='Stop'
                Copy-Item -Path "${'$'}PSScriptRoot/../../fixture/*" -Destination ${'$'}OutputDirectory -Recurse
                Write-Output 'Fixture SDK built'
            """.trimIndent())
        }
        fun local() = LocalProjects.read(sources)
        fun output(runtime: String = "same DLL"): Path {
            val out = sources.resolve("fixture").createDirectories()
            val kit = out.resolve("kotlin-kit").createDirectories()
            listOf("klib/nimby-mod-api.klib", "bridge/Exports.kt", "bin/NimbyKotlinMod.dll", "bin/kotlin_loader_test.exe",
                "gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/$version/fr.nimbyrails.mod.gradle.plugin-$version.pom").forEach {
                kit.resolve(it).apply { parent.createDirectories(); writeText("fixture") }
            }
            kit.resolve("bin/NimbyRailsFranceSDK.dll").writeText("same DLL")
            kit.resolve("sdk.json").writeText("""{"format":1,"target":"mingw_x64","sdkVersion":"$version","gradlePluginVersion":"$version"}""")
            val project = local().project
            val zip = out.resolve("sdk.zip")
            ZipOutputStream(zip.outputStream()).use { stream ->
                mapOf("loader/install-proxy.ps1" to "throw 'never executed'", "loader/NimbyRailsFranceSDK.dll" to runtime).forEach { (name, data) ->
                    stream.putNextEntry(ZipEntry("${project.rootFolder}/$name")); stream.write(data.toByteArray()); stream.closeEntry()
                }
            }
            out.resolve("project.json").writeText(hubJson.encodeToString(project.copy(size = zip.fileSize(), sha256 = zip.sha256())))
            out.resolve("hub-result.json").writeText("""{"project":"project.json","archive":"sdk.zip","kotlinKit":"kotlin-kit"}""")
            return out
        }
        fun store(): SettingsStore {
            val oldKit = root.resolve("previous-kit").createDirectory().apply { resolve("keep.txt").writeText("working kit") }
            val mod = LocalProject(local().project.copy(id = "signals", kind = "native-mod", version = "1.0.0", channel = "stable",
                modId = "Signals", module = "Signals.dll", sdkMin = "0.8.0-alpha.1", sdkMaxExclusive = "0.9.0"),
                root.resolve("mod").toString(), "", "packageMod")
            return SettingsStore(root.resolve("settings")).also { it.write(HubSettings(
                gameDirectory = game.toString(), developerMode = true, profile = HubProfile.DEVELOP,
                paths = HubPaths(development = root.resolve("development").toString(), kotlinSdk = oldKit.toString()),
                development = DevelopmentSettings(projects = mapOf("sdk" to local(), "signals" to mod),
                    origins = mapOf("sdk" to ModOrigin.LOCAL),
                    builds = mapOf("sdk" to BuildResult("Previous", true), "signals" to BuildResult("Previous", true)))) ) }
        }
    }
    /** Any attempted game interaction makes the build fail. Simulates an open game. */
    private class NoGameChanges : Windows() {
        override fun requireClosed(game: Path, destination: Path) = error("Game is running")
        override fun proxy(directory: Path, game: Path, action: String) = error("Unexpected proxy change")
        override fun createLink(path: Path, target: Path) = error("Unexpected link")
    }

    @Test fun readsVersionFromSourcesWithoutAnExistingDistribution() {
        if (!Host.windows) return
        val f = Fixture()
        assertEquals(f.version, f.local().project.version)
        assertTrue(f.local().buildsSdk)
        f.sources.resolve("dist").createDirectory().resolve("project.json").writeText("stale invalid manifest")
        f.sources.resolve("VERSION").writeText("0.8.0-alpha.3")
        assertEquals("0.8.0-alpha.3", f.local().project.version)
        val before = LocalProjects.fingerprint(f.local(), "missing old kit")
        f.sources.resolve("install").createDirectory().resolve("generated").writeText("output")
        assertEquals(before, LocalProjects.fingerprint(f.local(), "different missing kit"))
        f.sources.resolve("CMakeLists.txt").writeText("new source")
        assertNotEquals(before, LocalProjects.fingerprint(f.local(), ""))
    }

    @Test fun validatesReceiptArchiveAndKitBeforeImport() {
        if (!Host.windows) return
        val f = Fixture(); val out = f.output()
        assertEquals(f.version, WindowsSdkBuilder.readResult(f.local(), out).project.version)
        out.resolve("sdk.zip").appendText("corruption")
        assertFailsWith<IllegalArgumentException> { WindowsSdkBuilder.readResult(f.local(), out) }
        f.output()
        out.resolve("kotlin-kit/sdk.json").writeText("""{"format":1,"target":"mingw_x64","sdkVersion":"0.8.0-alpha.1"}""")
        assertFailsWith<IllegalArgumentException> { WindowsSdkBuilder.readResult(f.local(), out) }
        f.output()
        out.resolve("hub-result.json").writeText("""{"project":"../project.json","archive":"sdk.zip","kotlinKit":"kotlin-kit"}""")
        assertFailsWith<IllegalArgumentException> { WindowsSdkBuilder.readResult(f.local(), out) }
    }

    @Test fun successfulSdkBuildPreparesRuntimeAndSelectsKitWithoutTouchingOpenGame() = runBlocking {
        if (!Host.windows) return@runBlocking
        val f = Fixture(); f.output(); val store = f.store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = HubController(store, scope, journal = HubLog(f.root.resolve("logs")), windows = NoGameChanges())
        try {
            controller.compile("sdk")
            withTimeout(60_000) { controller.state.first { !it.busy } }
            assertNull(controller.state.value.operationError)
            val next = controller.state.value.settings
            assertTrue(next.development.builds.getValue("sdk").ready)
            assertEquals(f.version, next.development.builds.getValue("sdk").sdkVersion)
            assertFalse(next.development.builds.getValue("signals").ready)
            assertEquals(f.version, next.development.prepared.getValue("sdk").version)
            assertEquals(f.version, LocalProjects.kotlinSdk(next.paths.kotlinSdk, f.local().project))
            assertEquals(LocalProjects.fingerprint(f.local(), next.paths.kotlinSdk), next.development.builds.getValue("sdk").fingerprint)
            assertEquals("working kit", f.root.resolve("previous-kit/keep.txt").readText())
            assertFalse(f.game.resolve("NimbyRailsFranceSDK.dll").exists())
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun mismatchedRuntimeOrFailedProcessKeepsPreviousKitAndMods() = runBlocking {
        if (!Host.windows) return@runBlocking
        for (failure in listOf("runtime", "process")) {
            val f = Fixture(); f.output(runtime = "different DLL"); val store = f.store()
            if (failure == "process") f.sources.resolve("tools/windows/build-for-hub.ps1").writeText("exit 42")
            val original = store.read()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = HubController(store, scope, journal = HubLog(f.root.resolve("logs")), windows = NoGameChanges())
            try {
                controller.compile("sdk")
                withTimeout(60_000) { controller.state.first { !it.busy } }
                assertNotNull(controller.state.value.operationError)
                val next = controller.state.value.settings
                assertFalse(next.development.builds.getValue("sdk").ready)
                assertEquals(original.paths.kotlinSdk, next.paths.kotlinSdk)
                assertEquals(original.development.prepared, next.development.prepared)
                assertTrue(next.development.builds.getValue("signals").ready)
                assertEquals("working kit", f.root.resolve("previous-kit/keep.txt").readText())
            } finally { controller.close(); scope.cancel() }
        }
    }
}
