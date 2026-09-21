package fr.nimby.hub.install

import fr.nimby.hub.model.hubJson
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.*

/** The Hub owns these copies. It never replaces the game's executable or SDL. */
object LinuxSdkInstallation {
    const val library = "NimbyRailsFranceSDK.so"
    const val launcher = "nrf-launch.sh"
    private const val marker = "NimbyRailsFranceSDK-install.json"
    @Serializable private data class Record(val format: Int = 1, val platform: String = "linux-x64",
        val state: String, val files: Map<String, String>)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun source(directory: Path): Path = directory.resolve("loader/$library").also {
        require(it.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "Bibliothèque SDK Linux absente" }
    }
    private fun read(game: Path): Record {
        val path = game.resolve(marker)
        require(path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "Enregistrement SDK Linux absent ou remplacé par un lien" }
        return hubJson.decodeFromString<Record>(path.jsonText()).also { record ->
            require(record.format == 1 && record.platform == "linux-x64" && record.state in setOf("installing", "ready", "removing") &&
                record.files.keys == setOf(library, launcher) && record.files.values.all { Regex("[a-f0-9]{64}").matches(it) }) { "Enregistrement SDK Linux invalide" }
        }
    }
    private fun validate(game: Path, record: Record) {
        record.files.forEach { (name, digest) ->
            val file = game.resolve(name)
            if (file.exists(LinkOption.NOFOLLOW_LINKS)) {
                require(file.isRegularFile(LinkOption.NOFOLLOW_LINKS) && file.sha256() == digest) { "Fichier SDK modifié ou étranger : $name" }
            } else require(record.state != "ready") { "Fichier SDK manquant : $name" }
        }
    }
    fun owns(directory: Path, game: Path): Boolean = runCatching {
        val record = read(game)
        require(source(directory).sha256() == record.files[library])
        validate(game, record)
        true
    }.getOrDefault(false)

    fun install(directory: Path, game: Path) {
        val runtime = source(directory)
        runtime.inputStream().use { input ->
            val header = input.readNBytes(20)
            require(header.size == 20 && header.take(6) == listOf<Byte>(127, 69, 76, 70, 2, 1) &&
                header[18] == 62.toByte() && header[19] == 0.toByte()) { "SDK ELF Linux x64 requis" }
        }
        require(game.resolve("nimbyrails").isRegularFile()) { "Jeu Linux absent" }
        val script = """
            #!/bin/sh
            set -eu
            directory=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd -P)
            cd "${'$'}directory"
            export SteamAppId=1134710 SteamGameId=1134710
            export NRF_LINUX_OBSERVATION=1
            export LD_PRELOAD="./$library${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
            exec "${'$'}directory/nimbyrails" "${'$'}@"
        """.trimIndent().plus("\n").toByteArray()
        val planned = Record(state = "installing", files = mapOf(library to runtime.sha256(), launcher to hash(script)))
        val metadata = game.resolve(marker)
        if (metadata.exists(LinkOption.NOFOLLOW_LINKS)) {
            val current = read(game)
            require(current.files == planned.files && current.state != "removing") { "Un autre SDK est enregistré ; retirez-le avant l’installation" }
            validate(game, current)
            if (current.state == "ready") return
        } else {
            require(planned.files.keys.none { game.resolve(it).exists(LinkOption.NOFOLLOW_LINKS) }) { "Un fichier SDK non géré est déjà présent" }
            metadata.atomicWrite(hubJson.encodeToString(planned))
        }
        try {
            for (name in planned.files.keys) {
                val target = game.resolve(name)
                if (target.exists(LinkOption.NOFOLLOW_LINKS)) continue
                val temporary = Files.createTempFile(game, ".nrf-linux-", ".tmp")
                try {
                    if (name == library) Files.copy(runtime, temporary, StandardCopyOption.REPLACE_EXISTING) else temporary.writeBytes(script)
                    require(temporary.sha256() == planned.files[name]) { "Le SDK a changé pendant sa copie" }
                    if (name == launcher) Files.setPosixFilePermissions(temporary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                    Files.move(temporary, target)
                } finally { temporary.deleteIfExists() }
            }
            val complete = planned.copy(state = "ready")
            validate(game, complete)
            metadata.atomicWrite(hubJson.encodeToString(complete))
        } catch (failure: Exception) {
            // Keep a recoverable record if another process modified a copied file.
            runCatching { remove(directory, game) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }
    fun remove(directory: Path, game: Path) {
        if (!game.resolve(marker).exists(LinkOption.NOFOLLOW_LINKS)) {
            require(listOf(library, launcher).none { game.resolve(it).exists(LinkOption.NOFOLLOW_LINKS) }) { "Fichiers SDK présents sans enregistrement" }
            return
        }
        val record = read(game)
        require(source(directory).sha256() == record.files[library]) { "Le SDK actif appartient à une autre installation" }
        validate(game, record)
        game.resolve(marker).atomicWrite(hubJson.encodeToString(record.copy(state = "removing")))
        record.files.keys.forEach { game.resolve(it).deleteIfExists() }
        game.resolve(marker).deleteExisting()
    }
    fun installedLauncher(game: Path): Path? {
        if (!game.resolve(marker).exists(LinkOption.NOFOLLOW_LINKS)) return null
        val record = read(game)
        require(record.state == "ready") { "Installation SDK interrompue : restaurez le profil avant de lancer le jeu" }
        validate(game, record)
        return game.resolve(launcher)
    }
}
