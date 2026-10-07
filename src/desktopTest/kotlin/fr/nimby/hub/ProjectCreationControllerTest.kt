package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectCreationControllerTest {
    @AfterTest fun resetLanguage() { fr.nimby.hub.i18n.I18n.configure("auto", "fr") }

    @Test fun createsAndRegistersSourcesWithoutCompilingOrChangingTheAppliedProfile() = runTest {
        val root = Files.createTempDirectory("nrf-create-controller-")
        val game = root.resolve("game").createDirectory()
        val sentinel = game.resolve("untouched.txt").apply { writeText("game remains unchanged") }
        val kit = fixtureKit(root.resolve("kit"))
        val store = SettingsStore(root.resolve("profile"))
        val initial = HubSettings(language = "fr", developerMode = true, profile = HubProfile.DEVELOP,
            appliedProfile = HubProfile.PLAY, gameDirectory = game.toString(),
            paths = HubPaths(localMods = root.resolve("sources").toString(), kotlinSdk = kit.toString()))
        store.write(initial)
        val controller = HubController(store, backgroundScope, journal = HubLog(root.resolve("logs")))
        var created: LocalProject? = null
        try {
            controller.createProject(request()) { created = it }
            runCurrent(); controller.state.first { !it.busy }
            assertNull(controller.state.value.operationError)
            val local = assertNotNull(created)
            val persisted = store.read()
            assertEquals(local, persisted.development.projects["my-signals"])
            assertEquals(ModOrigin.LOCAL, persisted.development.origins["my-signals"])
            assertFalse(persisted.development.builds.getValue("my-signals").ready)
            assertTrue(persisted.development.prepared.isEmpty())
            assertEquals(initial.appliedProfile, persisted.appliedProfile)
            assertEquals(initial.installed, persisted.installed)
            assertEquals(initial.activeDevelopment, persisted.activeDevelopment)
            assertEquals("game remains unchanged", sentinel.readText())
            assertEquals("packageMod", local.task)
            assertEquals("0.9.0-alpha.1", local.project.sdkMin)
            assertTrue(Path(local.directory, "mod.json").isRegularFile())
            assertFalse(Path(local.directory, "build").exists())
        } finally { controller.close() }
    }

    @Test fun duplicateIdentifierLeavesExistingRegistrationAndSourcesIntact() = runTest {
        val root = Files.createTempDirectory("nrf-create-duplicate-")
        val store = SettingsStore(root.resolve("profile"))
        val existing = LocalProject(Project("my-signals", "native-mod", "1.0.0", modId = "my-signals",
            rootFolder = "my-signals-1.0.0", gameSha256 = listOf("a".repeat(64))),
            root.resolve("old").toString(), "mod.json", task = "packageMod")
        val initial = HubSettings(language = "fr", developerMode = true, profile = HubProfile.DEVELOP,
            paths = HubPaths(localMods = root.resolve("sources").toString()),
            development = DevelopmentSettings(projects = mapOf("my-signals" to existing)))
        store.write(initial)
        val before = store.read()
        val controller = HubController(store, backgroundScope, journal = HubLog(root.resolve("logs")))
        try {
            controller.createProject(request()) { fail("duplicate must not succeed") }
            runCurrent(); controller.state.first { !it.busy }
            assertNotNull(controller.state.value.operationError)
            assertEquals(before, store.read())
            assertFalse(root.resolve("sources").exists())
        } finally { controller.close() }
    }

    @Test fun playProfileCannotCreateSourcesEvenWithDeveloperModeEnabled() = runTest {
        val root = Files.createTempDirectory("nrf-create-play-")
        val store = SettingsStore(root.resolve("profile"))
        val initial = HubSettings(language = "fr", developerMode = true, profile = HubProfile.PLAY,
            paths = HubPaths(localMods = root.resolve("sources").toString()))
        store.write(initial)
        val before = store.read()
        val controller = HubController(store, backgroundScope, journal = HubLog(root.resolve("logs")))
        try {
            controller.createProject(request()) { fail("play profile must not create a project") }
            runCurrent(); controller.state.first { !it.busy }
            assertNotNull(controller.state.value.operationError)
            assertEquals(before, store.read())
            assertFalse(root.resolve("sources").exists())
        } finally { controller.close() }
    }

    private fun request() = NewProjectRequest("my-signals", "Mes signaux", "Auteur", "Un exemple", ProjectTemplate.SIGNAL)

    private fun fixtureKit(directory: Path): Path {
        directory.createDirectories()
        val target = if (Host.windows) "mingw_x64" else "linux_x64"
        directory.resolve("sdk.json").writeText("""{"format":1,"sdkVersion":"0.9.0-alpha.1","gradlePluginVersion":"0.9.0-alpha.1","kotlinVersion":"2.2.20","gradleVersion":"8.14.3","target":"$target","gameSha256":["${"a".repeat(64)}"]}""")
        val files = listOf("klib/nimby-mod-api.klib", "bridge/Exports.kt", "bin/NimbyKotlinMod.${Host.moduleExtension}",
            "bin/NimbyRailsFranceSDK.${Host.moduleExtension}", "bin/kotlin_loader_test${if (Host.windows) ".exe" else ""}",
            "gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/0.9.0-alpha.1/fr.nimbyrails.mod.gradle.plugin-0.9.0-alpha.1.pom") +
            if (Host.windows) listOf("bin/libwinpthread-1.dll") else emptyList()
        files.forEach { relative -> directory.resolve(relative).apply { parent.createDirectories(); writeText("fixture only") } }
        return directory
    }
}
