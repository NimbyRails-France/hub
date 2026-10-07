package fr.nimby.hub

import fr.nimby.hub.install.InstallRequest
import fr.nimby.hub.install.ProjectManager
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.platform.Windows
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ProjectDevelopmentStatusTest {
    private fun project(status: String? = null, version: String = "1.0.0") = Project(
        "fixture", "tco", version, "Fixture", rootFolder = "Fixture", platform = Host.id,
        url = "https://github.com/NimbyRails-France/fixture/releases/download/v$version/fixture.zip",
        sha256 = "a".repeat(64), size = 12, gameSha256 = listOf("b".repeat(64)), developmentStatus = status,
    )

    @Serializable private data class LegacyProject(val id: String, val kind: String, val version: String)

    @Test fun optionalMetadataDoesNotInferMaturityOrRejectFutureStatuses() {
        val old = hubJson.decodeFromString<Project>("""{"id":"fixture","kind":"tco","version":"1.0.0"}""")
        assertNull(old.developmentStatus)
        for (status in listOf(null, "in-development", "stable", "future-status")) {
            for (version in listOf("1.0.0", "1.1.0-alpha.1", "1.1.0-beta.1")) {
                val candidate = project(status, version)
                ProjectRules.validate(candidate)
                val encoded = hubJson.encodeToString(candidate)
                assertEquals(status, hubJson.decodeFromString<Project>(encoded).developmentStatus)
                // Previous consumers ignore the optional field without needing a schema migration.
                assertEquals(LegacyProject(candidate.id, candidate.kind, version), hubJson.decodeFromString<LegacyProject>(encoded))
            }
        }
    }

    @Test fun catalogueAndInstalledSettingsRetainStatusWithoutNetwork() {
        val base = Files.createTempDirectory("nrf-project-status-")
        for (status in listOf(null, "in-development", "stable", "future-status")) {
            val candidate = project(status)
            val record = InstalledProject(candidate.id, candidate.kind, candidate.version, base.resolve("installed").toString(),
                name = candidate.name, developmentStatus = status)
            assertEquals(status, record.asProject().developmentStatus)
            val catalogue = hubJson.decodeFromString<Catalogue>(hubJson.encodeToString(Catalogue(1, listOf(candidate))))
            val store = SettingsStore(base.resolve(status ?: "absent"))
            store.write(HubSettings(installed = mapOf(record.id to record),
                activeDevelopment = mapOf(record.id to record), development = DevelopmentSettings(
                    prepared = mapOf(record.id to record), projects = mapOf(candidate.id to
                        LocalProject(candidate, base.toString(), "project.json", task = "packageTool")))))
            val restored = store.read()
            assertEquals(status, restored.installed.getValue(record.id).developmentStatus)
            assertEquals(status, restored.activeDevelopment.getValue(record.id).developmentStatus)
            assertEquals(status, restored.development.prepared.getValue(record.id).developmentStatus)
            assertEquals(status, restored.development.projects.getValue(record.id).project.developmentStatus)
            assertEquals(status, HubState(restored).visibleProjects.single().developmentStatus)
            assertEquals(status, HubState(restored, projects = catalogue.projects).visibleProjects.single().developmentStatus)
        }
    }

    @Test fun selectedSourceOwnsTheDisplayedNameAndStatusIncludingAnAbsentStatus() {
        val published = project("stable").copy(name = "Published")
        val local = LocalProject(project().copy(name = "Local source"), "C:/sources", "mod.json", task = "packageMod")
        val prepared = InstalledProject(published.id, published.kind, "0.9.0", "C:/prepared",
            name = "Prepared", developmentStatus = "in-development")
        val settings = HubSettings(developerMode = true, profile = HubProfile.DEVELOP,
            development = DevelopmentSettings(projects = mapOf(local.project.id to local),
                prepared = mapOf(prepared.id to prepared), origins = mapOf(published.id to ModOrigin.LOCAL)))
        val state = HubState(settings, projects = listOf(published))
        assertEquals(local.project, state.visibleProjects.single())
        assertNull(state.visibleProjects.single().developmentStatus, "An absent local status must not inherit a published or prepared badge")
        val fromPrepared = state.copy(settings = settings.copy(development = settings.development.copy(projects = emptyMap())))
        assertEquals(prepared.asProject(), fromPrepared.visibleProjects.single())
        val publishedSelected = state.copy(settings = settings.copy(development = settings.development.copy(
            origins = mapOf(published.id to ModOrigin.PUBLISHED))))
        assertEquals(published, publishedSelected.visibleProjects.single())
        assertEquals(published, state.copy(settings = settings.copy(profile = HubProfile.PLAY)).visibleProjects.single())
    }

    @Test fun localOnlyProjectsAndOfflineInstallationsRemainVisibleWithTheirOwnMetadata() {
        val record = InstalledProject("fixture", "tco", "1.0.0", "C:/installed", developmentStatus = "stable")
        val local = LocalProject(project("in-development").copy(id = "local-only"), "C:/sources", "project.json", "packageTool")
        val settings = HubSettings(developerMode = true, profile = HubProfile.DEVELOP,
            installed = mapOf(record.id to record), development = DevelopmentSettings(
                projects = mapOf(local.project.id to local), origins = mapOf(local.project.id to ModOrigin.LOCAL)))
        val visible = HubState(settings, releaseErrors = mapOf("unavailable" to "offline")).visibleProjects.associateBy { it.id }
        assertEquals("stable", visible.getValue(record.id).developmentStatus)
        assertEquals("in-development", visible.getValue(local.project.id).developmentStatus)
        assertNull(visible.getValue("unavailable").developmentStatus)
    }

    @Test fun installationAndRollbackPersistTheStatusOfTheActualPackage() {
        val base = Files.createTempDirectory("nrf-status-install-")
        val game = base.resolve("game").createDirectory()
        val gameHash = Host.game(game).apply { writeText("isolated fake game") }.sha256()
        val destination = base.resolve("installed")
        val platform = object : Windows() {
            override fun requireClosed(game: Path, destination: Path) {}
            override fun checkTree(path: Path) {}
            override fun shortcut(id: String, destination: Path, remove: Boolean) {}
        }
        val manager = ProjectManager(platform)
        fun install(version: String, status: String): InstalledProject {
            val archive = base.resolve("fixture-$version.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Fixture/${Host.tcoName}"))
                zip.write("fake executable $version".toByteArray())
                zip.closeEntry()
            }
            val candidate = project(status, version).copy(gameSha256 = listOf(gameHash), size = archive.fileSize(), sha256 = archive.sha256())
            return assertNotNull(manager.execute(InstallRequest("install", candidate, destination.toString(), game.toString(),
                base.resolve("result.json").toString(), archive.toString(), expectedGameHash = gameHash)))
        }
        val original = install("1.0.0", "in-development")
        assertEquals("in-development", original.developmentStatus)
        val updated = install("1.1.0", "future-status")
        assertEquals("future-status", updated.developmentStatus, "A future display status must not block installing a valid package")
        assertEquals("future-status", hubJson.decodeFromString<InstalledProject>(destination.resolve(".nrf-project.json").jsonText()).developmentStatus)
        val restored = assertNotNull(manager.execute(InstallRequest("rollback", updated.asProject(), destination.toString(), game.toString(),
            base.resolve("rollback-result.json").toString())))
        assertEquals(original, restored)
        assertEquals("in-development", hubJson.decodeFromString<InstalledProject>(destination.resolve(".nrf-project.json").jsonText()).developmentStatus)
    }
}
