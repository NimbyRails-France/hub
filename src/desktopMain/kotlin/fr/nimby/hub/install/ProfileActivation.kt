package fr.nimby.hub.install

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/** Switches only owned links and the SDK proxy; distribution files never move. */
class ProfileActivation(private val windows: DesktopPlatform = desktopPlatform()) {
    @Serializable data class Journal(val before: Map<String, InstalledProject>, val after: Map<String, InstalledProject>)

    fun links(records: Map<String, InstalledProject>): Map<Path, Path> = buildMap {
        records.values.forEach { record ->
            listOfNotNull(record.modLink, record.loaderLink).forEach { link ->
                require(put(Path(link).toAbsolutePath().normalize(), Path(record.directory).toAbsolutePath().normalize()) == null) { "Jonction dupliquée : $link" }
            }
        }
    }

    fun activate(game: Path, before: Map<String, InstalledProject>, after: Map<String, InstalledProject>, journal: Path, persist: () -> Unit) {
        require(!journal.exists()) { "Une activation interrompue doit être restaurée avant de continuer." }
        (before.values + after.values).forEach { windows.requireClosed(game, Path(it.directory)) }
        windows.requireClosed(game, game)
        ProfileRules.validate(after, Host.game(game).sha256())
        val oldSdk = before["sdk"]?.directory
        val newSdk = after["sdk"]?.directory
        val sdkRegistered = listOf("NimbyRailsFranceSDK-install.json", "NimbyRailsSDK-install.json").any { game.resolve(it).exists() }
        if (sdkRegistered) {
            require(oldSdk != null) { "Un SDK installé hors du Hub est actif. Retirez-le avec son installateur avant de basculer." }
            require(windows.ownsSdk(Path(oldSdk), game)) { "Le SDK actif ne correspond plus à l’installation gérée par le Hub" }
        }
        val oldLinks = links(before)
        val newLinks = links(after)
        (oldLinks.keys + newLinks.keys).forEach { link ->
            val target = windows.linkTarget(link)?.let { Path(it).toAbsolutePath().normalize() }
            require(target == null || target == oldLinks[link]) { "Jonction non gérée par le profil actif : $link" }
        }
        journal.atomicWrite(hubJson.encodeToString(Journal(before, after)))
        var removedSdk = false
        var attemptedSdk = false
        try {
            if (oldSdk != newSdk && oldSdk != null) { removedSdk = true; windows.proxy(Path(oldSdk), game, "Remove") }
            if (oldSdk != newSdk && newSdk != null) { attemptedSdk = true; windows.proxy(Path(newSdk), game, "Install") }
            oldLinks.forEach { (link, target) -> if (newLinks[link] != target) windows.removeLink(link, target) }
            newLinks.forEach { (link, target) -> windows.createLink(link, target) }
            persist()
        } catch (failure: Exception) {
            try {
                newLinks.forEach { (link, target) ->
                    if (oldLinks[link] != target && windows.linkTarget(link)?.let { Path(it).toAbsolutePath().normalize() } == target) windows.removeLink(link, target)
                }
                oldLinks.forEach { (link, target) -> windows.createLink(link, target) }
                if (attemptedSdk) runCatching { windows.proxy(Path(newSdk!!), game, "Remove") }.onFailure {
                    require(!game.resolve("NimbyRailsFranceSDK-install.json").exists() && !game.resolve("NimbyRailsSDK-install.json").exists()) { "SDK de test encore enregistré : ${it.message}" }
                }
                if (removedSdk) windows.proxy(Path(oldSdk!!), game, "Install")
                journal.deleteIfExists()
            } catch (recovery: Exception) {
                failure.addSuppressed(recovery)
                error("Activation échouée ; restauration incomplète. Journal conservé : $journal. ${failure.message} / ${recovery.message}")
            }
            throw failure
        }
        journal.deleteIfExists()
    }

    /** Recovery is explicit after a crash and accepts only targets recorded before that crash. */
    fun recover(game: Path, journal: Path, persist: (Map<String, InstalledProject>) -> Unit) {
        val plan = hubJson.decodeFromString<Journal>(journal.jsonText())
        windows.requireClosed(game, game)
        (plan.before.values + plan.after.values).forEach { record ->
            windows.requireClosed(game, Path(record.directory))
            val owned = hubJson.decodeFromString<InstalledProject>(Path(record.directory, ".nrf-project.json").jsonText())
            require(owned.id == record.id && owned.version == record.version) { "Installation de récupération modifiée" }
        }
        val old = links(plan.before)
        val next = links(plan.after)
        val current = (old.keys + next.keys).associateWith { link -> windows.linkTarget(link)?.let { Path(it).toAbsolutePath().normalize() } }
        current.forEach { (link, target) -> require(target == null || target == old[link] || target == next[link]) { "Jonction étrangère : $link" } }
        val sdkCandidates = listOfNotNull(plan.before["sdk"], plan.after["sdk"])
        val marker = listOf("NimbyRailsFranceSDK-install.json", "NimbyRailsSDK-install.json").any { game.resolve(it).exists() }
        if (marker) {
            val candidate = sdkCandidates.firstOrNull { record ->
                windows.ownsSdk(Path(record.directory), game)
            } ?: error("SDK actif non reconnu. Le journal de récupération est conservé.")
            windows.proxy(Path(candidate.directory), game, "Remove")
        }
        plan.before["sdk"]?.let { windows.proxy(Path(it.directory), game, "Install") }
        current.forEach { (link, target) -> if (target != null && old[link] != target) windows.removeLink(link, target) }
        old.forEach { (link, target) -> windows.createLink(link, target) }
        persist(plan.before)
        journal.deleteIfExists()
    }

    fun prepareDevelopment(records: Map<String, InstalledProject>, root: Path, game: Path,
                           previous: Map<String, InstalledProject>): Map<String, InstalledProject> {
        return records.mapValues { (id, source) ->
            val prior = previous[id]
            val sourcePath = Path(source.directory).toAbsolutePath().normalize()
            // Reuse a profile copy only while its distribution identity is unchanged.
            val copy = if (prior != null && prior.version == source.version && prior.installedUtc == source.installedUtc &&
                prior.origin == source.origin && Path(prior.directory).isDirectory()) prior else {
                val target = root.resolve("sessions/$id-${java.util.UUID.randomUUID()}").toAbsolutePath().normalize()
                require(!target.startsWith(sourcePath) && !sourcePath.startsWith(target)) {
                    "Le dossier de préparation doit être distinct des sources de distribution"
                }
                windows.checkTree(sourcePath)
                Files.walk(sourcePath).use { stream -> stream.forEach { file ->
                    val output = target.resolve(sourcePath.relativize(file).toString())
                    if (file.isDirectory()) output.createDirectories() else { output.parent.createDirectories(); Files.copy(file, output) }
                } }
                source.copy(directory = target.toString())
            }
            val modId = source.asProject().modId
            copy.copy(modId = modId,
                modLink = if (source.kind == "native-mod") source.modLink ?: Host.modsDirectory().resolve(requireNotNull(modId)).toString() else null,
                loaderLink = if (source.kind == "native-mod" && source.loaderApi == 1) game.resolve("NRFMods/$id").toString() else null
            ).also { record -> Path(record.directory, ".nrf-project.json").atomicWrite(hubJson.encodeToString(record)) }
        }
    }
}
