package fr.nimby.hub

import fr.nimby.hub.install.LocalProjects
import fr.nimby.hub.install.ProjectGenerator
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import kotlinx.serialization.json.*
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.io.path.*
import kotlin.test.*

class ProjectGeneratorTest {
    private fun request(template: ProjectTemplate = ProjectTemplate.SIGNAL) =
        NewProjectRequest("example-mod", "Exemple de mod", "Équipe NRF", "Un exemple.\nDeuxième ligne.", template)

    private fun kit(parent: Path, version: String = "0.9.0-alpha.1"): Path {
        val root = parent.resolve("SDK été #={0}").createDirectories()
        root.resolve("sdk.json").writeText(hubJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("format", 1); put("sdkVersion", version); put("gradlePluginVersion", version)
            put("kotlinVersion", "2.2.20"); put("gradleVersion", "8.14.3")
            put("target", if (Host.windows) "mingw_x64" else "linux_x64")
            putJsonArray("gameSha256") { add("b".repeat(64)); add("c".repeat(64)) }
        }))
        val required = listOf("klib/nimby-mod-api.klib", "bridge/Exports.kt", "bin/NimbyKotlinMod.${Host.moduleExtension}",
            "bin/NimbyRailsFranceSDK.${Host.moduleExtension}", "bin/kotlin_loader_test${if (Host.windows) ".exe" else ""}",
            "gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/$version/fr.nimbyrails.mod.gradle.plugin-$version.pom") +
            if (Host.windows) listOf("bin/libwinpthread-1.dll") else emptyList()
        required.forEach { file -> root.resolve(file).apply { this.parent.createDirectories(); writeText("fixture") } }
        return root
    }

    @Test fun bothTemplatesAreImmediatelyReadableNativeModsUsingSelectedKitMetadata() {
        for (template in ProjectTemplate.entries) {
            val base = Files.createTempDirectory("nrf-project-generator-")
            val sdk = kit(base)
            val projects = base.resolve("sources")
            val local = ProjectGenerator.create(projects, sdk, request(template))
            assertEquals(local, LocalProjects.read(Path.of(local.directory)))
            assertEquals("native-mod", local.project.kind)
            assertEquals("in-development", local.project.developmentStatus)
            assertEquals("packageMod", local.task)
            assertEquals("0.9.0-alpha.1", local.project.sdkMin)
            assertEquals("0.10.0", local.project.sdkMaxExclusive)
            assertEquals(listOf("b".repeat(64), "c".repeat(64)), local.project.gameSha256)
            assertEquals("example-mod-mod.${Host.moduleExtension}", local.project.module)
            assertEquals("build/${if (Host.windows) "gradle" else "gradle-linux"}/distributions/example-mod-0.1.0-${Host.id}.zip", local.archive)
            val root = Path.of(local.directory)
            val properties = Properties().apply { root.resolve("gradle.properties").inputStream().use(::load) }
            assertEquals(sdk.toString(), properties.getProperty("nrfSdkDir"))
            val wrapper = Properties().apply { root.resolve("gradle/wrapper/gradle-wrapper.properties").inputStream().use(::load) }
            assertEquals("https://services.gradle.org/distributions/gradle-8.14.3-bin.zip", wrapper.getProperty("distributionUrl"))
            assertTrue(root.resolve("gradle/LICENSE.txt").readText().contains("Apache License"))
            assertTrue(root.resolve("gradlew").isRegularFile())
            assertTrue(root.resolve("gradlew.bat").isRegularFile())
            ZipInputStream(root.resolve("gradle/wrapper/gradle-wrapper.jar").inputStream()).use { archive ->
                assertTrue(generateSequence { archive.nextEntry }.any { it.name == "org/gradle/wrapper/GradleWrapperMain.class" })
            }
            assertTrue(root.resolve("src/test/kotlin/ModTests.kt").readText().contains("@Test"))
            assertTrue(root.resolve("README.md").readText().startsWith("# Exemple de mod\n"))
            val metadata = hubJson.parseToJsonElement(root.resolve("mod.json").readText()).jsonObject
            assertEquals("in-development", metadata.getValue("developmentStatus").jsonPrimitive.content)
            val readme = root.resolve("README.md").readText()
            assertTrue(readme.contains("indépendamment du canal de version"))
            assertTrue(readme.contains("independently from the release channel"))
            val entry = root.resolve("src/main/kotlin/Entry.kt").readText()
            if (template == ProjectTemplate.SIGNAL) {
                assertTrue(entry.contains("size = 4, left = true"))
                assertTrue(root.resolve("assets/closed.svg").isRegularFile())
                assertTrue(root.resolve("assets/open.svg").isRegularFile())
            } else {
                assertTrue(entry.contains("toolMod(modInfo)"))
                assertTrue(entry.contains("window(\"main\""))
                assertFalse(entry.contains("signalModel("))
            }
            assertFalse(root.resolve("build").exists(), "Generation must not launch a build")
        }
    }

    @Test fun stringsAreEncodedAsDataAndNeverInsertedAsKotlinCode() {
        val base = Files.createTempDirectory("nrf-template-quoting-")
        val dangerous = "Été \\\" ${'$'}{error(\"injected\")} {0}"
        val data = request().copy(name = dangerous, author = dangerous, description = "$dangerous\r\nSuite\tdu texte")
        val local = ProjectGenerator.create(base.resolve("sources"), kit(base), data)
        val root = Path.of(local.directory)
        val manifest = hubJson.parseToJsonElement(root.resolve("mod.json").readText()).jsonObject
        assertEquals(dangerous, manifest.getValue("name").jsonPrimitive.content)
        val source = root.resolve("src/main/kotlin/Entry.kt").readText()
        assertTrue(source.contains("\\${'$'}{error(\\\"injected\\\")}"))
        assertTrue(source.contains("\\nSuite du texte"))
        assertFalse(source.contains("\r"))
        assertEquals(0x70, root.resolve("src/main/kotlin/Entry.kt").readBytes().first().toInt(), "UTF-8 sources have no BOM")
    }

    @Test fun existingDirectoriesAndFilesAreNeverModified() {
        val base = Files.createTempDirectory("nrf-template-collision-")
        val sdk = kit(base)
        val projects = base.resolve("sources").createDirectories()
        val existing = projects.resolve("example-mod").createDirectory()
        existing.resolve("important.txt").writeText("keep")
        assertFails { ProjectGenerator.create(projects, sdk, request()) }
        assertEquals(listOf("important.txt"), existing.listDirectoryEntries().map { it.name })
        assertEquals("keep", existing.resolve("important.txt").readText())
        projects.resolve("occupied-file").writeText("keep file")
        assertFails { ProjectGenerator.create(projects, sdk, request().copy(id = "occupied-file")) }
        assertEquals("keep file", projects.resolve("occupied-file").readText())
    }

    @Test fun invalidIdentitiesAndTextAreRejectedBeforeCreatingFolders() {
        val base = Files.createTempDirectory("nrf-template-invalid-")
        val sdk = kit(base)
        val projects = base.resolve("sources")
        val invalid = listOf("../escape", "a/b", "C:\\escape", "UPPER", "con", "com1", "sdk", "hub", "tco", "", "a".repeat(65))
        invalid.forEach { id -> assertFails { ProjectGenerator.create(projects, sdk, request().copy(id = id)) } }
        listOf(request().copy(name = ""), request().copy(author = "bad\nline"), request().copy(description = "\u0000"),
            request().copy(version = "0.0.0-rc.1"), request().copy(name = "é".repeat(129)),
            request().copy(description = "x\n".repeat(1000))).forEach { data ->
            assertFails { ProjectGenerator.create(projects, sdk, data) }
        }
        assertFalse(projects.exists())
        assertFails { ProjectGenerator.create(Path.of("relative"), sdk, request()) }
        assertFails { ProjectGenerator.create(projects, Path.of("relative-kit"), request()) }
    }

    @Test fun malformedOrIncompleteKitDoesNotCreateAProject() {
        val invalidFields = listOf("sdkVersion" to "bad", "gradlePluginVersion" to "bad", "target" to "unsupported",
            "gradleVersion" to "8.14.3\ninjected=value", "kotlinVersion" to "oops", "format" to 2)
        for ((field, value) in invalidFields) {
            val base = Files.createTempDirectory("nrf-template-kit-")
            val sdk = kit(base)
            val before = hubJson.parseToJsonElement(sdk.resolve("sdk.json").readText()).jsonObject
            val after = JsonObject(before + (field to if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())))
            sdk.resolve("sdk.json").writeText(after.toString())
            assertFails { ProjectGenerator.create(base.resolve("sources"), sdk, request()) }
            assertFalse(base.resolve("sources").exists())
        }
        val base = Files.createTempDirectory("nrf-template-missing-")
        val sdk = kit(base)
        sdk.resolve("bridge/Exports.kt").deleteExisting()
        assertFails { ProjectGenerator.create(base.resolve("sources"), sdk, request()) }
        assertFalse(base.resolve("sources").exists())
    }

    @Test fun destinationInsideSdkAndNonDirectoryAncestorsAreRejected() {
        val base = Files.createTempDirectory("nrf-template-boundary-")
        val sdk = kit(base)
        assertFails { ProjectGenerator.create(sdk.resolve("sources"), sdk, request()) }
        assertFalse(sdk.resolve("sources").exists())
        val file = base.resolve("file").apply { writeText("keep") }
        assertFails { ProjectGenerator.create(file.resolve("sources"), sdk, request()) }
        assertEquals("keep", file.readText())
    }

    @Test fun symlinkedParentAndDanglingDestinationAreRejectedWithoutTouchingTheirTargets() {
        val base = Files.createTempDirectory("nrf-template-links-")
        val sdk = kit(base)
        val actual = base.resolve("actual").createDirectory()
        val link = base.resolve("linked")
        try { Files.createSymbolicLink(link, actual) } catch (error: Exception) { assumeNoException(error); return }
        assertFails { ProjectGenerator.create(link.resolve("sources"), sdk, request()) }
        assertTrue(actual.listDirectoryEntries().isEmpty())
        Files.createSymbolicLink(actual.resolve("example-mod"), base.resolve("absent"))
        assertFails { ProjectGenerator.create(actual, sdk, request()) }
        assertFalse(base.resolve("absent").exists())
    }

    @Test fun windowsJunctionParentCannotRedirectProjectCreation() {
        assumeTrue(Host.windows)
        val base = Files.createTempDirectory("nrf-template-junction-")
        val sdk = kit(base)
        val actual = base.resolve("actual").createDirectory()
        val link = base.resolve("junction")
        // A junction exercises Windows reparse handling without symbolic-link privileges.
        // Values travel as environment data, never interpolated into shell source.
        val command = "New-Item -ItemType Junction -Path \$env:NRF_TEST_LINK -Target \$env:NRF_TEST_TARGET -ErrorAction Stop | Out-Null"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).apply {
                environment()["NRF_TEST_LINK"] = link.toString()
                environment()["NRF_TEST_TARGET"] = actual.toString()
            }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        assertFails { ProjectGenerator.create(link.resolve("sources"), sdk, request()) }
        assertTrue(actual.listDirectoryEntries().isEmpty())
    }

    /** Optional artifact-producing smoke fixture; actual native builds run separately. */
    @Test fun generateReviewableProjectsWithRealKitWhenRequested() {
        val sdk = System.getenv("NRF_GENERATOR_TEST_KIT")
        val output = System.getenv("NRF_GENERATOR_TEST_OUTPUT")
        assumeTrue(!sdk.isNullOrBlank() && !output.isNullOrBlank())
        ProjectTemplate.entries.forEach { template ->
            val local = ProjectGenerator.create(Path.of(output!!), Path.of(sdk!!), request(template).copy(
                id = "generated-${template.name.lowercase()}", name = "Été ${template.name.lowercase()} ${'$'}{0} \"test\""))
            println("GENERATED_PROJECT=${local.directory}")
        }
    }
}
