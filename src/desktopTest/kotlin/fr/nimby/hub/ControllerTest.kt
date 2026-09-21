package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerTest {
    private open class Source : ReleaseSource {
        var catalogueCalls = 0
        var downloadCalls = 0
        var relayCalls = 0
        override suspend fun catalogue(channels: Map<String, String>): Catalogue { catalogueCalls++; return Catalogue(1, emptyList()) }
        override suspend fun project(id: String, channel: String): Project = error("offline fixture")
        override suspend fun hub(channel: String): HubRelease = error("Hub updater must not run in development")
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) { downloadCalls++ }
        override suspend fun listen(onEvent: suspend () -> Unit) { relayCalls++; awaitCancellation() }
    }

    @Test fun developerModeKeepsCatalogueAndNeverDownloadsAutomatically() = runTest {
        val root = Files.createTempDirectory("nrf-controller-")
        val store = SettingsStore(root)
        store.write(HubSettings(developerMode = true))
        val source = Source()
        val controller = HubController(store, backgroundScope, source)
        try {
            controller.start(); controller.refresh(); runCurrent()
            assertTrue(source.catalogueCalls >= 1)
            assertEquals(1, source.relayCalls)
            assertEquals(0, source.downloadCalls)
            assertTrue(controller.state.value.settings.developerMode)
            assertTrue(store.read().developerMode)
        } finally { controller.close() }
    }

    @Test fun inFlightResponseCannotReturnAfterDeveloperToggle() = runTest {
        val root = Files.createTempDirectory("nrf-controller-")
        val reply = CompletableDeferred<Catalogue>()
        val source = object : Source() {
            override suspend fun catalogue(channels: Map<String, String>): Catalogue {
                catalogueCalls++
                return if (catalogueCalls == 1) withContext(NonCancellable) { reply.await() } else Catalogue(1, emptyList())
            }
        }
        val store = SettingsStore(root)
        store.write(HubSettings(automatic = false))
        val controller = HubController(store, backgroundScope, source)
        try {
            controller.start(); runCurrent()
            assertEquals(1, source.catalogueCalls)
            controller.changeDeveloperMode(true)
            reply.complete(Catalogue(1, listOf(Project("stale", "tco", "9.0.0"))))
            runCurrent()
            controller.state.first { !it.busy }
            assertTrue(controller.state.value.projects.isEmpty())
            assertFalse(controller.state.value.busy)
            assertEquals(0, source.downloadCalls)
            assertTrue(store.read().developerMode)
        } finally { controller.close() }
    }

    @Test fun developerModePreventsPromotionOfAnAlreadyStartedDownload() = runTest {
        val root = Files.createTempDirectory("nrf-download-")
        val game = root.resolve("game").createDirectory()
        val executable = Host.game(game).apply { writeText("fake game, not executable") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var project: Project
        val source = object : Source() {
            override suspend fun catalogue(channels: Map<String, String>) = Catalogue(1, listOf(project))
            override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
                downloadCalls++; entered.complete(Unit)
                withContext(NonCancellable) {
                    release.await()
                    destination.parent.createDirectories()
                    destination.writeText("download finished after developer mode was enabled")
                }
            }
        }
        val store = SettingsStore(root)
        store.write(HubSettings(gameDirectory = game.toString(), automatic = false))
        val controller = HubController(store, backgroundScope, source)
        project = Project("fixture", "sdk", "1.0.0", url = "https://github.com/NimbyRails-France/fixture/releases/download/v1.0.0/test.zip",
            size = 100, sha256 = "a".repeat(64), rootFolder = "Fixture", gameSha256 = listOf(executable.sha256()))
        try {
            controller.start()
            controller.state.first { project.id in it.availableProjects && !it.busy }
            controller.installProject(project, root.resolve("installed"))
            entered.await()
            controller.changeDeveloperMode(true)
            release.complete(Unit); runCurrent()
            // runCurrent drains the test dispatcher, not pending Dispatchers.IO work.
            controller.state.first { !it.busy }
            assertEquals(1, source.downloadCalls)
            assertFalse(root.resolve("installed").exists())
            assertTrue(store.read().installed.isEmpty())
            assertFalse(controller.state.value.busy)
        } finally { controller.close(); release.complete(Unit) }
    }
}
