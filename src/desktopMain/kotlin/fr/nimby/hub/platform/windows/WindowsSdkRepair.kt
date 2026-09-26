package fr.nimby.hub.platform.windows

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
            "automaticDrivingBridgeSha256" to listOf("NimbyAutomaticDrivingBridge-v1.dll"))
        private val names = setOf(SDL, ORIGINAL, MANIFEST, "NimbyRailsFranceSDK.dll") + optional.values.flatten()

        private fun plain(path: Path) {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(a.isRegularFile && !a.isSymbolicLink && !a.isOther) { "Fichier non ordinaire : $path" }
        }
        private fun hash(path: Path): String { plain(path); return path.sha256() }
        private fun present(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        private fun ordinaryParents(path: Path) {
            var parent: Path? = path.toAbsolutePath().normalize()
            while (parent != null) {
                if (present(parent)) {
                    val a = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    require(a.isDirectory && !a.isSymbolicLink && !a.isOther) { "Dossier de réparation traversant un lien : $parent" }
                }
                parent = parent.parent
            }
        }

        /** Read-only preflight, also used to make removal of an already repaired
         * SDK idempotent. Unknown files never count as a clean installation. */
        fun requireClean(game: Path) {
            val actual = if (present(game.resolve(SDL))) hash(game.resolve(SDL)) else "absent"
            val residues = (names - SDL + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
                .filter { present(game.resolve(it)) }
            require(actual == ORIGINAL_SDL && residues.isEmpty()) {
                "Installation SDL à vérifier : ${game.resolve(SDL)}\nSHA-256 attendu : $ORIGINAL_SDL\n" +
                    "SHA-256 présent : $actual\nFichiers de chargeur présents : ${residues.joinToString().ifEmpty { "aucun" }}\n" +
                    "Dans Paramètres, utilisez Réparer le chargeur SDK. Seule une installation avec manifeste et sauvegarde vérifiables peut être réparée. Une DLL inconnue ne sera pas remplacée."
            }
        }
    }

    @Serializable data class Journal(val game: String, val backup: String, val hashes: Map<String, String>, val executableHash: String)

    private fun verified(game: Path): Journal {
        val manifest = game.resolve(MANIFEST)
        require(present(manifest)) { "Manifeste SDK absent : $manifest. Réparation automatique impossible. Conservez les DLL ; transmettez le journal pour identifier le chargeur ou la version du jeu." }
        plain(manifest)
        val m = hubJson.parseToJsonElement(manifest.jsonText()).jsonObject
        fun value(key: String) = m[key]?.jsonPrimitive?.contentOrNull
        fun digest(key: String): String = requireNotNull(value(key)) { "Empreinte absente : $key" }.lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Empreinte invalide : $key" }
        }
        require(value("format") in setOf("1", "2")) { "Format de manifeste SDK non pris en charge" }
        require(digest("originalSdlSha256") == originalHash) { "La sauvegarde SDL déclarée n'est pas la version prise en charge" }
        val expected = linkedMapOf(SDL to digest("proxySha256"), ORIGINAL to originalHash, "NimbyRailsFranceSDK.dll" to digest("sdkSha256"))
        optional.forEach { (key, allowed) ->
            if (!value(key).isNullOrBlank()) {
                val sum = digest(key)
                val declared = value(key.removeSuffix("Sha256") + "File")
                val filename = if (declared != null) {
                    require(declared in allowed) { "Nom de DLL interdit dans le manifeste : $declared" }; declared
                } else allowed.filter { present(game.resolve(it)) && hash(game.resolve(it)) == sum }.singleOrNull()
                    ?: error("DLL du manifeste absente ou ambiguë : $key")
                expected[filename] = sum
            }
        }
        // A second loader or an unrecorded bridge must be investigated, not erased.
        val extras = (names - expected.keys - MANIFEST + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { "Fichiers de chargeur non enregistrés : ${extras.joinToString()}" }
        expected.forEach { (name, sum) ->
            val actual = if (present(game.resolve(name))) hash(game.resolve(name)) else "absent"
            require(actual == sum) { "Fichier modifié : ${game.resolve(name)}\nSHA-256 attendu : $sum\nSHA-256 présent : $actual\nAucun fichier ne sera remplacé." }
        }
        val executable = digest("executableSha256")
        require(hash(game.resolve("NimbyRails.exe")) == executable) { "Le jeu a changé depuis l'installation du chargeur" }
        expected[MANIFEST] = hash(manifest)
        return Journal(game.toString(), data.resolve("repairs/sdk-${UUID.randomUUID()}").toAbsolutePath().normalize().toString(), expected, executable)
    }

    fun repair(directory: Path): Path {
        val game = directory.toAbsolutePath().normalize()
        ordinaryParents(game)
        ordinaryParents(data.resolve("repairs"))
        require(!data.toAbsolutePath().normalize().startsWith(game)) { "Les sauvegardes de réparation doivent être hors du jeu" }
        requireClosed(game)
        log("Diagnostic SDK Windows · jeu=$game · SHA-256 SDL d'origine attendu=$originalHash")
        (names + "NimbyRails.exe").sorted().forEach { name ->
            val path = game.resolve(name)
            log("$name : " + if (present(path)) runCatching { hash(path) }.getOrElse { "illisible : ${it.message}" } else "absent")
        }
        val journalFile = data.resolve(JOURNAL)
        if (present(journalFile)) plain(journalFile)
        val plan = if (journalFile.exists()) hubJson.decodeFromString<Journal>(journalFile.jsonText()).also {
            require(Path(it.game) == game) { "Une réparation est en attente pour ${it.game}" }
        } else verified(game).also { snapshot ->
            val backup = Path(snapshot.backup)
            Files.createDirectories(backup.parent)
            Files.createDirectory(backup)
            snapshot.hashes.forEach { (name, sum) ->
                Files.copy(game.resolve(name), backup.resolve(name))
                require(hash(backup.resolve(name)) == sum) { "Copie de sauvegarde instable : $name" }
            }
            backup.resolve("recovery.json").atomicWrite(hubJson.encodeToString(snapshot))
            journalFile.atomicWrite(hubJson.encodeToString(snapshot))
            log("Sauvegarde de réparation SDK : $backup")
        }
        val backup = Path(plan.backup).toAbsolutePath().normalize()
        ordinaryParents(backup)
        require(backup.startsWith(data.resolve("repairs").toAbsolutePath().normalize()) && backup != game && !backup.startsWith(game)) { "Dossier de sauvegarde invalide" }
        require(plan.hashes.keys.all { it in names } && plan.hashes.keys.containsAll(setOf(SDL, ORIGINAL, MANIFEST, "NimbyRailsFranceSDK.dll"))) { "Journal de réparation invalide" }
        require(plan.hashes[ORIGINAL] == originalHash) { "Sauvegarde SDL non reconnue" }
        plan.hashes.forEach { (name, sum) -> require(hash(backup.resolve(name)) == sum) { "Sauvegarde modifiée : $name" } }
        requireClosed(game)
        require(hash(game.resolve("NimbyRails.exe")) == plan.executableHash) { "Le jeu a changé pendant la réparation" }
        val extras = (names - plan.hashes.keys + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { "Chargeur modifié pendant la réparation : ${extras.joinToString()}" }
        fun checkCurrent(name: String) {
            val path = game.resolve(name)
            if (present(path)) {
                val current = hash(path)
                require(current == plan.hashes[name] || (name == SDL && current == originalHash)) { "Fichier modifié pendant la réparation : $path. Sauvegarde conservée : $backup" }
            } else require(name != SDL) { "SDL3.dll a disparu pendant la réparation. Sauvegarde conservée : $backup" }
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
        log("SDL d'origine restaurée. Réappliquez le profil ou réinstallez le SDK. Sauvegarde : $backup")
        return backup
    }
}
