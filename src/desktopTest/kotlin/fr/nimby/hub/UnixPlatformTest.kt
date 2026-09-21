package fr.nimby.hub

import fr.nimby.hub.platform.UnixPlatform
import fr.nimby.hub.platform.Host
import fr.nimby.hub.install.InstallRequest
import fr.nimby.hub.install.ProjectManager
import fr.nimby.hub.model.Project
import fr.nimby.hub.storage.sha256
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class UnixPlatformTest {
    @Test fun mappedSdkOutsideTheExecutableDirectoryPreventsReplacement() {
        if (!Host.linux) return
        val root = Files.createTempDirectory("nrf-unix-mapped-")
        var child: Process? = null
        try {
            val sdk = root.resolve("SDK with spaces").createDirectory()
            val mapped = sdk.resolve("runtime.so").apply { writeBytes(ByteArray(4096)) }
            val alias = root.resolve("sdk-alias")
            Files.createSymbolicLink(alias, sdk)
            val javaExecutable = Path(System.getProperty("java.home"), "bin", "java")
            val classes = java.nio.file.Path.of(MappedInstallationFixture::class.java.protectionDomain.codeSource.location.toURI())
            val kotlin = java.nio.file.Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
            child = ProcessBuilder(javaExecutable.toString(), "-cp", "$classes:$kotlin",
                MappedInstallationFixture::class.java.name, mapped.toString()).start()
            val deadline = System.nanoTime() + 10_000_000_000L
            val reader = child.inputStream.bufferedReader()
            while (child.isAlive && !reader.ready() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(reader.ready(), "Mapping fixture did not become ready")
            assertEquals("mapped", reader.readLine())
            val platform = UnixPlatform()
            assertFailsWith<IllegalArgumentException> { platform.requireClosed(root.resolve("game"), alias) }
            platform.requireClosed(root.resolve("game"), root.resolve("unrelated"))
            child.outputStream.close()
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            platform.requireClosed(root.resolve("game"), alias)
        } finally {
            child?.destroy(); child?.waitFor()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun runningGameIsRecognizedThroughSteamLinksAndNeverTerminated() {
        if (!Host.linux) return
        val root = Files.createTempDirectory("nrf-unix-running-")
        var child: Process? = null
        try {
            val game = root.resolve("game").createDirectory()
            val binary = game.resolve("nimbyrails")
            Files.copy(Path("/bin/bash"), binary)
            binary.toFile().setExecutable(true, true)
            val alias = root.resolve("Steam library")
            Files.createSymbolicLink(alias, game)
            // Block in a shell builtin so /proc/<pid>/exe remains our fixture.
            child = ProcessBuilder(binary.toString(), "-c", "read -r -t 30 value").start()
            val platform = UnixPlatform()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (child.isAlive && !platform.gameRunning(alias) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(child.isAlive, "Fixture failed: " + if (child.isAlive) "" else child.errorStream.bufferedReader().readText())
            assertTrue(platform.gameRunning(alias))
            assertFails { platform.requireClosed(alias, root.resolve("SDK")) }
            assertFails { platform.requestGameClose(alias) }
            assertTrue(child.isAlive)
        } finally {
            child?.destroy(); child?.waitFor()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun nativeModInstallationProducesUtf8ManifestAndOwnedLinks() {
        if (!Host.linux) return
        val root = Files.createTempDirectory("nrf-unix-mod-")
        try {
            val game = root.resolve("game").createDirectory()
            val hash = Host.game(game).apply { writeText("fixture game") }.sha256()
            val archive = root.resolve("mod.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("mod.txt", "Example.so")) {
                    zip.putNextEntry(ZipEntry("Example/$name")); zip.write("fixture".toByteArray()); zip.closeEntry()
                }
            }
            val project = Project("example", "native-mod", "1.0.0", size = archive.fileSize(), sha256 = archive.sha256(),
                rootFolder = "Example", gameSha256 = listOf(hash), platform = Host.id, modId = "Example", module = "Example.so", loaderApi = 1)
            val destination = root.resolve("installed")
            val resources = root.resolve("resources")
            val request = InstallRequest("install", project, destination.toString(), game.toString(), root.resolve("result.json").toString(),
                archive.toString(), hash, resources.toString())
            val manager = ProjectManager(UnixPlatform())
            manager.execute(request)
            assertEquals("[NRFMod]\nlibrary=Example.so\n", destination.resolve("nrf-mod.ini").readText())
            assertEquals(destination, game.resolve("NRFMods/example").toRealPath())
            assertEquals(destination, resources.resolve("Example").toRealPath())
            manager.execute(request.copy(action = "remove"))
            assertFalse(destination.exists())
            assertFalse(game.resolve("NRFMods/example").exists(java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("fixture game", Host.game(game).readText())
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun ownedLinkRemovalKeepsTargetAndRejectsForeignLinks() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("nrf-unix-links-")
        val target = root.resolve("target").createDirectory()
        target.resolve("keep").writeText("preserved")
        val other = root.resolve("other").createDirectory()
        val link = root.resolve("link")
        val platform = UnixPlatform()
        try {
            platform.createLink(link, target)
            assertEquals(target.toString(), platform.linkTarget(link))
            assertFailsWith<IllegalArgumentException> { platform.removeLink(link, other) }
            assertFailsWith<IllegalArgumentException> { platform.checkTree(root) }
            platform.removeLink(link, target)
            assertEquals("preserved", target.resolve("keep").readText())
            platform.checkTree(root)
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}

object MappedInstallationFixture {
    @JvmStatic fun main(arguments: Array<String>) {
        java.nio.channels.FileChannel.open(Path(arguments.single()), java.nio.file.StandardOpenOption.READ).use { channel ->
            val mapping = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            println("mapped")
            System.out.flush()
            System.`in`.read()
            check(mapping.get(0) == 0.toByte()) // Keep the mapping live until the parent releases us.
        }
    }
}
