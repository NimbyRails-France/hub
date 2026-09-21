package fr.nimby.hub

import fr.nimby.hub.install.LinuxSdkInstallation as Installer
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.sha256
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class LinuxSdkInstallationTest {
    private fun fixture(test: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
        if (!Host.linux) return
        val root = Files.createTempDirectory("nrf-linux-install-")
        try {
            val kit = root.resolve("SDK avec espaces").createDirectory()
            val game = root.resolve("NIMBY Rails").createDirectory()
            game.resolve("nimbyrails").writeText("game unchanged")
            val bytes = ByteArray(64)
            byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(bytes); bytes[18] = 62
            kit.resolve("loader").createDirectory().resolve(Installer.library).writeBytes(bytes)
            test(kit, game)
            assertEquals("game unchanged", game.resolve("nimbyrails").readText())
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun installRemoveAndReinstallPreserveGameAndHandleSpaces() = fixture { kit, game ->
        repeat(2) {
            Installer.install(kit, game)
            Installer.install(kit, game)
            assertTrue(Installer.owns(kit, game))
            assertEquals(kit.resolve("loader/${Installer.library}").sha256(), game.resolve(Installer.library).sha256())
            val launcher = assertNotNull(Installer.installedLauncher(game))
            assertTrue(Files.isExecutable(launcher))
            assertTrue(launcher.readText().contains("LD_PRELOAD=\"./${Installer.library}"))
            assertEquals(0, ProcessBuilder("sh", "-n", launcher.toString()).start().waitFor())
            Installer.remove(kit, game)
            Installer.remove(kit, game)
            assertNull(Installer.installedLauncher(game))
            assertFalse(game.resolve(Installer.library).exists())
        }
    }
    @Test fun foreignAndChangedFilesAreNeverOverwrittenOrRemoved() = fixture { kit, game ->
        val native = game.resolve(Installer.library)
        native.writeText("foreign")
        assertFails { Installer.install(kit, game) }
        assertEquals("foreign", native.readText())
        native.deleteExisting()
        Installer.install(kit, game)
        game.resolve(Installer.launcher).writeText("changed")
        assertFalse(Installer.owns(kit, game))
        assertFails { Installer.remove(kit, game) }
        assertFails { Installer.installedLauncher(game) }
        assertTrue(native.exists())
        assertEquals("changed", game.resolve(Installer.launcher).readText())
    }
    @Test fun interruptedInstallAndRemovalAreRecoverable() = fixture { kit, game ->
        Installer.install(kit, game)
        val marker = game.resolve("NimbyRailsFranceSDK-install.json")
        marker.writeText(marker.readText().replace("\"ready\"", "\"installing\""))
        game.resolve(Installer.launcher).deleteExisting()
        assertTrue(Installer.owns(kit, game))
        assertFails { Installer.installedLauncher(game) }
        Installer.install(kit, game)
        assertNotNull(Installer.installedLauncher(game))
        marker.writeText(marker.readText().replace("\"ready\"", "\"removing\""))
        game.resolve(Installer.library).deleteExisting()
        assertTrue(Installer.owns(kit, game))
        Installer.remove(kit, game)
        assertFalse(marker.exists())
        assertFalse(game.resolve(Installer.launcher).exists())
    }
}
