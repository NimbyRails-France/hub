package fr.nimby.hub.platform

import fr.nimby.hub.storage.atomicWrite
import java.net.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.*

/** File lock + authenticated loopback wake-up, with a compatibility check for the old Qt Hub. */
class SingleInstance(private val directory: Path) : AutoCloseable {
    private val channel = FileChannel.open(directory.resolve("kotlin-hub.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private val lock = channel.tryLock()
    private val endpoint = directory.resolve("kotlin-instance.txt")
    private var server: ServerSocket? = null
    val acquired get() = lock != null

    fun start(show: () -> Unit) {
        check(acquired)
        val qtLock = directory.resolve("hub.lock")
        if (qtLock.exists()) {
            val pid = qtLock.readLines().firstOrNull()?.toLongOrNull()
            require(pid == null || ProcessHandle.of(pid).map { it.isAlive }.orElse(false).not()) { "Quittez d'abord l'ancien Hub depuis sa zone de notification." }
        }
        val token = UUID.randomUUID().toString()
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        endpoint.atomicWrite("${socket.localPort}\n$token")
        thread(name = "hub-activation", isDaemon = true) {
            while (!socket.isClosed) runCatching {
                socket.accept().use { connection ->
                    connection.soTimeout = 1000
                    val text = connection.getInputStream().readNBytes(36).toString(Charsets.UTF_8)
                    if (text == token) show()
                }
            }
        }
    }
    fun activateExisting() {
        runCatching {
            val lines = endpoint.readLines()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), lines[0].toInt()), 1500)
                socket.getOutputStream().write(lines[1].toByteArray())
            }
        }
    }
    override fun close() {
        server?.close()
        if (acquired) endpoint.deleteIfExists()
        lock?.release(); channel.close()
    }
}
