package fr.nimby.hub

import fr.nimby.hub.install.LocalProjects
import fr.nimby.hub.model.*
import fr.nimby.hub.storage.*
import fr.nimby.hub.platform.Host
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class LocalProjectsTest {
    @Test fun kotlinProjectNeedsOnlySourceManifestBeforeFirstBuild() {
        val root = Files.createTempDirectory("nrf-kotlin-source-")
        root.resolve("gradle/wrapper").createDirectories().resolve("gradle-wrapper.jar").writeText("fixture")
        root.resolve("mod.json").writeText("""{
            "id":"new-mod","name":"New mod","modId":"NewMod","module":"NewModCore",
            "version":"1.0.0","language":"kotlin-native","sdkMin":"0.7.3","sdkMaxExclusive":"0.8.0",
            "gameSha256":["${"b".repeat(64)}"]
        }""")
        val local = LocalProjects.read(root)
        assertEquals("packageMod", local.task)
        assertEquals("build/${if (Host.windows) "gradle" else "gradle-linux"}/distributions/NewMod-1.0.0-${Host.id}.zip", local.archive)
        assertEquals("NewModCore.${Host.moduleExtension}", local.project.module)
        assertEquals(Host.id, local.project.platform)
        val fingerprint = LocalProjects.fingerprint(local, "")
        root.resolve("tools").createDirectory().resolve("capture.py").writeText("local analysis")
        root.resolve("local-notes").createDirectory().resolve("report.md").writeText("local results")
        assertEquals(fingerprint, LocalProjects.fingerprint(local, ""))
        assertEquals(0, local.project.size)
        assertFails { ProjectRules.validate(local.project, remote = false) }
        val store = SettingsStore(root.resolve("hub-data"))
        store.write(HubSettings(development = DevelopmentSettings(projects = mapOf("new-mod" to local))))
        assertEquals(local, store.read().development.projects["new-mod"])
        root.resolve("mod.json").writeText(root.resolve("mod.json").readText().replace("0.8.0", "0.7.0"))
        assertFails { LocalProjects.read(root) }
    }

    private fun project() = Project("fixture", "tco", "1.0.0", rootFolder = "Fixture", size = 12,
        sha256 = "a".repeat(64), gameSha256 = listOf("b".repeat(64)),
        url = "https://github.com/NimbyRails-France/fixture/releases/download/v1.0.0/fixture.zip")

    @Test fun descriptorReadsDeclaredArtifactAndRejectsEscapingPaths() {
        val root = Files.createTempDirectory("nrf-local-")
        root.resolve("project.json").writeText(hubJson.encodeToString(project()))
        root.resolve("hub-local.json").writeText("""{"manifest":"project.json","task":"packageTool","archive":"build/tool-{version}.zip"}""")
        val local = LocalProjects.read(root)
        assertEquals("packageTool", local.task)
        assertEquals("build/tool-1.0.0.zip", local.archive)
        root.resolve("hub-local.json").writeText("""{"manifest":"../project.json"}""")
        assertFails { LocalProjects.read(root) }
        assertFails { LocalProjects.inside(root, "../../escape.zip") }
    }

    @Test fun sourceAndSdkChangesInvalidateFingerprintButBuildOutputsDoNot() {
        val root = Files.createTempDirectory("nrf-fingerprint-")
        val sdk = Files.createTempDirectory("nrf-sdk-fingerprint-")
        root.resolve("project.json").writeText(hubJson.encodeToString(project()))
        val source = root.resolve("source.kt").apply { writeText("source 1") }
        val api = sdk.resolve("api.klib").apply { writeText("sdk 1") }
        val local = LocalProjects.read(root)
        val first = LocalProjects.fingerprint(local, sdk.toString())
        root.resolve("build").createDirectory().resolve("generated.txt").writeText("output")
        assertEquals(first, LocalProjects.fingerprint(local, sdk.toString()))
        root.resolve("tools").createDirectory().resolve("capture.py").writeText("local analysis")
        root.resolve("local-notes").createDirectory().resolve("report.md").writeText("local results")
        // Custom tool projects may use tools/ as build inputs; only SDK mods exclude it.
        val withTools = LocalProjects.fingerprint(local, sdk.toString())
        assertNotEquals(first, withTools)
        source.writeText("source 2")
        assertNotEquals(withTools, LocalProjects.fingerprint(local, sdk.toString()))
        source.writeText("source 1"); api.writeText("sdk 2")
        assertNotEquals(withTools, LocalProjects.fingerprint(local, sdk.toString()))
    }

    @Test fun failedCompilationInvalidatesPreviousReadyResultWithoutDeletingIt() = runTest {
        val root = Files.createTempDirectory("nrf-failed-build-")
        root.resolve("project.json").writeText(hubJson.encodeToString(project()))
        root.resolve("hub-local.json").writeText("""{"manifest":"project.json","task":"packageTool","archive":"build/tool.zip"}""")
        val local = LocalProjects.read(root)
        val old = root.resolve("old-package.zip").apply { writeText("previous build") }
        val store = SettingsStore(root.resolve("data"))
        store.write(HubSettings(developerMode = true, profile = HubProfile.DEVELOP, development = DevelopmentSettings(
            projects = mapOf("fixture" to local), origins = mapOf("fixture" to ModOrigin.LOCAL),
            builds = mapOf("fixture" to BuildResult("Prêt à tester", true)),
            prepared = mapOf("fixture" to InstalledProject("fixture", "tco", "1.0.0", root.toString())))))
        val controller = HubController(store, backgroundScope)
        try {
            controller.compile("fixture")
            controller.state.first { !it.busy }
            assertNotNull(controller.state.value.operationError)
            assertFalse(store.read().development.builds.getValue("fixture").ready)
            assertEquals("previous build", old.readText())
            assertFails { ProfileRules.resolve(store.read()) }
        } finally { controller.close() }
    }

    @Test fun oldDeveloperSettingsAreBackedUpAndProtectedDuringMigration() {
        val root = Files.createTempDirectory("nrf-settings-migration-")
        val original = """{"developerMode":true,"root":"C:/existing","installed":{}}"""
        root.resolve("settings.json").writeText(original)
        val store = SettingsStore(root)
        val settings = store.read()
        assertTrue(settings.legacyProtection)
        assertEquals("C:/existing", settings.path(PathSetting.MODS))
        assertEquals(original, root.resolve("settings.before-profiles.json").readText())
        assertEquals(original, root.resolve("settings.json").readText())
    }

    @Test fun gradleWrapperBuildsDeclaredPackageWithoutPublishingOrInstalling() = runBlocking {
        val root = Files.createTempDirectory("nrf-gradle-fixture-")
        root.resolve("gradle/wrapper").createDirectories()
        listOf("gradle-wrapper.jar", "gradle-wrapper.properties").forEach {
            Files.copy(Path("gradle/wrapper/$it"), root.resolve("gradle/wrapper/$it"))
        }
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        root.resolve("build.gradle.kts").writeText("""
            tasks.register<Zip>("packageTool") {
                from("payload") { into("Fixture") }
                archiveFileName.set("fixture.zip")
                destinationDirectory.set(layout.buildDirectory.dir("packages"))
            }
        """.trimIndent())
        root.resolve("payload").createDirectory().resolve("NimbyTco.exe").writeText("fake executable")
        root.resolve("project.json").writeText(hubJson.encodeToString(project()))
        root.resolve("hub-local.json").writeText("""{"manifest":"project.json","task":"packageTool","archive":"build/packages/fixture.zip"}""")
        val output = mutableListOf<String>()
        val (built, archive) = LocalProjects.build(LocalProjects.read(root), "", output::add)
        assertTrue(output.any { it.contains("BUILD SUCCESSFUL") })
        assertEquals(archive.sha256(), built.sha256)
        assertEquals(archive.fileSize(), built.size)
        val stage = root.resolve("verify").createDirectory()
        fr.nimby.hub.install.Archives.extract(archive, stage, built.rootFolder)
        assertEquals("fake executable", stage.resolve("NimbyTco.exe").readText())
    }
}
