package fr.nimby.hub.platform

import java.nio.file.*
import kotlin.io.path.*
import fr.nimby.hub.install.LinuxSdkInstallation

class UnixPlatform : DesktopPlatform {
    override val supported get() = Host.linux
    private fun canonical(path: Path) = runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
    private fun running(): List<Path> = ProcessHandle.allProcesses().use { processes ->
        processes.map { it.info().command().orElse(null)?.let { command -> canonical(Path(command)) } }.filter { it != null }.map { it!! }.toList()
    }
    override fun requireClosed(game: Path, destination: Path) {
        val executable = canonical(Host.game(game))
        val installation = canonical(destination)
        require(running().none { it == executable || it.startsWith(installation) } && !mappedInstallation(installation)) {
            "Fermez le jeu et les outils utilisant cette installation."
        }
    }
    private fun mappedInstallation(installation: Path): Boolean = ProcessHandle.allProcesses().use { processes ->
        val separator = Regex("\\s+")
        val paths = mutableMapOf<String, Boolean>()
        processes.anyMatch { process ->
            val maps = Path("/proc/${process.pid()}/maps")
            try {
                Files.newBufferedReader(maps).useLines { lines -> lines.any { line ->
                    val name = line.split(separator, limit = 6).getOrNull(5)
                    if (name == null || !name.startsWith('/')) false
                    else paths.getOrPut(name) {
                        canonical(Path(name.removeSuffix(" (deleted)").replace("\\012", "\n"))).startsWith(installation)
                    }
                } }
            } catch (_: NoSuchFileException) { false } // Process exited during enumeration.
              catch (_: AccessDeniedException) { false } // Other users' processes.
        }
    }
    override fun gameRunning(game: Path) = running().any { it == canonical(Host.game(game)) }
    override fun requestGameClose(game: Path) {
        // SIGTERM can bypass the game's save dialog. Until a window-manager
        // close request is available, let the player finish the running game.
        require(!gameRunning(game)) { "Enregistrez puis fermez NIMBY Rails dans sa fenêtre avant de relancer le profil Linux." }
    }
    override fun launchGame(game: Path) {
        require(Host.game(game).isRegularFile()) { "Exécutable Linux introuvable" }
        val launch = LinuxSdkInstallation.installedLauncher(game)
        val home = Path(System.getProperty("user.home"))
        val wsl = !System.getenv("WSL_DISTRO_NAME").isNullOrBlank()
        val fallback = home.resolve(".local/bin/nimby-wsl-rtx")
        if (launch == null) {
            if (wsl && Files.isExecutable(fallback) && canonical(game) == canonical(Host.defaultGame())) {
                ProcessBuilder(fallback.toString()).apply { environment()["NRF_LINUX_OBSERVATION"] = "0" }.start()
            } else ProcessBuilder("steam", "steam://rungameid/1134710").start()
            return
        }
        if (running().none { it.fileName.toString() == "steam" }) {
            ProcessBuilder("steam", "-silent").start()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (running().none { it.fileName.toString() == "steam" } && System.nanoTime() < deadline) Thread.sleep(250)
            require(running().any { it.fileName.toString() == "steam" }) { "Ouvrez Steam puis relancez NIMBY Rails" }
        }
        val graphics = home.resolve(".local/bin/wsl-vulkan-rtx")
        val command = (if (wsl && Files.isExecutable(graphics)) listOf(graphics.toString()) else emptyList()) + listOf("sh", launch.toString())
        ProcessBuilder(command).directory(game.toFile()).apply {
            if (wsl) {
                environment()["SDL_VIDEO_DRIVER"] = "x11"
                val guard = home.resolve(".local/lib/nrf-wsl/directory-read-guard.so")
                if (guard.isRegularFile() && environment()["NRF_DIRECTORY_READ_GUARD"] != "0") {
                    environment()["LD_PRELOAD"] = guard.toString() + environment()["LD_PRELOAD"]?.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
                }
            }
        }.start()
    }
    override fun linkTarget(path: Path): String? {
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return null
        require(path.isSymbolicLink()) { "Le chemin existant n'est pas un lien : $path" }
        return path.parent.resolve(Files.readSymbolicLink(path)).toAbsolutePath().normalize().toString()
    }
    override fun createLink(path: Path, target: Path) {
        val expected = target.toAbsolutePath().normalize()
        val current = linkTarget(path)
        if (current != null) { require(Path(current) == expected) { "Lien appartenant à une autre installation" }; return }
        path.parent.createDirectories(); Files.createSymbolicLink(path, expected)
    }
    override fun removeLink(path: Path, target: Path) {
        val current = linkTarget(path) ?: return
        require(Path(current) == target.toAbsolutePath().normalize()) { "Lien appartenant à une autre installation" }
        Files.delete(path)
    }
    override fun checkTree(path: Path) {
        Files.walk(path).use { files -> files.forEach { file ->
            val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attributes.isSymbolicLink && !attributes.isOther) { "Lien ou fichier spécial dans une installation gérée : $file" }
        } }
    }
    override fun shortcut(id: String, destination: Path, remove: Boolean) {
        require(Regex("[a-z][a-z0-9-]*").matches(id))
        val root = Path(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "applications")
        val file = root.resolve("nrf-$id.desktop")
        val executable = destination.resolve(Host.tcoName).toAbsolutePath().toString()
        require(executable.none { it == '\n' || it == '\r' })
        val marker = "X-NRF-Target=$executable"
        if (file.exists()) require(file.readLines().contains(marker)) { "Raccourci appartenant à une autre installation" }
        if (remove) { file.deleteIfExists(); return }
        val quoted = executable.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`").replace("%", "%%")
        root.createDirectories()
        file.writeText("[Desktop Entry]\nType=Application\nName=Nimby TCO\nExec=\"$quoted\"\nTerminal=false\nCategories=Game;\n$marker\n")
    }
    override fun proxy(directory: Path, game: Path, action: String) {
        require(action in setOf("Install", "Remove"))
        require(supported && Host.architecture == "x64") { "Le SDK natif exige Linux x64" }
        requireClosed(game, directory)
        if (action == "Install") LinuxSdkInstallation.install(directory, game) else LinuxSdkInstallation.remove(directory, game)
    }
    override fun ownsSdk(directory: Path, game: Path) = LinuxSdkInstallation.owns(directory, game)
}
