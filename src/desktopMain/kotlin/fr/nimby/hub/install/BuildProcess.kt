package fr.nimby.hub.install

import kotlinx.coroutines.*
import java.io.Reader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one build and its children; cancellation never waits for another log line. */
internal object BuildProcess {
    suspend fun run(command: List<String>, root: Path, output: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // Keep creation inside the cleanup scope: cancellation while start() returns
        // must not discard a live Process at a withContext return boundary.
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
        val stopped = AtomicBoolean()
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            // Capture handles BEFORE stopping the parent, which would otherwise
            // orphan its descendants. Never select unrelated Java/Gradle processes.
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroy() }
            process.destroy()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while ((process.isAlive || children.any { it.isAlive }) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            children.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
        }
        try {
            process.outputStream.close() // Builds are non-interactive; do not leave stdin waiting forever.
            coroutineScope {
                // Java pipe reads need not react to Thread.interrupt(). An independent
                // coroutine stops the owned process on cancellation, releasing that pipe.
                val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { stop() } }
                }
                try {
                    val context = currentCoroutineContext()
                    process.inputStream.bufferedReader().use { reader ->
                        boundedLines(reader, { context.ensureActive() }, output)
                    }
                    runInterruptible { process.waitFor() }
                } finally {
                    withContext(NonCancellable) { cancellation.cancelAndJoin() }
                }
            }
        } finally {
            withContext(NonCancellable) { stop() }
        }
    }

    private fun boundedLines(reader: Reader, checkActive: () -> Unit, output: (String) -> Unit) {
        val buffer = CharArray(4096)
        val line = StringBuilder(2000)
        var count = 0
        var pending = false
        var afterCr = false
        fun emit() {
            // A UTF-16 limit may land between the two chars of an emoji.
            if (line.isNotEmpty() && line.last().isHighSurrogate()) line.setLength(line.length - 1)
            if (count < 100_000) { count++; output(line.toString()) }
            line.setLength(0)
            pending = false
        }
        while (true) {
            checkActive()
            val size = reader.read(buffer)
            if (size < 0) break
            for (index in 0 until size) {
                val char = buffer[index]
                if (char == '\n' && afterCr) { afterCr = false; continue }
                afterCr = char == '\r'
                if (char == '\r' || char == '\n') emit()
                else {
                    pending = true
                    // Discard the rest of a huge line while draining stdout: truncating
                    // AFTER readLine() would already have allocated unbounded memory.
                    if (line.length < 2000 && count < 100_000) line.append(char)
                }
            }
        }
        if (pending) emit()
    }
}
