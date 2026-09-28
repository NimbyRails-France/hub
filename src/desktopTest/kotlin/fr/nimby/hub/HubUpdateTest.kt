package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.*
import fr.nimby.hub.update.SelfUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HubUpdateTest {
    @Test fun hubDownloadsAndInstallsInBothProfilesWhileDevelopmentProjectsStayProtected() = runTest {
        for (profile in HubProfile.entries) {
            val root = Files.createTempDirectory("nrf-hub-update-")
            val game = root.resolve("game").createDirectory()
            val gameHash = Host.game(game).apply { writeText("fixture game") }.sha256()
            val payload = root.resolve("payload").apply { writeText("fixture installer, never executed") }
            val extension = if (Host.windows) "exe" else if (Host.mac) "dmg" else "deb"
            val release = HubRelease(1, "NRFHub", Host.id, "99.0.0",
                "https://github.com/NimbyRails-France/hub/releases/download/v99.0.0/setup.$extension",
                payload.sha256(), payload.fileSize(), "stable")
            val project = Project("sdk", "sdk", "99.0.0", gameSha256 = listOf(gameHash))
            var checks = 0
            val downloads = mutableListOf<String>()
            val launches = mutableListOf<List<String>>()
            val source = object : ReleaseSource {
                override suspend fun catalogue(channels: Map<String, String>) = Catalogue(1, listOf(project))
                override suspend fun project(id: String, channel: String) = project
                override suspend fun hub(channel: String): HubRelease { checks++; return release }
                override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
                    downloads += url
                    assertEquals(release.url, url, "SDK must not update with developer tools enabled")
                    destination.parent.createDirectories()
                    payload.copyTo(destination)
                }
                override suspend fun listen(onEvent: suspend () -> Unit) = awaitCancellation()
            }
            val store = SettingsStore(root)
            store.write(HubSettings(gameDirectory = game.toString(), developerMode = true,
                profile = profile, appliedProfile = profile,
                installed = mapOf("sdk" to InstalledProject("sdk", "sdk", "1.0.0", root.resolve("sdk").toString()))))
            val updater = SelfUpdater(root, source,
                { root.resolve(if (Host.windows) "NRFHub.exe" else "NRFHub") }, { launches += it })
            val controller = HubController(store, backgroundScope, source,
                journal = HubLog(root.resolve("logs")), selfUpdater = updater)
            try {
                controller.start()
                controller.state.first { !it.busy && it.readyHubVersion == release.version }
                assertEquals(1, checks)
                assertEquals(listOf(release.url), downloads)
                assertTrue(controller.quit(relaunch = true))
                assertEquals(1, launches.size, "Hub installer must launch in $profile")
                assertEquals("1.0.0", store.read().installed.getValue("sdk").version)
                assertEquals(profile, store.read().appliedProfile)
            } finally { controller.close() }
        }
    }
}
