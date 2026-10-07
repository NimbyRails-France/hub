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
        const val PENDING = "NimbyRailsFranceSDK-install.tmp"
        const val JOURNAL = "sdk-repair.json"
        private const val SDL = "SDL3.dll"
        private const val ORIGINAL = "NimbyRailsSDL3Original.dll"
        private val optional = mapOf(
            "pthreadSha256" to listOf("libwinpthread-1.dll"),
            "textureBridgeSha256" to (1..4).map { "NimbyRailsFranceTextureBridge-experimental-v$it.dll" },
            "signalUiBridgeSha256" to listOf("NimbySignalUiBridge-experimental-v1.dll"),
            "automaticDrivingBridgeSha256" to listOf("NimbyAutomaticDrivingBridge-v1.dll"),
            "constructionBridgeSha256" to listOf("NimbyConstructionBridge-experimental-v1.dll"))
        private val additional = setOf("NimbyRailsFranceClockBridge-0.7.1.dll", "NimbyModMetadataBridge-v1.dll", "NimbyRailsFranceModHost.exe")
        private val reusable = setOf("NimbyRailsFranceSDK.dll") + optional.values.flatten() + additional
        // Byte identity with the dependency distributed in SDK 0.6.6. Other
        // pre-existing versions are never assumed to belong to this project.
        internal val legacyDlls = mapOf("libwinpthread-1.dll" to setOf("1179c0c0ed77abb4aa92a14db97f369cdf364d810167d251b0dfe466db004c21"))
        private fun priorName(name: String) = "$name.nrf-before-sdk"
        private val stages = mapOf(SDL to "SDL3.NimbySDK.tmp", "NimbyRailsFranceSDK.dll" to "NimbyRailsFranceSDK.tmp",
            "libwinpthread-1.dll" to "libwinpthread-1.NimbySDK.tmp",
            "NimbyRailsFranceTextureBridge-experimental-v4.dll" to "NimbyRailsFranceTextureBridge.NimbySDK.tmp",
            "NimbySignalUiBridge-experimental-v1.dll" to "NimbySignalUiBridge.NimbySDK.tmp",
            "NimbyAutomaticDrivingBridge-v1.dll" to "NimbyAutomaticDrivingBridge.NimbySDK.tmp",
            "NimbyConstructionBridge-experimental-v1.dll" to "NimbyConstructionBridge.NimbySDK.tmp") + additional.associateWith { "$it.NimbySDK.tmp" }
        private val names = setOf(SDL, ORIGINAL, MANIFEST, PENDING) + reusable + stages.values + reusable.map(::priorName)

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
        fun requireClean(game: Path, loader: Path? = null, originalHash: String = ORIGINAL_SDL,
                         knownLegacy: Map<String, Set<String>> = legacyDlls) {
            val actual = if (present(game.resolve(SDL))) hash(game.resolve(SDL)) else tr("absent")
            val residues = (setOf(ORIGINAL, MANIFEST, PENDING, "NimbyRailsSDK-install.json", "NimbyRailsSDK.dll") + stages.values + reusable.map(::priorName))
                .filter { present(game.resolve(it)) }
            require(actual == originalHash && residues.isEmpty()) {
                tr("Installation SDL à vérifier : {0}\nSHA-256 attendu : {1}\n", game.resolve(SDL), originalHash) +
                    tr("SHA-256 présent : {0}\nFichiers de chargeur présents : {1}\n", actual, residues.joinToString().ifEmpty { tr("aucun") }) +
                    tr("Dans Paramètres, utilisez Réparer le chargeur SDK. Seule une installation avec manifeste et sauvegarde vérifiables peut être réparée. Une DLL inconnue ne sera pas remplacée.")
            }
            reusable.filter { present(game.resolve(it)) }.forEach { name ->
                val path = game.resolve(name)
                val sum = hash(path)
                val source = loader?.resolve(name)
                val wanted = if (source != null && present(source)) hash(source) else tr("absent")
                require(wanted == sum || (wanted != tr("absent") && sum in knownLegacy[name].orEmpty())) {
                    tr("DLL déjà présente : {0}\nSHA-256 présent : {1}\nSHA-256 du SDK : {2}\nElle ne correspond pas au fichier fourni par ce SDK. Elle a été conservée. Transmettez le journal pour identifier sa provenance.", path, sum, wanted)
                }
            }
        }
    }

    @Serializable data class Journal(val game: String, val backup: String, val hashes: Map<String, String>, val executableHash: String,
                                    val shared: Set<String> = emptySet(), val missing: Set<String> = emptySet(),
                                    val restore: Map<String, String> = emptyMap())

    private fun verified(game: Path): Journal {
        require(!(present(game.resolve(MANIFEST)) && present(game.resolve(PENDING)))) { tr("Plusieurs manifestes SDK présents ; aucun fichier ne sera modifié.") }
        val manifestName = if (present(game.resolve(MANIFEST))) MANIFEST else PENDING
        val manifest = game.resolve(manifestName)
        require(present(manifest)) { tr("Manifeste SDK absent : {0}. Réparation automatique impossible. Conservez les DLL ; transmettez le journal pour identifier le chargeur ou la version du jeu.", manifest) }
        plain(manifest)
        val m = hubJson.parseToJsonElement(manifest.jsonText()).jsonObject
        fun value(key: String) = m[key]?.jsonPrimitive?.contentOrNull
        fun digest(key: String): String = requireNotNull(value(key)) { tr("Empreinte absente : {0}", key) }.lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { tr("Empreinte invalide : {0}", key) }
        }
        require(value("format") in setOf("1", "2", "3")) { tr("Format de manifeste SDK non pris en charge") }
        require(digest("originalSdlSha256") == originalHash) { tr("La sauvegarde SDL déclarée n'est pas la version prise en charge") }
        val expected = linkedMapOf(SDL to digest("proxySha256"), ORIGINAL to originalHash, "NimbyRailsFranceSDK.dll" to digest("sdkSha256"))
        optional.forEach { (key, allowed) ->
            if (!value(key).isNullOrBlank()) {
                val sum = digest(key)
                val declared = value(key.removeSuffix("Sha256") + "File")
                val filename = if (declared != null) {
                    require(declared in allowed) { tr("Nom de DLL interdit dans le manifeste : {0}", declared) }; declared
                } else if (allowed.size == 1) allowed.single()
                else allowed.filter { present(game.resolve(it)) && hash(game.resolve(it)) == sum }.singleOrNull()
                    ?: error(tr("DLL du manifeste absente ou ambiguë : {0}", key))
                expected[filename] = sum
            }
        }
        m["additionalBridges"]?.jsonObject?.forEach { (name, digest) ->
            val sum = digest.jsonPrimitive.content.lowercase()
            require(name in additional && sum.matches(Regex("[0-9a-f]{64}"))) { tr("Journal de réparation invalide") }
            expected[name] = sum
        }
        val shared = m["sharedFiles"]?.jsonObject?.map { (name, digest) ->
            require(value("format") == "3" && name in reusable && expected[name] == digest.jsonPrimitive.content.lowercase()) { tr("Journal de réparation invalide") }
            name
        }?.toSet().orEmpty()
        val restore = m["replacedFiles"]?.jsonObject?.mapValues { (name, digest) ->
            val sum = digest.jsonPrimitive.content.lowercase()
            require(value("format") == "3" && name in reusable && name in expected && name !in shared && sum.matches(Regex("[0-9a-f]{64}"))) { tr("Journal de réparation invalide") }
            sum
        }.orEmpty()
        restore.forEach { (name, sum) -> expected[priorName(name)] = sum }
        stages.forEach { (name, stage) ->
            if (present(game.resolve(stage))) {
                val sum = if (name == SDL) digest("proxySha256") else expected[name]
                require(sum != null) { tr("Journal de réparation invalide") }
                expected[stage] = sum
            }
        }
        // A second loader or an unrecorded bridge must be investigated, not erased.
        // A shared pthread dependency is not owned by the SDK and must remain untouched.
        val extras = (names - expected.keys - manifestName - "libwinpthread-1.dll" + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { tr("Fichiers de chargeur non enregistrés : {0}", extras.joinToString()) }
        val missing = mutableSetOf<String>()
        // Steam may already have restored SDL; a missing SDK-owned DLL can also
        // be the result of quarantine or an interrupted installation/removal.
        val sdlRestored = present(game.resolve(SDL)) && hash(game.resolve(SDL)) == originalHash
        if (sdlRestored) expected[SDL] = originalHash
        restore.forEach { (name, sum) ->
            if (present(game.resolve(name)) && hash(game.resolve(name)) == sum) expected[name] = sum
        }
        expected.forEach { (name, sum) ->
            if (!present(game.resolve(name)) && name !in shared) {
                if (name == ORIGINAL) require(sdlRestored) { tr("La sauvegarde SDL déclarée n'est pas la version prise en charge") }
                else if (name in restore.keys.map(::priorName)) {
                    val current = game.resolve(name.removeSuffix(".nrf-before-sdk"))
                    require(present(current) && hash(current) == sum) { tr("Sauvegarde de DLL absente ou invalide : {0}", game.resolve(name)) }
                }
                else missing.add(name)
                return@forEach
            }
            val actual = if (present(game.resolve(name))) hash(game.resolve(name)) else tr("absent")
            require(actual == sum) { tr("Fichier modifié : {0}\nSHA-256 attendu : {1}\nSHA-256 présent : {2}\nAucun fichier ne sera remplacé.", game.resolve(name), sum, actual) }
        }
        val executable = digest("executableSha256")
        require(hash(game.resolve("NimbyRails.exe")) == executable) { tr("Le jeu a changé depuis l'installation du chargeur") }
        expected[manifestName] = hash(manifest)
        return Journal(game.toString(), data.resolve("repairs/sdk-${UUID.randomUUID()}").toAbsolutePath().normalize().toString(), expected, executable, shared, missing, restore)
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
            snapshot.hashes.filterKeys { it !in snapshot.missing }.forEach { (name, sum) ->
                val source = when {
                    name == ORIGINAL && !present(game.resolve(ORIGINAL)) -> game.resolve(SDL)
                    name.endsWith(".nrf-before-sdk") && !present(game.resolve(name)) -> game.resolve(name.removeSuffix(".nrf-before-sdk"))
                    else -> game.resolve(name)
                }
                Files.copy(source, backup.resolve(name))
                require(hash(backup.resolve(name)) == sum) { tr("Copie de sauvegarde instable : {0}", name) }
            }
            backup.resolve("recovery.json").atomicWrite(hubJson.encodeToString(snapshot))
            journalFile.atomicWrite(hubJson.encodeToString(snapshot))
            log(tr("Sauvegarde de réparation SDK : {0}", backup))
        }
        val backup = Path(plan.backup).toAbsolutePath().normalize()
        ordinaryParents(backup)
        require(backup.startsWith(data.resolve("repairs").toAbsolutePath().normalize()) && backup != game && !backup.startsWith(game)) { tr("Dossier de sauvegarde invalide") }
        require(plan.hashes.keys.all { it in names } && plan.hashes.keys.containsAll(setOf(SDL, ORIGINAL, "NimbyRailsFranceSDK.dll")) &&
            listOf(MANIFEST, PENDING).count { it in plan.hashes } == 1) { tr("Journal de réparation invalide") }
        require(plan.shared.all { it in reusable && it in plan.hashes }) { tr("Journal de réparation invalide") }
        require(plan.restore.all { (name, sum) -> name in reusable && name in plan.hashes && name !in plan.shared && plan.hashes[priorName(name)] == sum }) { tr("Journal de réparation invalide") }
        require(plan.missing.all { it in plan.hashes && it !in plan.shared && it !in setOf(ORIGINAL, MANIFEST, PENDING) && it !in plan.restore.keys.map(::priorName) }) { tr("Journal de réparation invalide") }
        require(plan.hashes[ORIGINAL] == originalHash) { tr("Sauvegarde SDL non reconnue") }
        plan.hashes.filterKeys { it !in plan.missing }.forEach { (name, sum) -> require(hash(backup.resolve(name)) == sum) { tr("Sauvegarde modifiée : {0}", name) } }
        requireClosed(game)
        require(hash(game.resolve("NimbyRails.exe")) == plan.executableHash) { tr("Le jeu a changé pendant la réparation") }
        val extras = (names - plan.hashes.keys - "libwinpthread-1.dll" + setOf("NimbyRailsSDK-install.json", "NimbyRailsSDK.dll"))
            .filter { present(game.resolve(it)) }
        require(extras.isEmpty()) { tr("Chargeur modifié pendant la réparation : {0}", extras.joinToString()) }
        fun checkCurrent(name: String) {
            val path = game.resolve(name)
            if (present(path)) {
                val current = hash(path)
                require(current == plan.hashes[name] || current == plan.restore[name] || (name == SDL && current == originalHash)) { tr("Fichier modifié pendant la réparation : {0}. Sauvegarde conservée : {1}", path, backup) }
            } else require(name !in plan.shared) { tr("Fichier partagé absent pendant la réparation : {0}. Sauvegarde conservée : {1}", path, backup) }
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
        plan.restore.forEach { (name, sum) ->
            val stage = Files.createTempFile(game, ".nrf-dll-restore-", ".tmp")
            try {
                Files.copy(backup.resolve(priorName(name)), stage, StandardCopyOption.REPLACE_EXISTING)
                require(hash(stage) == sum)
                checkCurrent(name)
                Files.move(stage, game.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { stage.deleteIfExists() }
        }
        (plan.hashes.keys - SDL - plan.shared - plan.restore.keys).forEach { name -> checkCurrent(name); Files.deleteIfExists(game.resolve(name)) }
        require(hash(game.resolve(SDL)) == originalHash)
        journalFile.deleteExisting()
        log(tr("SDL d'origine restaurée. Réappliquez le profil ou réinstallez le SDK. Sauvegarde : {0}", backup))
        return backup
    }
}
