package fr.nimby.hub.model

import fr.nimby.hub.i18n.tr

import kotlinx.serialization.Serializable

@Serializable enum class HubProfile(val sourceLabel: String) {
    PLAY("Jouer"), DEVELOP("Développer");
    val label get() = tr(sourceLabel)
}
@Serializable enum class ModOrigin { PUBLISHED, LOCAL }
enum class HubPage(private val sourceLabel: String, val kind: String?) {
    MODS("Mods", "native-mod"), TOOLS("Utilitaires", "tco"), SDK("SDK", "sdk"),
    ACTIVITY("Téléchargements", null), SETTINGS("Paramètres", null);
    val label get() = tr(sourceLabel)
}
enum class PathSetting(private val sourceLabel: String, val development: Boolean = false, val file: Boolean = false) {
    GAME("Dossier du jeu"), MODS("Installation des mods"), TOOLS("Installation des utilitaires"),
    SDK("Installation des SDK"), LOCAL_MODS("Projets locaux de mods", true),
    LOCAL_TOOLS("Projets locaux d’utilitaires", true), DEVELOPMENT("Installation de développement", true),
    KOTLIN_SDK("Kit SDK Kotlin pour compiler", true), IDEA("Exécutable IntelliJ IDEA", true, true);
    val label get() = tr(sourceLabel)
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
    val builder: String = "gradle",
) {
    val buildsSdk get() = builder == "windows-sdk"
}
@Serializable data class BuildResult(
    val status: String = "À compiler", val ready: Boolean = false, val completedAt: String = "",
    val sdkDirectory: String = "", val fingerprint: String = "", val error: String = "",
    // Snapshot of the successful build's input, never inferred from today's kit.
    val sdkVersion: String = "",
) {
    // Persist the existing source values so old settings keep working in either
    // language. Unknown diagnostic values remain literal, including braces.
    val displayStatus get() = if (status in buildStatuses) tr(status) else status
}
private val buildStatuses = setOf("À compiler", "À recompiler avec le nouveau SDK", "Paquet à importer",
    "Paquet prêt à tester", "Compilation en cours", "Préparation en cours", "Prêt à tester",
    "Compilation annulée", "Compilation ou préparation échouée")
@Serializable data class DevelopmentSettings(
    val origins: Map<String, ModOrigin> = emptyMap(),
    val projects: Map<String, LocalProject> = emptyMap(),
    val prepared: Map<String, InstalledProject> = emptyMap(),
    val builds: Map<String, BuildResult> = emptyMap(),
    val sdkVersions: Map<String, InstalledProject> = emptyMap(),
    val sdkVersion: String = "",
    val sharedDataAcknowledged: Boolean = false,
)

/** A new kit invalidates mod binaries even when the human-readable version is unchanged. */
fun DevelopmentSettings.withKotlinKitChanged() = copy(builds = builds.mapValues { (id, result) ->
    if (projects[id]?.project?.kind == "native-mod") result.copy(ready = false, status = "À recompiler avec le nouveau SDK") else result
})

/** Commit the runtime and kit selection together only after all build/import checks succeed. */
fun HubSettings.withSuccessfulBuild(local: LocalProject, record: InstalledProject, result: BuildResult, kotlinKit: String? = null): HubSettings {
    val id = local.project.id
    val dev = if (kotlinKit != null) development.withKotlinKitChanged() else development
    return copy(paths = if (kotlinKit != null) paths.copy(kotlinSdk = kotlinKit) else paths,
        development = dev.copy(projects = dev.projects + (id to local), prepared = dev.prepared + (id to record),
            origins = dev.origins + (id to ModOrigin.LOCAL), builds = dev.builds + (id to result)))
}

object ProfileRules {
    fun resolve(settings: HubSettings): Map<String, InstalledProject> {
        if (!settings.developing) return settings.installed
        val result = settings.installed.toMutableMap()
        if (settings.development.sdkVersion.isNotBlank()) result["sdk"] =
            settings.development.sdkVersions[settings.development.sdkVersion] ?: error(tr("Version SDK sélectionnée absente"))
        settings.development.origins.filterValues { it == ModOrigin.LOCAL }.forEach { (id, _) ->
            require(settings.development.builds[id]?.ready == true) { tr("{0} : compilation ou préparation requise", id) }
            result[id] = settings.development.prepared[id] ?: error(tr("{0} : résultat local absent", id))
        }
        return result
    }
    fun validate(records: Map<String, InstalledProject>, gameHash: String) {
        val modIds = mutableSetOf<String>()
        records.forEach { (id, record) ->
            require(id == record.id) { tr("Identité de projet incohérente") }
            val project = record.asProject()
            if (record.kind == "native-mod") {
                require(!project.modId.isNullOrBlank() && modIds.add(project.modId.lowercase())) { tr("Identifiant de mod absent ou déjà utilisé : {0}", record.name) }
            }
            val reason = ProjectRules.incompatibility(project, gameHash, records)
            require(reason == null) { "${record.name} : $reason" }
        }
    }
}
