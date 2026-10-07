package fr.nimby.hub.platform.windows

import java.io.ByteArrayOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Bounded OS queries. Never use a deadline to interrupt an installation transaction. */
internal object ReadOnlyProcess {
    data class Result(val exitCode: Int, val output: String)

    fun capture(command: List<String>, input: String, timeoutMs: Long = 30_000,
                maximumBytes: Int = 1024 * 1024): Result {
        require(timeoutMs > 0 && maximumBytes > 0)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = FutureTask {
            process.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream(minOf(maximumBytes, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(count <= maximumBytes - bytes.size()) { "Windows query output exceeds $maximumBytes bytes" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8)
            }
        }
        val writer = FutureTask {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
        }
        try {
            // Drain stdout while writing stdin: neither a verbose helper nor a
            // helper refusing stdin may block the thread enforcing the deadline.
            Thread.ofVirtual().name("nrf-query-output").start(output)
            Thread.ofVirtual().name("nrf-query-input").start(writer)
            val started = System.nanoTime()
            while (true) {
                if (output.isDone) output.get() // Report size/read failures promptly.
                if (writer.isDone) writer.get()
                val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                check(remaining > 0) { "Windows query exceeded $timeoutMs ms" }
                if (process.waitFor(minOf(100, remaining), TimeUnit.MILLISECONDS)) break
            }
            writer.get(2, TimeUnit.SECONDS)
            return Result(process.exitValue(), output.get(2, TimeUnit.SECONDS))
        } finally {
            // These fixed inventory scripts never launch other executables.
            // Only their own process is stopped; game processes are untouched.
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
            writer.cancel(true)
        }
    }
}
