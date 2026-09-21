package fr.nimby.hub.platform

import java.nio.file.Path
import kotlin.io.path.*
import fr.nimby.hub.storage.sha256

interface DesktopPlatform {
    val supported: Boolean
    fun requireClosed(game: Path, destination: Path)
    fun gameRunning(game: Path): Boolean
    fun requestGameClose(game: Path)
    fun launchGame(game: Path)
    fun linkTarget(path: Path): String?
    fun createLink(path: Path, target: Path)
    fun removeLink(path: Path, target: Path)
    fun checkTree(path: Path)
    fun shortcut(id: String, destination: Path, remove: Boolean = false)
    fun proxy(directory: Path, game: Path, action: String)
    fun ownsSdk(directory: Path, game: Path): Boolean = Host.sdkLibraryNames.any { name ->
        val active = game.resolve(name)
        val owned = directory.resolve("loader/$name")
        active.isRegularFile() && owned.isRegularFile() && active.sha256() == owned.sha256()
    }
}

object Host {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val linux = System.getProperty("os.name").startsWith("Linux")
    val mac = System.getProperty("os.name").startsWith("Mac")
    val architecture = when (System.getProperty("os.arch").lowercase()) { "amd64", "x86_64" -> "x64"; "aarch64", "arm64" -> "arm64"; else -> error("Architecture non prise en charge") }
    val id get() = "${if (windows) "windows" else if (mac) "macos" else "linux"}-$architecture"
    val gameName get() = if (windows) "NIMBYRails.exe" else "nimbyrails"
    val tcoName get() = if (windows) "NimbyTco.exe" else "bin/NimbyTco"
    val proxyInstaller get() = if (windows) "loader/install-proxy.ps1" else "loader/install-proxy.sh"
    val moduleExtension get() = if (windows) "dll" else if (mac) "dylib" else "so"
    val sdkLibraryNames get() = if (windows) listOf("NimbyRailsFranceSDK.dll", "NimbyRailsSDK.dll")
        else listOf("NimbyRailsFranceSDK.$moduleExtension")
    fun game(directory: Path) = directory.resolve(gameName)
    fun modsDirectory(): Path = if (windows) Path(System.getProperty("user.home"), "Saved Games/Weird and Wry/NIMBY Rails/mods")
        else Path(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "nimbyrails-saves/mods")
    fun defaultGame(): Path = if (windows) Path("C:/Program Files (x86)/Steam/steamapps/common/NIMBY Rails") else {
        val home = Path(System.getProperty("user.home"))
        listOf(".steam/steam", ".local/share/Steam", ".steam/debian-installation").map { home.resolve("$it/steamapps/common/NIMBY Rails") }
            .firstOrNull { game(it).isRegularFile() } ?: home.resolve(".local/share/Steam/steamapps/common/NIMBY Rails")
    }
}
fun desktopPlatform(): DesktopPlatform = if (Host.windows) Windows() else UnixPlatform()
