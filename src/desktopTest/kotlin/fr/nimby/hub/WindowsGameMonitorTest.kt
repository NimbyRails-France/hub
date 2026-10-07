package fr.nimby.hub

import fr.nimby.hub.platform.windows.RunningExecutable
import java.nio.file.Path
import kotlin.test.*

class WindowsGameMonitorTest {
    private val monitor = RunningExecutable()
    private fun runningExecutable(executable: Path, fallback: () -> Boolean) = monitor.isRunning(executable, fallback)
    private val current = Path.of(ProcessHandle.current().info().command().orElseThrow())

    @Test fun identifiedExecutableNeedsNoShellProcess() {
        assertTrue(runningExecutable(current) { error("A known running process needs no fallback") })
    }

    @Test fun missingExecutableStillUsesConservativeNameCheck() {
        val missing = current.resolveSibling("nrf-nonexistent-game-monitor-fixture.exe")
        assertTrue(runningExecutable(missing) { true })
        assertFalse(runningExecutable(missing) { false })
    }

    @Test fun sameFileNameInAnotherDirectoryDoesNotReusePositiveResult() {
        assertTrue(runningExecutable(current) { false })
        val other = current.parent.resolve("nrf-nonexistent-monitor-fixture").resolve(current.fileName)
        var checked = false
        assertFalse(runningExecutable(other) { checked = true; false })
        assertTrue(checked)
    }

    @Test fun uncertainFallbackFailurePropagatesToCaller() {
        val missing = current.resolveSibling("nrf-nonexistent-game-monitor-fixture.exe")
        assertFailsWith<IllegalStateException> { runningExecutable(missing) { error("Process information unavailable") } }
    }
}
