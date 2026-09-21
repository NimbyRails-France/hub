package fr.nimby.hub.model

import kotlinx.serialization.Serializable

@Serializable enum class HubProfile(val label: String) { PLAY("Jouer"), DEVELOP("Développer") }
@Serializable enum class ModOrigin { PUBLISHED, LOCAL }
enum class HubPage(val label: String, val kind: String?) {
    MODS("Mods", "native-mod"), TOOLS("Utilitaires", "tco"), SDK("SDK", "sdk"),
    ACTIVITY("Téléchargements", null), SETTINGS("Paramètres", null)
}
enum class PathSetting(val label: String, val development: Boolean = false, val file: Boolean = false) {
    GAME("Dossier du jeu"), MODS("Installation des mods"), TOOLS("Installation des utilitaires"),
    SDK("Installation des SDK"), LOCAL_MODS("Projets locaux de mods", true),
    LOCAL_TOOLS("Projets locaux d’utilitaires", true), DEVELOPMENT("Installation de développement", true),
    KOTLIN_SDK("Kit SDK Kotlin pour compiler", true), IDEA("Exécutable IntelliJ IDEA", true, true)
}
@Serializable data class HubPaths(
    val mods: String = "", val tools: String = "", val sdk: String = "",
    val localMods: String = "", val localTools: String = "", val development: String = "",
    val kotlinSdk: String = "", val idea: String = "",
)
fun HubSettings.path(setting: PathSetting): String = when (setting) {
    PathSetting.GAME -> gameDirectory
    PathSetting.MODS -> paths.mods.ifBlank { root }
    PathSetting.TOOLS -> paths.tools.ifBlank { root }
    PathSetting.SDK -> paths.sdk.ifBlank { root }
    PathSetting.LOCAL_MODS -> paths.localMods
    PathSetting.LOCAL_TOOLS -> paths.localTools
    PathSetting.DEVELOPMENT -> paths.development
    PathSetting.KOTLIN_SDK -> paths.kotlinSdk
    PathSetting.IDEA -> paths.idea
}
@Serializable data class LocalProject(
    val project: Project, val directory: String, val manifest: String,
    val task: String = "", val archive: String = "",
)
@Serializable data class BuildResult(
    val status: String = "À compiler", val ready: Boolean = false, val completedAt: String = "",
    val sdkDirectory: String = "", val fingerprint: String = "", val error: String = "",
)
@Serializable data class DevelopmentSettings(
    val origins: Map<String, ModOrigin> = emptyMap(),
    val projects: Map<String, LocalProject> = emptyMap(),
    val prepared: Map<String, InstalledProject> = emptyMap(),
    val builds: Map<String, BuildResult> = emptyMap(),
    val sdkVersions: Map<String, InstalledProject> = emptyMap(),
    val sdkVersion: String = "",
    val sharedDataAcknowledged: Boolean = false,
)

object ProfileRules {
    fun resolve(settings: HubSettings): Map<String, InstalledProject> {
        if (!settings.developing) return settings.installed
        val result = settings.installed.toMutableMap()
        if (settings.development.sdkVersion.isNotBlank()) result["sdk"] =
            settings.development.sdkVersions[settings.development.sdkVersion] ?: error("Version SDK sélectionnée absente")
        settings.development.origins.filterValues { it == ModOrigin.LOCAL }.forEach { (id, _) ->
            require(settings.development.builds[id]?.ready == true) { "$id : compilation ou préparation requise" }
            result[id] = settings.development.prepared[id] ?: error("$id : résultat local absent")
        }
        return result
    }
    fun validate(records: Map<String, InstalledProject>, gameHash: String) {
        val modIds = mutableSetOf<String>()
        records.forEach { (id, record) ->
            require(id == record.id) { "Identité de projet incohérente" }
            val project = record.asProject()
            if (record.kind == "native-mod") {
                require(!project.modId.isNullOrBlank() && modIds.add(project.modId.lowercase())) { "Identifiant de mod absent ou déjà utilisé : ${record.name}" }
            }
            val reason = ProjectRules.incompatibility(project, gameHash, records)
            require(reason == null) { "${record.name} : $reason" }
        }
    }
}
