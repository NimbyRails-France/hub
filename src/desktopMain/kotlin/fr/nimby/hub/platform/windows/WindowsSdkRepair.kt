package fr.nimby.hub.platform.windows

import fr.nimby.hub.i18n.tr

import fr.nimby.hub.model.hubJson
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.*

/** Repair only the documented Windows proxy layout, never an arbitrary SDL.
 * No code from the damaged installation is executed. A verified copy of every
 * affected file is retained outside the game before any mutation. The journal
 * makes a partial repair resumable after process termination or a locked DLL.
 */
class WindowsSdkRepair(private val data: Path, private val requireClosed: (Path) -> Unit,
                       private val log: (String) -> Unit = {},
                       private val originalHash: String = ORIGINAL_SDL) {
    companion object {
        const val ORIGINAL_SDL = "2a2704678bf6c9c6a944270ab35079df76f5afe92b780394ed72d9c8218b98d8"
        const val MANIFEST = "NimbyRailsFranceSDK-install.json"
        const val JOURNAL = "sdk-repair.json"
        private const val SDL = "SDL3.dll"
        private const val ORIGINAL = "NimbyRailsSDL3Original.dll"
        private val optional = mapOf(
            "pthreadSha256" to listOf("libwinpthread-1.dll"),
            "textureBridgeSha256" to (1..4).map { "NimbyRailsFranceTextureBridge-experimental-v$it.dll" },
            "signalUiBridgeSha256" to listOf("NimbySignalUiBridge-experimental-v1.dll"),
            "automaticDrivingBridgeSha256" to listOf("NimbyAutomaticDrivingBridge-v1.dll"),
            "constructionBridgeSha256" to listOf("NimbyConstructionBridge-experimental-v1.dll"))
        private val names = setOf(SDL, ORIGINAL, MANIFEST, "NimbyRailsFranceSDK.dll") + optional.values.flatten()

        private fun plain(path: Path) {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(a.isRegularFile && !a.isSymbolicLink && !a.isOther) { tr("Fichier non ordinaire : {0}", path) }
        }
        private fun hash(path: Path): String { plain(path); return path.sha256() }
        private fun present(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        private fun ordinaryParents(path: Path) {
            var parent: Path? = path.toAbsolutePath().normalize()
            while (parent != null) {
                if (present(parent)) {
                    val a = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    require(a.isDirectory && !a.isSymbolicLink && !a.isOther) { tr("Dossier de réparation traversant un lien : {0}", parent) }
                }
                parent = parent.parent
            }
        }

        /** Read-only preflight, also used to make removal of an already repaired
         * SDK idempotent. Unknown files never count as a clean installation. */
        fun requireClean(game: Path) {
            val actual = if (present(game.resolve(SDL))) hash(game.resolve(SDL)) else tr("absent")
            val residues = (names - SDL + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
                .filter { present(game.resolve(it)) }
            require(actual == ORIGINAL_SDL && residues.isEmpty()) {
                tr("Installation SDL à vérifier : {0}\nSHA-256 attendu : {1}\n", game.resolve(SDL), ORIGINAL_SDL) +
                    tr("SHA-256 présent : {0}\nFichiers de chargeur présents : {1}\n", actual, residues.joinToString().ifEmpty { tr("aucun") }) +
                    tr("Dans Paramètres, utilisez Réparer le chargeur SDK. Seule une installation avec manifeste et sauvegarde vérifiables peut être réparée. Une DLL inconnue ne sera pas remplacée.")
            }
        }
    }

    @Serializable data class Journal(val game: String, val backup: String, val hashes: Map<String, String>, val executableHash: String)

    private fun verified(game: Path): Journal {
        val manifest = game.resolve(MANIFEST)
        require(present(manifest)) { tr("Manifeste SDK absent : {0}. Réparation automatique impossible. Conservez les DLL ; transmettez le journal pour identifier le chargeur ou la version du jeu.", manifest) }
        plain(manifest)
        val m = hubJson.parseToJsonElement(manifest.jsonText()).jsonObject
        fun value(key: String) = m[key]?.jsonPrimitive?.contentOrNull
        fun digest(key: String): String = requireNotNull(value(key)) { tr("Empreinte absente : {0}", key) }.lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { tr("Empreinte invalide : {0}", key) }
        }
        require(value("format") in setOf("1", "2")) { tr("Format de manifeste SDK non pris en charge") }
        require(digest("originalSdlSha256") == originalHash) { tr("La sauvegarde SDL déclarée n'est pas la version prise en charge") }
        val expected = linkedMapOf(SDL to digest("proxySha256"), ORIGINAL to originalHash, "NimbyRailsFranceSDK.dll" to digest("sdkSha256"))
        optional.forEach { (key, allowed) ->
            if (!value(key).isNullOrBlank()) {
                val sum = digest(key)
                val declared = value(key.removeSuffix("Sha256") + "File")
                val filename = if (declared != null) {
                    require(declared in allowed) { tr("Nom de DLL interdit dans le manifeste : {0}", declared) }; declared
                } else allowed.filter { present(game.resolve(it)) && hash(game.resolve(it)) == sum }.singleOrNull()
                    ?: error(tr("DLL du manifeste absente ou ambiguë : {0}", key))
                expected[filename] = sum
            }
        }
        // A second loader or an unrecorded bridge must be investigated, not erased.
        val extras = (names - expected.keys - MANIFEST + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { tr("Fichiers de chargeur non enregistrés : {0}", extras.joinToString()) }
        expected.forEach { (name, sum) ->
            val actual = if (present(game.resolve(name))) hash(game.resolve(name)) else tr("absent")
            require(actual == sum) { tr("Fichier modifié : {0}\nSHA-256 attendu : {1}\nSHA-256 présent : {2}\nAucun fichier ne sera remplacé.", game.resolve(name), sum, actual) }
        }
        val executable = digest("executableSha256")
        require(hash(game.resolve("NimbyRails.exe")) == executable) { tr("Le jeu a changé depuis l'installation du chargeur") }
        expected[MANIFEST] = hash(manifest)
        return Journal(game.toString(), data.resolve("repairs/sdk-${UUID.randomUUID()}").toAbsolutePath().normalize().toString(), expected, executable)
    }

    fun repair(directory: Path): Path {
        val game = directory.toAbsolutePath().normalize()
        ordinaryParents(game)
        ordinaryParents(data.resolve("repairs"))
        require(!data.toAbsolutePath().normalize().startsWith(game)) { tr("Les sauvegardes de réparation doivent être hors du jeu") }
        requireClosed(game)
        log(tr("Diagnostic SDK Windows · jeu={0} · SHA-256 SDL d'origine attendu={1}", game, originalHash))
        (names + "NimbyRails.exe").sorted().forEach { name ->
            val path = game.resolve(name)
            log("$name : " + if (present(path)) runCatching { hash(path) }.getOrElse { tr("illisible : {0}", it.message) } else tr("absent"))
        }
        val journalFile = data.resolve(JOURNAL)
        if (present(journalFile)) plain(journalFile)
        val plan = if (journalFile.exists()) hubJson.decodeFromString<Journal>(journalFile.jsonText()).also {
            require(Path(it.game) == game) { tr("Une réparation est en attente pour {0}", it.game) }
        } else verified(game).also { snapshot ->
            val backup = Path(snapshot.backup)
            Files.createDirectories(backup.parent)
            Files.createDirectory(backup)
            snapshot.hashes.forEach { (name, sum) ->
                Files.copy(game.resolve(name), backup.resolve(name))
                require(hash(backup.resolve(name)) == sum) { tr("Copie de sauvegarde instable : {0}", name) }
            }
            backup.resolve("recovery.json").atomicWrite(hubJson.encodeToString(snapshot))
            journalFile.atomicWrite(hubJson.encodeToString(snapshot))
            log(tr("Sauvegarde de réparation SDK : {0}", backup))
        }
        val backup = Path(plan.backup).toAbsolutePath().normalize()
        ordinaryParents(backup)
        require(backup.startsWith(data.resolve("repairs").toAbsolutePath().normalize()) && backup != game && !backup.startsWith(game)) { tr("Dossier de sauvegarde invalide") }
        require(plan.hashes.keys.all { it in names } && plan.hashes.keys.containsAll(setOf(SDL, ORIGINAL, MANIFEST, "NimbyRailsFranceSDK.dll"))) { tr("Journal de réparation invalide") }
        require(plan.hashes[ORIGINAL] == originalHash) { tr("Sauvegarde SDL non reconnue") }
        plan.hashes.forEach { (name, sum) -> require(hash(backup.resolve(name)) == sum) { tr("Sauvegarde modifiée : {0}", name) } }
        requireClosed(game)
        require(hash(game.resolve("NimbyRails.exe")) == plan.executableHash) { tr("Le jeu a changé pendant la réparation") }
        val extras = (names - plan.hashes.keys + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { tr("Chargeur modifié pendant la réparation : {0}", extras.joinToString()) }
        fun checkCurrent(name: String) {
            val path = game.resolve(name)
            if (present(path)) {
                val current = hash(path)
                require(current == plan.hashes[name] || (name == SDL && current == originalHash)) { tr("Fichier modifié pendant la réparation : {0}. Sauvegarde conservée : {1}", path, backup) }
            } else require(name != SDL) { tr("SDL3.dll a disparu pendant la réparation. Sauvegarde conservée : {0}", backup) }
        }
        plan.hashes.keys.forEach(::checkCurrent)
        // Replace SDL atomically first. Never leave the game without SDL, even
        // if the process ends between this replacement and the following deletes.
        val temporary = Files.createTempFile(game, ".nrf-sdl-repair-", ".tmp")
        try {
            Files.copy(backup.resolve(ORIGINAL), temporary, StandardCopyOption.REPLACE_EXISTING)
            require(hash(temporary) == originalHash)
            checkCurrent(SDL)
            Files.move(temporary, game.resolve(SDL), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.deleteIfExists() }
        (plan.hashes.keys - SDL).forEach { name -> checkCurrent(name); Files.deleteIfExists(game.resolve(name)) }
        require(hash(game.resolve(SDL)) == originalHash)
        journalFile.deleteExisting()
        log(tr("SDL d'origine restaurée. Réappliquez le profil ou réinstallez le SDK. Sauvegarde : {0}", backup))
        return backup
    }
}
