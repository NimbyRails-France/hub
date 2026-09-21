package fr.nimby.hub.storage

import fr.nimby.hub.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.nio.file.*
import java.security.MessageDigest
import kotlin.io.path.*

fun Path.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        digest.update(buffer, 0, size)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

fun Path.jsonText(maximum: Long = 2 * 1024 * 1024): String {
    require(fileSize() <= maximum) { "Fichier JSON trop volumineux" }
    return readText().removePrefix("\uFEFF")
}

fun Path.atomicWrite(text: String) {
    parent.createDirectories()
    val temporary = Files.createTempFile(parent, ".nrf-write-", ".tmp")
    try {
        temporary.writeText(text)
        try { Files.move(temporary, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, this, StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.deleteIfExists() }
}

fun defaultDataDirectory(): Path = when {
    System.getProperty("os.name").startsWith("Windows") -> Path(System.getenv("LOCALAPPDATA"), "NimbyRailsFrance", "NRFHub")
    System.getProperty("os.name").startsWith("Mac") -> Path(System.getProperty("user.home"), "Library", "Application Support", "NimbyRailsFrance", "NRFHub")
    else -> Path(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "NimbyRailsFrance", "NRFHub")
}

class SettingsStore(val directory: Path) {
    private val file = directory.resolve("settings.json")
    fun read(): HubSettings {
        directory.createDirectories()
        if (!file.exists()) return HubSettings(root = directory.resolve("projects").toString(), paths = HubPaths(
            mods = directory.resolve("mods").toString(), tools = directory.resolve("tools").toString(),
            sdk = directory.resolve("sdk").toString(), development = directory.resolve("development").toString()))
        // A broken profile must never silently overwrite the installed-project records.
        val text = file.jsonText()
        val legacy = "schema" !in hubJson.parseToJsonElement(text).jsonObject
        val decoded = hubJson.decodeFromString<HubSettings>(text)
        val settings = decoded.copy(paths = decoded.paths.copy(development = decoded.paths.development.ifBlank { directory.resolve("development").toString() }),
            legacyProtection = decoded.legacyProtection || (legacy && decoded.developerMode))
        listOf(settings.installed, settings.activeDevelopment, settings.development.prepared).forEach { records -> records.forEach { (id, record) ->
            require(id == record.id && ProjectRules.identifier.matches(id) && Versions.valid(record.version) && record.kind in setOf("sdk", "tco", "native-mod")) { "Registre d'installation invalide" }
        } }
        settings.development.sdkVersions.forEach { (version, record) ->
            require(version == record.version && Versions.valid(version) && record.id == "sdk" && record.kind == "sdk") { "Registre des versions SDK invalide" }
        }
        settings.development.projects.forEach { (id, local) ->
            require(id == local.project.id && Path(local.directory).isAbsolute) { "Projet local invalide" }
            ProjectRules.validate(local.project, remote = false, requireArtifact = local.task.isBlank())
        }
        require(settings.development.origins.keys.all(ProjectRules.identifier::matches)) { "Sélection locale invalide" }
        require(settings.schema == 2) { "Format de réglages non pris en charge" }
        if (legacy && !directory.resolve("settings.before-profiles.json").exists()) directory.resolve("settings.before-profiles.json").atomicWrite(text)
        return settings
    }
    fun write(settings: HubSettings) = file.atomicWrite(hubJson.encodeToString(settings))
}
