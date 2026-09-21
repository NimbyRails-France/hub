package fr.nimby.hub.install

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import kotlin.io.path.*

@Serializable
data class InstallRequest(
    val action: String,
    val project: Project,
    val destination: String,
    val gameDirectory: String,
    val resultFile: String,
    val archive: String = "",
    val expectedGameHash: String = "",
    val nativeModsDirectory: String? = null,
    val detached: Boolean = false,
    val origin: String = "unknown",
)

class ProjectManager(private val windows: DesktopPlatform = desktopPlatform(), private val log: (String) -> Unit = {}) {
    fun execute(request: InstallRequest): InstalledProject? {
        require(ProjectRules.identifier.matches(request.project.id)) { "Identifiant de projet invalide" }
        if (request.action == "install") require(request.project.platform == Host.id) { "Ce paquet cible ${request.project.platform}, ce système est ${Host.id}" }
        require(request.action in setOf("install", "remove", "rollback"))
        val destination = Path(request.destination).toAbsolutePath().normalize()
        require(Path(request.destination).isAbsolute && destination.parent != null && destination.toString().length >= 8)
        // Do not traverse an existing junction in any ancestor of a managed directory.
        var ancestor: Path? = destination.parent
        while (ancestor != null) {
            if (ancestor.exists(LinkOption.NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(ancestor, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && !attributes.isOther) { "Un dossier parent est un lien" }
            }
            ancestor = ancestor.parent
        }
        val previous = destination.resolveSibling("${destination.fileName}.nrf-previous")
        val game = Path(request.gameDirectory).toAbsolutePath().normalize()
        windows.requireClosed(game, destination)
        val old = if (destination.exists(LinkOption.NOFOLLOW_LINKS)) record(destination, request.project.id) else null
        require(!request.detached || (request.action == "install" && old == null)) { "Une préparation doit utiliser un nouveau dossier" }
        val result = when (request.action) {
            "remove" -> { remove(destination, previous, game, requireNotNull(old)); null }
            "rollback" -> rollback(destination, previous, game, requireNotNull(old))
            else -> install(request, destination, previous, game, old)
        }
        Path(request.resultFile).atomicWrite(result?.let(hubJson::encodeToString) ?: "{}")
        return result
    }

    private fun record(path: Path, id: String): InstalledProject {
        windows.checkTree(path)
        val record = hubJson.decodeFromString<InstalledProject>(path.resolve(".nrf-project.json").jsonText())
        require(record.id == id) { "Ce dossier appartient à un autre projet" }
        return record
    }

    private fun erase(path: Path, id: String) {
        record(path, id) // Validates ownership and rejects all reparse points before walking.
        deleteTree(path)
    }

    private fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun supportedGame(game: Path, hashes: List<String>, expected: String? = null) {
        val actual = Host.game(game).sha256()
        require(hashes.any { it.equals(actual, true) } && (expected == null || actual.equals(expected, true))) { "Version du jeu modifiée ou non prise en charge" }
    }

    private fun links(record: InstalledProject) = listOfNotNull(record.modLink, record.loaderLink).distinct().map(::Path)

    private fun validateLinks(record: InstalledProject, destination: Path) {
        links(record).forEach { link ->
            val target = windows.linkTarget(link)
            require(target == null || Path(target).toAbsolutePath().normalize() == destination) { "La jonction appartient à un autre projet" }
        }
    }

    private fun shortcut(record: InstalledProject, destination: Path, remove: Boolean = false) {
        if (record.kind == "tco") runCatching { windows.shortcut(record.id, destination, remove) }
            .onFailure { log("Raccourci : ${it.message}") }
    }

    private fun remove(destination: Path, previous: Path, game: Path, old: InstalledProject) {
        if (previous.exists()) record(previous, old.id)
        validateLinks(old, destination)
        if (old.kind == "sdk") windows.proxy(destination, game, "Remove")
        links(old).forEach { windows.removeLink(it, destination) }
        erase(destination, old.id)
        if (previous.exists()) erase(previous, old.id)
        shortcut(old, destination, remove = true)
    }

    private fun rollback(destination: Path, previous: Path, game: Path, old: InstalledProject): InstalledProject {
        val prior = record(previous, old.id)
        require(prior.kind == old.kind)
        supportedGame(game, prior.gameSha256)
        validateLinks(old, destination)
        validateLinks(prior, destination)
        val swap = destination.resolveSibling("${destination.fileName}.nrf-swap-${UUID.randomUUID()}")
        if (old.kind == "sdk") windows.proxy(destination, game, "Remove")
        Files.move(destination, swap)
        var promoted = false
        try {
            Files.move(previous, destination); promoted = true
            if (prior.kind == "sdk") windows.proxy(destination, game, "Install")
            links(prior).forEach { windows.createLink(it, destination) }
            (links(old) - links(prior).toSet()).forEach { windows.removeLink(it, destination) }
        } catch (failure: Exception) {
            if (promoted) Files.move(destination, previous)
            Files.move(swap, destination)
            links(old).forEach { windows.createLink(it, destination) }
            (links(prior) - links(old).toSet()).forEach { windows.removeLink(it, destination) }
            if (old.kind == "sdk") windows.proxy(destination, game, "Install")
            throw failure
        }
        Files.move(swap, previous)
        shortcut(prior, destination)
        return prior
    }

    private fun install(request: InstallRequest, destination: Path, previous: Path, game: Path, old: InstalledProject?): InstalledProject {
        val project = request.project
        ProjectRules.validate(project, remote = false)
        supportedGame(game, project.gameSha256, request.expectedGameHash)
        val archive = Path(request.archive)
        require(archive.fileSize() == project.size && archive.sha256().equals(project.sha256, true)) { "Archive : empreinte ou taille incorrecte" }
        require(old == null || old.kind == project.kind) { "Le type du projet a changé" }
        if (!request.detached && project.kind == "sdk" && old == null) require(!game.resolve("NimbyRailsSDK-install.json").exists() && !game.resolve("NimbyRailsFranceSDK-install.json").exists()) { "Retirez d'abord le SDK installé hors du Hub avec son installateur" }
        destination.parent.createDirectories()
        val stage = Files.createTempDirectory(destination.parent, ".nrf-stage-")
        try {
            Archives.extract(archive, stage, project.rootFolder)
            var next = InstalledProject(project.id, project.kind, project.version, destination.toString(), project.name,
                project.gameSha256, project.sdkMin, project.sdkMaxExclusive, project.loaderApi, project.module,
                installedUtc = Instant.now().toString(), modId = project.modId, origin = request.origin, platform = project.platform)
            when (project.kind) {
                "sdk" -> require(stage.resolve(if (Host.linux) "loader/${LinuxSdkInstallation.library}" else Host.proxyInstaller).isRegularFile()) { "Chargeur SDK absent" }
                "tco" -> require(stage.resolve(Host.tcoName).isRegularFile()) { "Exécutable TCO absent" }
                "native-mod" -> {
                    require(stage.resolve("mod.txt").isRegularFile()) { "mod.txt absent" }
                    val mods = request.nativeModsDirectory?.let(::Path) ?: Host.modsDirectory()
                    val modLink = mods.toAbsolutePath().normalize().resolve(project.modId!!).toString()
                    require(old == null || old.modLink == modLink) { "Identifiant de mod modifié" }
                    if (!request.detached) next = next.copy(modLink = modLink)
                    if (project.loaderApi == 1) {
                        require(stage.resolve(project.module!!).isRegularFile()) { "DLL du mod absente" }
                        val manifest = "[NRFMod]\nlibrary=${project.module}\n"
                        stage.resolve("nrf-mod.ini").writeBytes(if (Host.windows)
                            ("\uFEFF" + manifest.replace("\n", "\r\n")).toByteArray(Charsets.UTF_16LE)
                            else manifest.toByteArray(Charsets.UTF_8))
                        if (!request.detached) next = next.copy(loaderLink = game.resolve("NRFMods/${project.id}").toString())
                    }
                    require(old?.loaderLink == null || old.loaderLink == next.loaderLink) { "Enregistrement du chargeur modifié" }
                    validateLinks(next, destination)
                    if (old == null) require(links(next).all { windows.linkTarget(it) == null }) { "Mod déjà enregistré hors du Hub" }
                }
            }
            stage.resolve(".nrf-project.json").atomicWrite(hubJson.encodeToString(next))
            windows.requireClosed(game, destination)
            supportedGame(game, project.gameSha256, request.expectedGameHash)
            if (previous.exists()) erase(previous, project.id)
            var movedOld = false
            var promoted = false
            var proxyRemoved = false
            var proxyInstalled = false
            val createdLinks = mutableListOf<Path>()
            try {
                if (old?.kind == "sdk") { windows.proxy(destination, game, "Remove"); proxyRemoved = true }
                if (old != null) { Files.move(destination, previous); movedOld = true }
                Files.move(stage, destination); promoted = true
                if (project.kind == "sdk" && !request.detached) { windows.proxy(destination, game, "Install"); proxyInstalled = true }
                for (link in links(next)) if (windows.linkTarget(link) == null) {
                    windows.createLink(link, destination); createdLinks.add(link)
                }
            } catch (failure: Exception) {
                if (proxyInstalled) windows.proxy(destination, game, "Remove")
                createdLinks.forEach { windows.removeLink(it, destination) }
                if (promoted) erase(destination, project.id)
                if (movedOld) Files.move(previous, destination)
                if (proxyRemoved) windows.proxy(destination, game, "Install")
                throw failure
            }
            if (!request.detached) shortcut(next, destination)
            return next
        } finally { if (stage.exists()) deleteTree(stage) }
    }
}
