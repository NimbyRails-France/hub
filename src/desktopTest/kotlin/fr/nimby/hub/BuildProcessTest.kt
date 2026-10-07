package fr.nimby.hub

import fr.nimby.hub.install.LocalProjects
import fr.nimby.hub.platform.windows.ReadOnlyProcess
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class BuildProcessTest {
    private fun command(vararg args: String): List<String> {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val classpath = listOf(BuildProcessFixture::class.java, kotlin.Unit::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct().joinToString(File.pathSeparator)
        return listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", classpath,
            BuildProcessFixture::class.java.name) + args
    }

    private suspend fun awaitPids(file: Path, size: Int): List<Long> = withTimeout(15_000) {
        while (true) {
            val pids = if (file.exists()) file.readLines().mapNotNull { it.toLongOrNull() } else emptyList()
            if (pids.size == size) return@withTimeout pids
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    private fun alive(id: Long) = ProcessHandle.of(id).map { it.isAlive }.orElse(false)

    private fun cancellation(mode: String, count: Int) = runBlocking {
        val root = Files.createTempDirectory("nrf-build-cancel-")
        val pidFile = root.resolve("pids.txt")
        val build = launch { LocalProjects.run(command(mode, pidFile.toString()), root, "Fixture") {} }
        var pids = emptyList<Long>()
        try {
            pids = awaitPids(pidFile, count)
            assertTrue(pids.all(::alive))
            // The fixture is silent (or has no newline), so cancellation must not
            // depend on reader.readLine returning by itself.
            withTimeout(5_000) { build.cancelAndJoin() }
            withTimeout(5_000) { while (pids.any(::alive)) delay(10) }
        } finally {
            build.cancel()
            pids.forEach { ProcessHandle.of(it).ifPresent { process -> if (process.isAlive) process.destroyForcibly() } }
        }
    }

    @Test fun cancellingSilentBuildStopsIt() = cancellation("silent", 1)
    @Test fun cancellingPartialLineStopsParentAndChild() = cancellation("child", 2)

    @Test fun longLineIsTruncatedWhileLaterLinesAndUtf8RemainAvailable() = runBlocking {
        val root = Files.createTempDirectory("nrf-build-lines-")
        val output = mutableListOf<String>()
        withTimeout(15_000) { LocalProjects.run(command("lines"), root, "Fixture", output::add) }
        assertEquals(listOf("x".repeat(2000), "été", "x".repeat(1999), "", "last"), output)
    }

    @Test fun failingBuildReturnsDiagnosticInsteadOfSuccess() = runBlocking {
        val root = Files.createTempDirectory("nrf-build-failure-")
        val output = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(15_000) { LocalProjects.run(command("fail"), root, "Fixture", output::add) }
        }
        assertTrue(failure.message.orEmpty().contains("Fixture"))
        assertEquals(listOf("failure"), output)
    }

    @Test fun readOnlyQueryBoundsSilenceAndStopsOnlyItsOwnProcess() {
        val root = Files.createTempDirectory("nrf-query-timeout-")
        val pidFile = root.resolve("pid.txt")
        val started = System.nanoTime()
        val failure = assertFailsWith<IllegalStateException> {
            ReadOnlyProcess.capture(command("silent", pidFile.toString()), "", timeoutMs = 1000)
        }
        assertTrue(failure.message.orEmpty().contains("exceeded"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
        val pid = pidFile.readText().toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(alive(pid))
    }

    @Test fun readOnlyQueryRejectsFloodInsteadOfReturningTruncatedJson() {
        val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
            ReadOnlyProcess.capture(command("lines"), "", maximumBytes = 4096)
        }
        assertTrue(failure.cause?.message.orEmpty().contains("exceeds"))
    }

    @Test fun readOnlyQueryWritesStdinWhileDrainingStdout() {
        val request = "é".repeat(100_000)
        val result = ReadOnlyProcess.capture(command("duplex"), request, maximumBytes = 500_000)
        assertEquals(0, result.exitCode)
        assertEquals("x".repeat(100_000) + request, result.output)
    }
}

/** Standalone child VM: exercises real OS pipes rather than interruptible mocks. */
object BuildProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "silent" -> {
                Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString())
                Thread.sleep(60_000)
            }
            "child" -> {
                val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
                val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), BuildProcessFixture::class.java.name,
                    "silent", args[1] + ".child").inheritIO().start()
                System.out.print("partial line")
                System.out.flush()
                Files.writeString(Path.of(args[1]), "${ProcessHandle.current().pid()}\n${child.pid()}")
                Thread.sleep(60_000)
            }
            "lines" -> {
                val bytes = ByteArray(8192) { 'x'.code.toByte() }
                repeat(640) { System.out.write(bytes) }
                System.out.write(("\r\nété\r\n" + "x".repeat(1999) + "🚆\r\n\nlast").toByteArray(Charsets.UTF_8))
            }
            "fail" -> { System.err.println("failure"); System.exit(7) }
            "duplex" -> {
                System.out.write("x".repeat(100_000).toByteArray(Charsets.UTF_8))
                System.out.flush()
                System.out.write(System.`in`.readAllBytes())
            }
        }
    }
}
