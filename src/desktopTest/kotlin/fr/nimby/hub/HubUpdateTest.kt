package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.*
import fr.nimby.hub.update.SelfUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HubUpdateTest {
    private class UpdateFixture(
        profile: HubProfile = HubProfile.PLAY,
        automatic: Boolean = true,
    ) {
        val root = Files.createTempDirectory("nrf-hub-restart-")
        private val game = root.resolve("game").createDirectory().also { Host.game(it).writeText("fixture game, never executed") }
        private val payload = root.resolve("payload").apply { writeText("fixture installer, never executed") }
        private val extension = if (Host.windows) "exe" else if (Host.mac) "dmg" else "deb"
        val release = HubRelease(1, "NRFHub", Host.id, "99.0.0",
            "https://github.com/NimbyRails-France/hub/releases/download/v99.0.0/setup.$extension",
            payload.sha256(), payload.fileSize(), "stable")
        var catalogueChecks = 0
        var downloads = 0
        var listenerStarts = 0
        var listenerCancellations = 0
        var downloadedInstaller: Path? = null
        var launchFailure: IOException? = null
        var launchAttempts = 0
        var currentExecutable: Path? = root.resolve(if (Host.windows) "NRFHub.exe" else "NRFHub")
        val launches = mutableListOf<List<String>>()
        val source = object : ReleaseSource {
            override suspend fun catalogue(channels: Map<String, String>): Catalogue {
                catalogueChecks++
                return Catalogue(1, emptyList())
            }
            override suspend fun project(id: String, channel: String): Project = error("No project download expected")
            override suspend fun hub(channel: String) = release
            override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
                assertEquals(release.url, url)
                assertEquals(release.size, size)
                assertEquals(release.sha256, hash)
                destination.parent.createDirectories()
                payload.copyTo(destination)
                downloads++
                downloadedInstaller = destination
            }
            override suspend fun listen(onEvent: suspend () -> Unit) {
                listenerStarts++
                try { awaitCancellation() } finally { listenerCancellations++ }
            }
        }
        val store = SettingsStore(root).also {
            it.write(HubSettings(language = "fr", gameDirectory = game.toString(), automatic = automatic,
                developerMode = profile == HubProfile.DEVELOP, profile = profile, appliedProfile = profile))
        }
        val updater = SelfUpdater(root, source,
            { currentExecutable }, { command ->
                launchAttempts++
                launchFailure?.let { throw it }
                launches += command
            })
        fun controller(scope: CoroutineScope) = HubController(store, scope, source,
            journal = HubLog(root.resolve("logs")), selfUpdater = updater)
    }

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

    @Test fun explicitRestartAppliesPreparedUpdateAfterAutomaticUpdatesAreDisabledInEitherProfile() = runTest {
        for (profile in HubProfile.entries) {
            val fixture = UpdateFixture(profile)
            val controller = fixture.controller(backgroundScope)
            try {
                controller.start()
                controller.state.first { !it.busy && it.readyHubVersion == fixture.release.version }
                controller.changeAutomatic(false)
                val before = fixture.store.read()
                assertFalse(before.automatic)

                assertTrue(controller.quit(relaunch = true), "Prepared update must apply explicitly in $profile")
                assertEquals(1, fixture.launchAttempts)
                assertEquals(1, fixture.launches.size)
                if (Host.windows) assertTrue("/RELAUNCH" in fixture.launches.single())
                assertNull(controller.state.value.readyHubVersion)
                assertEquals(before, fixture.store.read(), "Restart must not change the selected game profile")

                assertFalse(controller.quit(relaunch = true), "A consumed update must not launch twice")
                assertEquals(1, fixture.launchAttempts)
            } finally { controller.close() }
        }
    }

    @Test fun restartWithoutAPreparedInstallerStaysOpenAndKeepsSynchronizationActive() = runTest {
        val fixture = UpdateFixture(automatic = false)
        val controller = fixture.controller(backgroundScope)
        try {
            controller.start()
            controller.state.first { !it.busy }
            runCurrent()
            assertEquals(1, fixture.listenerStarts)
            assertEquals(0, fixture.downloads)

            assertFalse(controller.quit(relaunch = true))
            runCurrent()
            assertEquals(0, fixture.launchAttempts)
            assertEquals(0, fixture.listenerCancellations)
            assertTrue(assertNotNull(controller.state.value.operationError).contains("Aucune mise à jour du Hub prête à installer"))

            val checks = fixture.catalogueChecks
            controller.refresh()
            controller.state.first { !it.busy }
            assertEquals(checks + 1, fixture.catalogueChecks)
        } finally { controller.close() }
    }

    @Test fun installerLaunchFailureIsVisibleKeepsThePreparedUpdateAndNetworkAndCanBeRetriedOnce() = runTest {
        val fixture = UpdateFixture()
        val controller = fixture.controller(backgroundScope)
        try {
            controller.start()
            controller.state.first { !it.busy && it.readyHubVersion == fixture.release.version }
            runCurrent()
            fixture.launchFailure = IOException("fixture installer launch refused")

            assertFalse(controller.quit(relaunch = true))
            runCurrent()
            assertEquals(1, fixture.launchAttempts)
            assertTrue(fixture.launches.isEmpty())
            assertEquals(fixture.release.version, controller.state.value.readyHubVersion)
            assertEquals(0, fixture.listenerCancellations)
            val error = assertNotNull(controller.state.value.operationError)
            assertTrue(error.contains("Mise à jour du Hub impossible"))
            assertTrue(error.contains("fixture installer launch refused"))

            controller.clearError()
            fixture.launchFailure = null
            assertTrue(controller.quit(relaunch = true))
            runCurrent()
            assertEquals(2, fixture.launchAttempts)
            assertEquals(1, fixture.launches.size)
            assertEquals(1, fixture.listenerCancellations)
            assertFalse(controller.quit(relaunch = true))
            assertEquals(2, fixture.launchAttempts)
        } finally { controller.close() }
    }

    @Test fun preparedUpdateWithAnUnsupportedExecutableStaysOpenWithAPreciseReason() = runTest {
        val fixture = UpdateFixture()
        val controller = fixture.controller(backgroundScope)
        try {
            controller.start()
            controller.state.first { !it.busy && it.readyHubVersion == fixture.release.version }
            runCurrent()
            fixture.currentExecutable = fixture.root.resolve("java.exe")

            assertFalse(controller.quit(relaunch = true))
            runCurrent()
            assertEquals(0, fixture.launchAttempts)
            assertEquals(fixture.release.version, controller.state.value.readyHubVersion)
            assertEquals(0, fixture.listenerCancellations)
            val error = assertNotNull(controller.state.value.operationError)
            assertTrue(error.contains("exécutable NRFHub installé"))
            assertFalse(error.contains("Aucune mise à jour du Hub prête"))
        } finally { controller.close() }
    }

    @Test fun missingOrChangedPreparedInstallerCannotLaunchAndAFreshDownloadRestoresReadiness() = runTest {
        for (remove in listOf(false, true)) {
            val fixture = UpdateFixture()
            val controller = fixture.controller(backgroundScope)
            try {
                controller.start()
                controller.state.first { !it.busy && it.readyHubVersion == fixture.release.version }
                runCurrent()
                val old = assertNotNull(fixture.downloadedInstaller)
                if (remove) old.deleteExisting() else old.writeText("x".repeat(fixture.release.size.toInt()))

                assertFalse(controller.quit(relaunch = true))
                runCurrent()
                assertEquals(0, fixture.launchAttempts)
                assertNull(controller.state.value.readyHubVersion)
                assertEquals(0, fixture.listenerCancellations)
                assertTrue(assertNotNull(controller.state.value.operationError).contains("Installateur Hub absent ou modifié"))

                controller.clearError()
                controller.refresh()
                controller.state.first { !it.busy && it.readyHubVersion == fixture.release.version }
                assertEquals(2, fixture.downloads)
                assertNotEquals(old, fixture.downloadedInstaller)
                assertTrue(controller.quit(relaunch = true))
                assertEquals(1, fixture.launches.size)
            } finally { controller.close() }
        }
    }
}
