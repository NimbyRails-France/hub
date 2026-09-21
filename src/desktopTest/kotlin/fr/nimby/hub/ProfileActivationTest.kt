package fr.nimby.hub

import fr.nimby.hub.install.ProfileActivation
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.platform.Windows
import fr.nimby.hub.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileActivationTest {
    private class Platform : Windows() {
        override val supported = true
        val targets = mutableMapOf<Path, Path>()
        val calls = mutableListOf<String>()
        var activeSdk: Path? = null
        var failSdk: Path? = null
        var failLink: Path? = null
        var running = false
        var rejectClose = false
        var launches = 0
        override fun requireClosed(game: Path, destination: Path) { check(!running) { "Jeu ouvert" } }
        override fun gameRunning(game: Path) = running
        override fun requestGameClose(game: Path) { calls += "close"; check(!rejectClose) { "Fermeture refusée" }; running = false }
        override fun launchGame(game: Path) { calls += "launch"; launches++; running = true }
        override fun checkTree(path: Path) {}
        override fun linkTarget(path: Path) = targets[path]?.toString()
        override fun removeLink(path: Path, target: Path) { check(targets[path] == null || targets[path] == target); targets.remove(path) }
        override fun createLink(path: Path, target: Path) {
            if (failLink == path) { failLink = null; error("Jonction refusée") }
            check(targets[path] == null || targets[path] == target); targets[path] = target
        }
        override fun proxy(directory: Path, game: Path, action: String) {
            calls += "$action:$directory"
            if (action == "Remove") { check(activeSdk == directory); activeSdk = null }
            else { if (failSdk == directory) error("SDK incompatible"); check(activeSdk == null); activeSdk = directory }
        }
    }
    private val source = object : ReleaseSource {
        override suspend fun catalogue(channels: Map<String, String>) = Catalogue(1, emptyList())
        override suspend fun project(id: String, channel: String): Project = error("No network")
        override suspend fun hub(channel: String): HubRelease = error("No network")
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) = error("No network")
        override suspend fun listen(onEvent: suspend () -> Unit) = awaitCancellation()
    }
    private class Fixture {
        val root = Files.createTempDirectory("nrf-profiles-")
        val game = root.resolve("game").createDirectory()
        val hash = Host.game(game).apply { writeText("fake game") }.sha256()
        fun record(id: String, kind: String, folder: String, version: String = "0.7.3"): InstalledProject {
            val dir = root.resolve(folder).createDirectories()
            dir.resolve("config.ini").writeText("original settings")
            return InstalledProject(id, kind, version, dir.toString(), gameSha256 = listOf(hash), loaderApi = 1,
                module = if (kind == "native-mod") "Mod.dll" else null, modId = if (kind == "native-mod") id else null,
                modLink = if (kind == "native-mod") root.resolve("resources/$id").toString() else null,
                loaderLink = if (kind == "native-mod") game.resolve("NRFMods/$id").toString() else null,
                installedUtc = folder, origin = "published").also { dir.resolve(".nrf-project.json").atomicWrite(hubJson.encodeToString(it)) }
        }
    }

    @Test fun sdkAndModSwitchThenRestoreWithoutChangingPublishedFiles() {
        val f = Fixture(); val platform = Platform(); val activation = ProfileActivation(platform)
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal-sdk", "0.7.2"), "signals" to f.record("signals", "native-mod", "normal-mod"))
        val testing = normal + ("sdk" to f.record("sdk", "sdk", "alternate-sdk"))
        val copies = activation.prepareDevelopment(testing, f.root.resolve("development"), f.game, emptyMap())
        platform.activeSdk = Path(normal.getValue("sdk").directory)
        platform.targets.putAll(activation.links(normal))
        val journal = f.root.resolve("activation.json")
        activation.activate(f.game, normal, copies, journal) {}
        assertEquals(Path(copies.getValue("sdk").directory), platform.activeSdk)
        assertEquals(activation.links(copies), platform.targets)
        Path(copies.getValue("signals").directory, "config.ini").writeText("test settings")
        activation.activate(f.game, copies, normal, journal) {}
        assertEquals(Path(normal.getValue("sdk").directory), platform.activeSdk)
        assertEquals(activation.links(normal), platform.targets)
        assertEquals("original settings", Path(normal.getValue("signals").directory, "config.ini").readText())
        assertFalse(journal.exists())
    }
    @Test fun installedSdkOwnershipUsesHostLibraryAndRejectsChangedBinary() {
        val f = Fixture(); val platform = Platform(); val activation = ProfileActivation(platform)
        val sdk = f.record("sdk", "sdk", "owned-sdk")
        val name = Host.sdkLibraryNames.first()
        Path(sdk.directory, "loader").createDirectory().resolve(name).writeText("owned SDK")
        val installed = f.game.resolve(name).apply { writeText("owned SDK") }
        f.game.resolve("NimbyRailsFranceSDK-install.json").writeText("{}")
        val records = mapOf("sdk" to sdk)
        val journal = f.root.resolve("activation.json")
        var persisted = false
        activation.activate(f.game, records, records, journal) { persisted = true }
        assertTrue(persisted)
        assertFalse(journal.exists())
        installed.writeText("foreign SDK")
        assertFailsWith<IllegalArgumentException> { activation.activate(f.game, records, records, journal) {} }
        assertFalse(journal.exists())
        assertTrue(platform.calls.isEmpty())
    }

    @Test fun failedLinkActivationRestoresSdkAndEveryOldLink() {
        val f = Fixture(); val p = Platform(); val activation = ProfileActivation(p)
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal"), "signals" to f.record("signals", "native-mod", "mod"))
        val next = activation.prepareDevelopment(normal, f.root.resolve("dev"), f.game, emptyMap())
        p.activeSdk = Path(normal.getValue("sdk").directory); p.targets.putAll(activation.links(normal))
        p.failLink = Path(next.getValue("signals").loaderLink!!)
        val journal = f.root.resolve("activation.json")
        var persisted = false
        assertFails { activation.activate(f.game, normal, next, journal) { persisted = true } }
        assertFalse(persisted)
        assertEquals(Path(normal.getValue("sdk").directory), p.activeSdk)
        assertEquals(activation.links(normal), p.targets)
        assertFalse(journal.exists())
    }

    @Test fun failedSdkInstallationRestoresNormalSdkAndDoesNotPersist() {
        val f = Fixture(); val p = Platform(); val activation = ProfileActivation(p)
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal"))
        val next = mapOf("sdk" to f.record("sdk", "sdk", "next"))
        p.activeSdk = Path(normal.getValue("sdk").directory); p.failSdk = Path(next.getValue("sdk").directory)
        var persisted = false
        assertFails { activation.activate(f.game, normal, next, f.root.resolve("activation.json")) { persisted = true } }
        assertFalse(persisted)
        assertEquals(Path(normal.getValue("sdk").directory), p.activeSdk)
    }

    @Test fun foreignJunctionIsRejectedBeforeTouchingSdk() {
        val f = Fixture(); val p = Platform(); val activation = ProfileActivation(p)
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal"), "signals" to f.record("signals", "native-mod", "mod"))
        p.targets[Path(normal.getValue("signals").modLink!!)] = f.root.resolve("foreign")
        assertFails { activation.activate(f.game, normal, emptyMap(), f.root.resolve("activation.json")) {} }
        assertTrue(p.calls.isEmpty())
    }

    @Test fun sdkInstalledOutsideHubIsNeverRemovedWhenTestActivationFails() {
        val f = Fixture(); val p = Platform(); val activation = ProfileActivation(p)
        f.game.resolve("NimbyRailsFranceSDK-install.json").writeText("foreign installation")
        val next = mapOf("sdk" to f.record("sdk", "sdk", "test-sdk"))
        assertFails { activation.activate(f.game, emptyMap(), next, f.root.resolve("activation.json")) {} }
        assertTrue(p.calls.isEmpty())
        assertEquals("foreign installation", f.game.resolve("NimbyRailsFranceSDK-install.json").readText())
    }

    @Test fun disablingDevelopmentRestoresNormalSdkBeforeHidingLocalControls() = runTest {
        val f = Fixture(); val p = Platform()
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal", "0.7.2"))
        val dev = mapOf("sdk" to f.record("sdk", "sdk", "dev", "0.7.3"))
        p.activeSdk = Path(dev.getValue("sdk").directory)
        val store = SettingsStore(f.root.resolve("data"))
        store.write(HubSettings(gameDirectory = f.game.toString(), developerMode = true, profile = HubProfile.DEVELOP,
            appliedProfile = HubProfile.DEVELOP, installed = normal, activeDevelopment = dev))
        val controller = HubController(store, backgroundScope, source, windows = p)
        try {
            controller.changeDeveloperMode(false)
            controller.state.first { !it.busy }
            assertNull(controller.state.value.operationError)
            assertEquals(Path(normal.getValue("sdk").directory), p.activeSdk)
            assertFalse(store.read().developerMode)
            assertEquals(HubProfile.PLAY, store.read().appliedProfile)
            assertEquals(normal, store.read().installed)
        } finally { controller.close() }
    }

    @Test fun restartRequestsNormalClosureAndLaunchesOnlyAfterActivation() = runTest {
        val f = Fixture(); val p = Platform(); p.running = true
        val normal = mapOf("sdk" to f.record("sdk", "sdk", "normal"))
        p.activeSdk = Path(normal.getValue("sdk").directory)
        val store = SettingsStore(f.root.resolve("data")); store.write(HubSettings(gameDirectory = f.game.toString(), installed = normal))
        val controller = HubController(store, backgroundScope, source, windows = p)
        try {
            controller.launchGame(restart = true)
            controller.state.first { !it.busy }
            assertNull(controller.state.value.operationError)
            assertEquals(listOf("close", "launch"), p.calls)
            assertEquals(1, p.launches)
        } finally { controller.close() }
    }

    @Test fun refusedGameClosureDoesNotSwitchSdkOrLaunchAnotherGame() = runTest {
        val f = Fixture(); val p = Platform(); p.running = true; p.rejectClose = true
        val store = SettingsStore(f.root.resolve("data")); store.write(HubSettings(gameDirectory = f.game.toString()))
        val controller = HubController(store, backgroundScope, source, windows = p)
        try {
            controller.launchGame(restart = true)
            controller.state.first { !it.busy }
            assertNotNull(controller.state.value.operationError)
            assertEquals(listOf("close"), p.calls)
            assertEquals(0, p.launches)
            assertTrue(p.running)
        } finally { controller.close() }
    }
}
