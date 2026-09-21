package fr.nimby.hub.update

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.network.ReleaseSource
import fr.nimby.hub.storage.sha256
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*

class SelfUpdater(private val directory: Path, private val source: ReleaseSource) {
    private var ready: Pair<HubRelease, Path>? = null
    val version get() = ready?.first?.version
    private val installedExecutable: Path? get() = ProcessHandle.current().info().command().orElse(null)?.let(::Path)
        ?.takeIf { it.fileName.toString().equals(if (Host.windows) "NRFHub.exe" else "NRFHub", true) }

    fun discard() { ready?.second?.deleteIfExists(); ready = null }
    suspend fun check(channel: String, allowed: () -> Boolean) {
        if (!allowed() || installedExecutable == null || ready != null) return
        val release = source.hub(channel)
        if (!allowed() || Versions.compare(release.version, HUB_VERSION) <= 0) return
        require(release.platform == Host.id) { "Installateur incompatible avec ${Host.id}" }
        val extension = if (Host.windows) "exe" else if (Host.mac) "dmg" else "deb"
        require(release.url.substringBefore('?').endsWith(".$extension", ignoreCase = true)) { "Format d'installateur incompatible" }
        val path = directory.resolve("updates/setup-${UUID.randomUUID()}.$extension")
        source.download(release.url, release.size, release.sha256, path)
        if (allowed()) ready = release to path else path.deleteIfExists()
    }

    fun installOnExit(allowed: Boolean, relaunch: Boolean) {
        if (!allowed) return
        val executable = installedExecutable ?: return
        val (release, path) = ready ?: return
        require(path.fileSize() == release.size && path.sha256().equals(release.sha256, true)) { "Installateur Hub modifié" }
        if (!Host.windows) {
            // Native package managers own elevation and replacement of installed files.
            // Opening the verified installer lets the desktop ask for any required credentials.
            ProcessBuilder(if (Host.mac) "open" else "xdg-open", path.toString()).start()
            return
        }
        if (release.installer == "jpackage-exe") {
            ProcessBuilder(path.toString()).start()
            return
        }
        val command = mutableListOf(path.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/DIR=${executable.parent}")
        if (relaunch) command += "/RELAUNCH"
        ProcessBuilder(command).start()
    }
}
