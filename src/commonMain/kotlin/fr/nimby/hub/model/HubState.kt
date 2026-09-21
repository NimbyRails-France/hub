package fr.nimby.hub.model

data class HubState(
    val settings: HubSettings,
    val projects: List<Project> = emptyList(),
    val gameHash: String = "",
    val busy: Boolean = false,
    val installing: Boolean = false,
    val status: String = "Prêt",
    val relayStatus: String = "Notifications arrêtées",
    val log: List<String> = emptyList(),
    val readyHubVersion: String? = null,
    val windows: Boolean = true,
    val releaseErrors: Map<String, String> = emptyMap(),
    val availableProjects: Set<String> = emptySet(),
    val building: Boolean = false,
    val sdkReleases: List<Project> = emptyList(),
    val gameRunning: Boolean = false,
    val operationError: String? = null,
    val recoveryRequired: Boolean = false,
) {
    val visibleProjects get(): List<Project> {
        val listed = projects + settings.installed.values.filter { installed -> projects.none { it.id == installed.id } }.map { it.asProject() }
        val locals = if (settings.developing) settings.development.projects.values.map { it.project } + settings.development.prepared.values.map { it.asProject() } else emptyList()
        val combined = (listed + locals).distinctBy { it.id }
        return combined + releaseErrors.keys.filter { id -> combined.none { it.id == id } }.map { Project(it, if (it in listOf("sdk", "tco")) it else "native-mod", "0.0.0") }
    }
}
