package fr.nimby.hub

import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Windows
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.*
import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*

class SdkPromotionTest {
    /** Transaction tests inject the OS boundary; fixture text is never treated
     * as a supported game DLL or passed to the real Windows preflight. */
    private class Platform : Windows() {
        var active = ""
        var failVersion = ""
        val calls = mutableListOf<String>()
        override fun requireClosed(game: Path, destination: Path) {}
        override fun checkTree(path: Path) {}
        override fun proxy(directory: Path, game: Path, action: String) {
            val version = directory.resolve("version.txt").readText()
            calls += "$action:$version"
            if (action == "Remove") active = "" else {
                check(version != failVersion) { "SDK Install failed" }
                active = version
            }
        }
    }

    @Test fun failedSdkUpdateRestoresDistributionAndRuntime() {
        val root = Files.createTempDirectory("nrf-promotion-")
        val game = root.resolve("game").createDirectory()
        val hash = Host.game(game).apply { writeText("fixture") }.sha256()
        val platform = Platform()
        val manager = ProjectManager(platform)
        fun install(version: String) {
            val archive = root.resolve("$version.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                mapOf(Host.proxyInstaller to "fixture installer", "version.txt" to version).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry("Fixture/$name")); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            val project = Project("sdk", "sdk", version, rootFolder = "Fixture", size = archive.fileSize(), sha256 = archive.sha256(), gameSha256 = listOf(hash), loaderApi = 1)
            manager.execute(InstallRequest("install", project, root.resolve("sdk").toString(), game.toString(), root.resolve("result.json").toString(), archive.toString(), hash))
        }
        install("0.7.3")
        platform.failVersion = "0.8.0"
        assertFails { install("0.8.0") }
        assertEquals("0.7.3", root.resolve("sdk/version.txt").readText())
        assertEquals("0.7.3", platform.active)
        assertEquals(listOf("Install:0.7.3", "Remove:0.7.3", "Install:0.8.0", "Install:0.7.3"), platform.calls)
    }

    @Test fun reapplyingSameProfileReinstallsProxyAfterRepair() {
        val root = Files.createTempDirectory("nrf-reapply-")
        val game = root.resolve("game").createDirectory()
        val hash = Host.game(game).apply { writeText("fixture") }.sha256()
        val sdk = root.resolve("sdk").createDirectory()
        sdk.resolve("version.txt").writeText("0.8.0")
        val records = mapOf("sdk" to InstalledProject("sdk", "sdk", "0.8.0", sdk.toString(), gameSha256 = listOf(hash)))
        val platform = Platform()
        var persisted = false
        ProfileActivation(platform).activate(game, records, records, root.resolve("activation.json")) { persisted = true }
        assertTrue(persisted)
        assertEquals("0.8.0", platform.active)
        assertEquals(listOf("Remove:0.8.0", "Install:0.8.0"), platform.calls)
    }
}
