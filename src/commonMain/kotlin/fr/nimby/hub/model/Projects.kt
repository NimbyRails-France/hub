package fr.nimby.hub.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val hubJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
const val HUB_VERSION = "0.4.0"

@Serializable
data class Project(
    val id: String,
    val kind: String,
    val version: String,
    val name: String = id,
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val rootFolder: String = "",
    val gameSha256: List<String> = emptyList(),
    val sdkMin: String? = null,
    val sdkMaxExclusive: String? = null,
    val loaderApi: Int? = null,
    val modId: String? = null,
    val module: String? = null,
    val channel: String? = null,
    val releaseUrl: String = "",
    val changelog: String = "",
    val platform: String = "windows-x64",
)

@Serializable
data class InstalledProject(
    val id: String,
    val kind: String,
    val version: String,
    val directory: String,
    val name: String = id,
    val gameSha256: List<String> = emptyList(),
    val sdkMin: String? = null,
    val sdkMaxExclusive: String? = null,
    val loaderApi: Int? = null,
    val module: String? = null,
    val modLink: String? = null,
    val loaderLink: String? = null,
    val installedUtc: String = "",
    val modId: String? = null,
    val origin: String = "unknown",
    val platform: String = "windows-x64",
) {
    fun asProject() = Project(id, kind, version, name, gameSha256 = gameSha256,
        sdkMin = sdkMin, sdkMaxExclusive = sdkMaxExclusive, loaderApi = loaderApi, module = module,
        modId = modId ?: modLink?.replace('\\', '/')?.substringAfterLast('/'), platform = platform)
}

@Serializable
data class HubSettings(
    val gameDirectory: String = "",
    val root: String = "",
    val automatic: Boolean = true,
    val developerMode: Boolean = false,
    val installed: Map<String, InstalledProject> = emptyMap(),
    val notified: Map<String, String> = emptyMap(),
    val channels: Map<String, String> = emptyMap(),
    val geometry: String = "", // Preserve the previous Qt field for profile compatibility.
    val windowWidth: Int = 1120,
    val windowHeight: Int = 780,
    val schema: Int = 2,
    val paths: HubPaths = HubPaths(),
    val profile: HubProfile = HubProfile.PLAY,
    val appliedProfile: HubProfile = HubProfile.PLAY,
    val development: DevelopmentSettings = DevelopmentSettings(),
    val activeDevelopment: Map<String, InstalledProject> = emptyMap(),
    val legacyProtection: Boolean = false,
    val disableDeveloperAfterApply: Boolean = false,
) {
    fun selectedChannel(id: String) = channels[id]?.takeIf { it in listOf("stable", "beta", "alpha") } ?: "stable"
    val developing get() = developerMode && profile == HubProfile.DEVELOP
    fun selectedRecord(id: String): InstalledProject? = if (developing && development.origins[id] == ModOrigin.LOCAL)
        development.prepared[id] else if (developing && id == "sdk" && development.sdkVersion.isNotBlank())
        development.sdkVersions[development.sdkVersion] else installed[id]
}

@Serializable
data class Catalogue(val schema: Int, val projects: List<Project>, val errors: Map<String, String> = emptyMap())

@Serializable
data class HubRelease(
    val schema: Int,
    val product: String,
    val platform: String,
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val channel: String? = null,
    val installer: String? = null,
)

object Versions {
    private val format = Regex("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})(?:-(alpha|beta)\\.([1-9][0-9]{0,8}))?")
    fun valid(value: String) = format.matches(value)
    fun channel(value: String) = format.matchEntire(value)?.groupValues?.get(4)?.ifEmpty { "stable" }
    fun compare(left: String, right: String): Int {
        require(valid(left) && valid(right)) { "Version invalide" }
        val l = format.matchEntire(left)!!.groupValues
        val r = format.matchEntire(right)!!.groupValues
        for (i in 1..3) if (l[i] != r[i]) return l[i].toInt().compareTo(r[i].toInt())
        fun rank(stage: String) = when (stage) { "alpha" -> 0; "beta" -> 1; else -> 2 }
        if (l[4] != r[4]) return rank(l[4]).compareTo(rank(r[4]))
        return (l[5].toIntOrNull() ?: 0).compareTo(r[5].toIntOrNull() ?: 0)
    }
}

object ProjectRules {
    val identifier = Regex("[a-z][a-z0-9-]{0,63}")
    val hash = Regex("[a-fA-F0-9]{64}")
    val module = Regex("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*\\.(dll|so|dylib)")
    private val officialAsset = Regex("https://github\\.com/NimbyRails-France/[a-zA-Z0-9_-]+/releases/download/[^?#]+")
    fun validModule(value: String?) = value != null && value.length < 200 && module.matches(value)
    fun officialUrl(value: String) = officialAsset.matches(value)
    fun validate(project: Project, remote: Boolean = true, requireArtifact: Boolean = true) {
        require(identifier.matches(project.id)) { "Identifiant de projet invalide" }
        require(project.platform in setOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64")) { "Plateforme inconnue" }
        require(project.kind in setOf("sdk", "tco", "native-mod")) { "Type de projet inconnu" }
        require(Versions.valid(project.version)) { "Version invalide" }
        require(project.channel == null || project.channel == Versions.channel(project.version)) { "Canal incompatible avec la version" }
        if (remote) require(officialUrl(project.url)) { "Adresse de release non officielle" }
        if (remote || requireArtifact) require(project.size in 1..536_870_912 && hash.matches(project.sha256)) { "Taille ou empreinte invalide" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}").matches(project.rootFolder)) { "Racine d'archive invalide" }
        require(project.gameSha256.isNotEmpty() && project.gameSha256.all(hash::matches)) { "Versions du jeu absentes ou invalides" }
        require((project.sdkMin == null) == (project.sdkMaxExclusive == null)) { "Intervalle SDK incomplet" }
        project.sdkMin?.let { minimum ->
            require(Versions.valid(minimum) && Versions.valid(project.sdkMaxExclusive!!))
            require(Versions.compare(minimum, project.sdkMaxExclusive) < 0)
        }
        project.loaderApi?.let {
            require(it == 1 && project.kind in setOf("sdk", "native-mod")) { "API du chargeur incompatible" }
            if (project.kind == "native-mod") {
                require(validModule(project.module)) { "Nom de bibliothèque native invalide" }
                val extension = when (project.platform.substringBefore('-')) {
                    "windows" -> ".dll"
                    "linux" -> ".so"
                    "macos" -> ".dylib"
                    else -> error("Plateforme inconnue")
                }
                require(project.module!!.endsWith(extension, ignoreCase = project.platform.startsWith("windows-"))) {
                    "Bibliothèque native incompatible avec ${project.platform}"
                }
            }
        }
        if (project.kind == "native-mod") require(project.modId?.matches(Regex("[A-Za-z0-9_-]{1,100}")) == true)
    }

    fun incompatibility(project: Project, gameHash: String, installed: Map<String, InstalledProject>): String? {
        if (gameHash.isEmpty() || project.gameSha256.none { it.equals(gameHash, true) }) return "Version du jeu non prise en charge"
        val sdk = installed["sdk"]
        val loader = sdk?.loaderApi ?: if (sdk != null && Versions.compare(sdk.version, "0.7.2") >= 0) 1 else 0
        if (project.kind != "sdk" && (project.loaderApi ?: 0) > loader) return "Mise à jour du NRF Loader requise"
        if (project.sdkMin != null && (sdk == null || Versions.compare(sdk.version, project.sdkMin) < 0 ||
                    Versions.compare(sdk.version, project.sdkMaxExclusive!!) >= 0)) return "SDK ${project.sdkMin} requis"
        if (project.kind == "sdk") for (dependent in installed.values) {
            if ((dependent.loaderApi ?: 0) > (project.loaderApi ?: 0)) return "NRF Loader requis par ${dependent.name}"
            if (dependent.sdkMin != null && (Versions.compare(project.version, dependent.sdkMin) < 0 ||
                        Versions.compare(project.version, dependent.sdkMaxExclusive!!) >= 0)) return "SDK incompatible avec ${dependent.name}"
        }
        return null
    }

    fun validate(release: HubRelease) {
        require(release.schema == 1 && release.product == "NRFHub" && release.platform in setOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64"))
        require(Versions.valid(release.version) && officialUrl(release.url))
        require(release.channel == null || release.channel == Versions.channel(release.version))
        require(release.installer == null || release.installer == when (release.platform.substringBefore('-')) {
            "windows" -> "jpackage-exe"; "linux" -> "deb"; "macos" -> "dmg"; else -> ""
        }) { "Installateur incompatible avec la plateforme" }
        require(release.url.startsWith("https://github.com/NimbyRails-France/hub/releases/download/"))
        require(hash.matches(release.sha256) && release.size in 1..536_870_912)
    }
}
