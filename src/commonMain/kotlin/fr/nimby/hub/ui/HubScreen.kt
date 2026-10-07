package fr.nimby.hub.ui

import fr.nimby.hub.i18n.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.nimby.hub.model.*

data class HubActions(
    val chooseGame: () -> Unit = {}, val chooseRoot: () -> Unit = {},
    val developerMode: (Boolean) -> Unit = {}, val automatic: (Boolean) -> Unit = {},
    val refresh: () -> Unit = {}, val install: (Project) -> Unit = {},
    val open: (InstalledProject) -> Unit = {}, val remove: (Project) -> Unit = {},
    val rollback: (Project) -> Unit = {}, val importLocal: () -> Unit = {}, val restart: () -> Unit = {},
    val channel: (String, String) -> Unit = { _, _ -> }, val notes: (Project) -> Unit = {},
    val choosePath: (PathSetting) -> Unit = {}, val profile: (HubProfile) -> Unit = {},
    val addLocal: (String) -> Unit = {}, val origin: (String, ModOrigin) -> Unit = { _, _ -> },
    val createProject: () -> Unit = {},
    val compile: (String) -> Unit = {}, val openIdea: (LocalProject) -> Unit = {},
    val forgetLocal: (String) -> Unit = {}, val launchGame: (Boolean) -> Unit = {},
    val launchTool: (String) -> Unit = {}, val applyProfile: () -> Unit = {},
    val loadSdkVersions: () -> Unit = {}, val installSdk: (Project) -> Unit = {},
    val sdkVersion: (String) -> Unit = {}, val clearError: () -> Unit = {},
    val loadKotlinKits: () -> Unit = {}, val downloadKotlinKit: (KotlinKit) -> Unit = {},
    val releaseLegacy: () -> Unit = {},
    val recoverProfile: () -> Unit = {},
    val openLogs: () -> Unit = {}, val repairSdk: () -> Unit = {},
    val exportLogs: () -> Unit = {},
    val language: (String) -> Unit = {},
    val checkGame: () -> Unit = {},
)

private val ink = Color(0xFF1C2634)
private val muted = Color(0xFF637083)
private val accent = Color(0xFF315FC1)

@Composable
fun HubScreen(state: HubState, actions: HubActions, logo: Painter? = null) {
    var page by remember { mutableStateOf(HubPage.MODS) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = Color(0xFFF5F6F8),
        surface = Color.White, onSurface = ink, onBackground = ink, surfaceVariant = Color(0xFFEBEEF3),
        outline = Color(0xFFB4BDCA))) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.width(184.dp).fillMaxHeight().background(Color(0xFF192332)).padding(16.dp)) {
                Row(Modifier.padding(top = 14.dp, start = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (logo != null) Image(logo, contentDescription = tr("Logo NimbyRails France"), modifier = Modifier.size(40.dp))
                    Text("NRF", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text("NIMBY RAILS FRANCE", color = Color(0xFF9EADC0), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 10.dp, bottom = 36.dp))
                HubPage.entries.forEach { item ->
                    val active = page == item
                    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp).background(
                        if (active) Color(0xFF2D3C52) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { page = item; selected = null; query = "" }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(item.label, color = if (active) Color.White else Color(0xFFB7C2D0),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(if (state.gameRunning) tr("Jeu en cours") else tr("Jeu fermé"), color = Color(0xFFB7C2D0),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                Text("Hub $HUB_VERSION", color = Color(0xFF8292A8), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 26.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(page.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(when (page) {
                            HubPage.MODS -> tr("Votre bibliothèque de mods")
                            HubPage.TOOLS -> tr("Les outils qui accompagnent le jeu")
                            HubPage.SDK -> tr("SDK et chargeur du jeu")
                            HubPage.ACTIVITY -> tr("Opérations et journal du Hub")
                            HubPage.SETTINGS -> tr("Installation et préférences")
                        }, color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.settings.developerMode) {
                        HubProfile.entries.forEach { profile ->
                            if (state.settings.profile == profile) FilledTonalButton({ actions.profile(profile) }, enabled = !state.busy) { Text(profile.label) }
                            else TextButton({ actions.profile(profile) }, enabled = !state.busy) { Text(profile.label) }
                        }
                    }
                    Button({ actions.launchGame(state.gameRunning) }, enabled = !state.busy && state.windows && !state.checkingGame && state.gameHash.isNotBlank()) {
                        Text(if (state.gameRunning) tr("Redémarrer NIMBY Rails") else tr("Lancer NIMBY Rails"))
                    }
                }
                HorizontalDivider(color = Color(0xFFE1E5EB))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.windows && state.gameHash.isBlank() &&
                    (state.settings.gameDirectory.isBlank() || state.gameIssue != null || state.checkingGame)) {
                    GameSetup(state, actions)
                }
                if (state.settings.profile != state.settings.appliedProfile || state.settings.disableDeveloperAfterApply) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFFFF1D6)).padding(horizontal = 26.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("{0} en attente · fermeture du jeu nécessaire avant la bascule", state.settings.profile.label), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(actions.applyProfile, enabled = !state.busy && !state.gameRunning) { Text(tr("Appliquer")) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(26.dp)) {
                    when (page) {
                        HubPage.SETTINGS -> SettingsPage(state, actions)
                        HubPage.ACTIVITY -> ActivityPage(state, actions)
                        HubPage.SDK -> SdkPage(state, actions)
                        else -> {
                            val projects = state.visibleProjects.filter { it.kind == page.kind && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)) }
                            val project = projects.firstOrNull { it.id == selected } ?: projects.firstOrNull()
                            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("Rechercher…")) },
                                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
                                    if (state.settings.developing) {
                                        if (page == HubPage.MODS) OutlinedButton(actions.createProject, enabled = !state.busy && state.windows) { Text(tr("Créer un projet")) }
                                        OutlinedButton({ actions.addLocal(page.kind!!) }, enabled = !state.busy && state.windows) { Text(tr("Ajouter un projet local")) }
                                    }
                                    OutlinedButton(actions.refresh, enabled = !state.busy) { Text(tr("Actualiser")) }
                                }
                                if (projects.isEmpty()) EmptyLibrary(page, state.settings.developing)
                                else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFE1E5EB))) {
                                        LazyColumn {
                                            items(projects, key = { it.id }) { item ->
                                                val record = state.settings.selectedRecord(item.id)
                                                Column(Modifier.fillMaxWidth().background(if (item.id == project?.id) Color(0xFFECF1FB) else Color.White)
                                                    .clickable { selected = item.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    ProjectHeading(item)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(record?.version ?: tr("Non installé"), color = muted, style = MaterialTheme.typography.bodySmall)
                                                        if (state.settings.developing && state.settings.development.origins[item.id] == ModOrigin.LOCAL)
                                                            Text(tr("Local"), color = accent, style = MaterialTheme.typography.labelMedium)
                                                    }
                                                    if (item.kind == "native-mod" && state.settings.developing && state.settings.development.origins[item.id] == ModOrigin.LOCAL) {
                                                        val sdkVersion = state.settings.development.builds[item.id]?.sdkVersion.orEmpty()
                                                        Text(if (sdkVersion.isBlank()) tr("SDK de compilation non enregistré") else tr("Compilé avec SDK {0}", sdkVersion),
                                                            color = muted, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                HorizontalDivider(color = Color(0xFFEEF0F4))
                                            }
                                        }
                                    }
                                    project?.let { ProjectDetail(it, state, actions, Modifier.width(330.dp).fillMaxHeight(), openSdk = {
                                        page = HubPage.SDK; selected = null; query = ""
                                    }) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFE1E5EB))
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 26.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.status, Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("Actif : {0}", state.settings.appliedProfile.label), color = muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        state.operationError?.let { error -> AlertDialog(onDismissRequest = actions.clearError,
            title = { Text(tr("Opération interrompue")) }, text = { Text(error) },
            confirmButton = { TextButton(actions.clearError) { Text(tr("Fermer")) } }) }
    }
}

@Composable
private fun GameSetup(state: HubState, actions: HubActions) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 12.dp),
        color = Color(0xFFECF1FB), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Préparer votre première installation"), style = MaterialTheme.typography.titleMedium)
            Text(tr("1. Choisissez le dossier de NIMBY Rails. Le Hub vérifiera le jeu avant l’installation du SDK et des mods."),
                style = MaterialTheme.typography.bodyMedium)
            Text(tr("Dans Steam : clic droit sur NIMBY Rails → Gérer → Parcourir les fichiers locaux."),
                style = MaterialTheme.typography.bodySmall, color = muted)
            if (state.settings.gameDirectory.isNotBlank()) Text(state.settings.gameDirectory,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.checkingGame) Text(tr("Vérification du jeu…"), color = accent)
            else state.gameIssue?.let { Text(it.text, color = muted, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(actions.chooseGame, enabled = !state.busy && !state.checkingGame) { Text(tr("Choisir le dossier du jeu")) }
                if (state.settings.gameDirectory.isNotBlank()) TextButton(actions.checkGame,
                    enabled = !state.busy && !state.checkingGame) { Text(tr("Vérifier à nouveau")) }
            }
            Text(tr("2. Une fois le jeu reconnu, installez le SDK puis les mods de votre choix."),
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun EmptyLibrary(page: HubPage, development: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (page == HubPage.MODS) tr("Aucun mod à afficher") else tr("Aucun utilitaire à afficher"), style = MaterialTheme.typography.titleLarge)
        Text(if (development) tr("Actualisez le catalogue ou ajoutez un projet local.") else tr("Actualisez le catalogue pour retrouver les projets disponibles."),
            color = muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProjectHeading(project: Project, prominent: Boolean = false) {
    // This describes the author's project status, independently of the release
    // channel. Wrapping keeps long names and translated labels readable.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(project.name, style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            fontWeight = if (prominent) FontWeight.SemiBold else null, modifier = Modifier.align(Alignment.CenterVertically))
        val label = when (project.developmentStatus) {
            "in-development" -> tr("En cours de développement")
            "stable" -> tr("Stable")
            else -> null // Older manifests and future statuses never imply a maturity level.
        }
        if (label != null) Surface(shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.CenterVertically),
            color = if (project.developmentStatus == "stable") Color(0xFFE5F3EB) else Color(0xFFFFF1D6),
            contentColor = if (project.developmentStatus == "stable") Color(0xFF25613E) else Color(0xFF785009)) {
            Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ProjectDetail(project: Project, state: HubState, actions: HubActions, modifier: Modifier = Modifier, openSdk: () -> Unit = {}) {
    val s = state.settings
    val installed = s.installed[project.id]
    val local = s.development.projects[project.id]
    val prepared = s.development.prepared[project.id]
    val localSelected = s.developing && s.development.origins[project.id] == ModOrigin.LOCAL
    val record = s.selectedRecord(project.id)
    val result = s.development.builds[project.id]
    // Published packages update the usual installation. A selected development
    // SDK is shown separately below and must not enable that installation.
    val installationReason = runCatching { ProjectRules.incompatibility(project, state.gameHash, s.installed) }
        .getOrElse { tr("Prérequis de l’installation non vérifiables") }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectHeading(project, prominent = true)
        Text(if (project.kind == "tco") tr("Utilitaire") else if (project.kind == "sdk") tr("SDK et chargeur") else tr("Mod"), color = muted, style = MaterialTheme.typography.labelLarge)
        if (s.developing) {
            HorizontalDivider()
            Text(tr("Version au prochain lancement"), style = MaterialTheme.typography.titleSmall)
            OriginRow(tr("Version publiée"), installed?.version ?: tr("Non installée"), !localSelected, !state.busy) { actions.origin(project.id, ModOrigin.PUBLISHED) }
            OriginRow(tr("Projet ou paquet local"), local?.directory ?: prepared?.let { tr("Version {0}", it.version) } ?: tr("Aucun projet associé"),
                localSelected, !state.busy && (local != null || prepared != null)) { actions.origin(project.id, ModOrigin.LOCAL) }
        }
        if (localSelected) {
            Text(result?.displayStatus ?: tr("À compiler"), color = if (result?.ready == true) Color(0xFF267249) else muted, style = MaterialTheme.typography.titleSmall)
            result?.completedAt?.takeIf { it.isNotBlank() }?.let { Text(tr("Préparé à {0}", it), color = muted, style = MaterialTheme.typography.bodySmall) }
            result?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (project.kind == "native-mod") {
                InfoLine(tr("SDK du paquet local"), result?.sdkVersion?.takeIf { it.isNotBlank() } ?: tr("Non enregistré"))
                InfoLine(tr("Prochaine compilation"), state.kotlinKitVersion.ifBlank { tr("Aucun kit choisi") })
                val activeSdk = (if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment else s.installed)["sdk"]
                InfoLine(tr("SDK actif dans le jeu"), activeSdk?.version ?: tr("Non installé"))
                if (result?.sdkVersion.isNullOrBlank()) Text(tr("Une compilation depuis le Hub enregistrera la version utilisée pour ce paquet."),
                    color = muted, style = MaterialTheme.typography.bodySmall)
            }
            local?.let {
                OutlinedButton({ actions.openIdea(it) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text(tr("Ouvrir dans IntelliJ")) }
                Button({ actions.compile(project.id) }, enabled = !state.busy && it.task.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(tr("Compiler")) }
                if (it.task.isBlank()) Text(tr("Compilez ce projet dans son IDE puis importez le paquet produit."), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(actions.importLocal, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Importer un paquet local")) }
            TextButton({ actions.forgetLocal(project.id) }, enabled = !state.busy) { Text(tr("Retirer du profil")) }
        } else {
            InfoLine(tr("Installée"), installed?.version ?: "—")
            InfoLine(tr("Disponible"), if (project.id in state.availableProjects) project.version else tr("Non vérifiée"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelSelector(project.name, s.selectedChannel(project.id), !state.busy) { actions.channel(project.id, it) }
            }
            if (project.kind != "sdk") SdkPrerequisitesCard(project, state, openSdk)
            Button({ actions.install(project) }, enabled = !state.busy && state.windows && !state.checkingGame &&
                installationReason == null &&
                project.id in state.availableProjects && s.appliedProfile == HubProfile.PLAY && !s.legacyProtection,
                modifier = Modifier.fillMaxWidth()) { Text(if (installed == null) tr("Installer") else tr("Mettre à jour")) }
            if (s.appliedProfile != HubProfile.PLAY) Text(tr("Revenez à Jouer pour modifier l’installation habituelle."), color = muted, style = MaterialTheme.typography.bodySmall)
            installed?.let {
                OutlinedButton({ actions.rollback(project) }, enabled = !state.busy && s.appliedProfile == HubProfile.PLAY, modifier = Modifier.fillMaxWidth()) { Text(tr("Restaurer la version précédente")) }
                TextButton({ actions.remove(project) }, enabled = !state.busy && s.appliedProfile == HubProfile.PLAY) { Text(tr("Désinstaller")) }
            }
        }
        HorizontalDivider()
        val reason = if (localSelected) runCatching {
            ProfileRules.resolve(s).let { ProjectRules.incompatibility(record?.asProject() ?: project, state.gameHash, it) }
        }.getOrElse { it.message ?: tr("Prérequis de l’installation non vérifiables") } else installationReason
        val compatibility = if (reason != null) {
            if (localSelected) reason else tr("Installation habituelle : {0}", reason)
        } else if (localSelected) tr("Compatible avec le profil sélectionné") else tr("Compatible avec l’installation habituelle")
        Text(state.releaseErrors[project.id] ?: compatibility,
            color = muted, style = MaterialTheme.typography.bodySmall)
        if (record != null) {
            Text(record.directory, color = muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ actions.open(record) }) { Text(tr("Ouvrir le dossier d’installation")) }
            if (project.kind == "tco") Button({ actions.launchTool(project.id) }, enabled = !state.busy && (!localSelected || result?.ready == true)) { Text(tr("Lancer l’utilitaire")) }
        }
        if (project.releaseUrl.isNotBlank()) TextButton({ actions.notes(project) }) { Text(tr("Notes de version")) }
    }
}

@Composable
private fun SdkPrerequisitesCard(project: Project, state: HubState, openSdk: () -> Unit) {
    val prerequisites = SdkPrerequisites.forProject(project, state.settings)
    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF2F5FA), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(tr("Prérequis"))
            if (project.id !in state.availableProjects) {
                Text(tr("Actualisez le catalogue pour vérifier les prérequis de la version disponible."), color = muted, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(tr("Pour la version {0}", project.version), color = muted, style = MaterialTheme.typography.bodySmall)
                if (prerequisites.minimum != null || prerequisites.maximumExclusive != null) {
                    Text(tr("SDK minimum : {0} (inclus)", prerequisites.minimum ?: tr("Non renseigné")), style = MaterialTheme.typography.bodySmall)
                    Text(tr("SDK maximum : {0} (exclu)", prerequisites.maximumExclusive ?: tr("Non renseigné")), style = MaterialTheme.typography.bodySmall)
                }
                val status = when (prerequisites.status) {
                    SdkPrerequisiteStatus.NOT_DECLARED -> tr("Aucune contrainte SDK déclarée")
                    SdkPrerequisiteStatus.COMPATIBLE -> tr("Compatible avec ce SDK")
                    SdkPrerequisiteStatus.MISSING -> tr("SDK manquant")
                    SdkPrerequisiteStatus.INCOMPATIBLE -> tr("SDK incompatible")
                    SdkPrerequisiteStatus.NOT_READY -> tr("SDK local à préparer")
                    SdkPrerequisiteStatus.UNVERIFIABLE -> tr("Prérequis SDK non vérifiables")
                }
                Text(status, color = if (prerequisites.status == SdkPrerequisiteStatus.COMPATIBLE) Color(0xFF267249)
                    else if (prerequisites.status in listOf(SdkPrerequisiteStatus.MISSING, SdkPrerequisiteStatus.INCOMPATIBLE, SdkPrerequisiteStatus.NOT_READY)) MaterialTheme.colorScheme.error else muted,
                    style = MaterialTheme.typography.labelLarge)
            }
            Text(tr("SDK prévu pour le profil {0} : {1}", if (state.settings.developing) HubProfile.DEVELOP.label else HubProfile.PLAY.label,
                prerequisites.selectedVersion ?: tr("Non installé")), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(openSdk) { Text(tr("Aller au SDK")) }
        }
    }
}

@Composable
private fun SdkPage(state: HubState, actions: HubActions) {
    val s = state.settings
    // The right pane installs the published SDK, even when a local source is
    // selected for development. Keep its identity and badge tied to that source.
    val publishedState = state.copy(settings = s.copy(profile = HubProfile.PLAY))
    val sdk = publishedState.visibleProjects.firstOrNull { it.id == "sdk" } ?: Project("sdk", "sdk", "0.0.0", "NimbyRails France SDK")
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SectionTitle(tr("SDK du jeu"))
            InfoLine(tr("SDK habituel"), s.installed["sdk"]?.version ?: tr("Non installé"))
            InfoLine(tr("SDK actif"), (if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment["sdk"] else s.installed["sdk"])?.version ?: tr("Aucun"))
            Text(tr("Un seul SDK est actif dans le jeu. Le retour à Jouer restaure le SDK habituel."), color = muted, style = MaterialTheme.typography.bodyMedium)
            if (s.developing) {
                HorizontalDivider()
                SectionTitle(tr("SDK pour les essais"))
                val published = s.development.origins["sdk"] != ModOrigin.LOCAL
                OriginRow(tr("SDK habituel"), s.installed["sdk"]?.version ?: tr("Non installé"), published && s.development.sdkVersion.isBlank(), !state.busy) { actions.sdkVersion("") }
                s.development.sdkVersions.toSortedMap().forEach { (version, _) ->
                    OriginRow("SDK $version", tr("Version publiée conservée pour les essais"), published && s.development.sdkVersion == version, !state.busy) { actions.sdkVersion(version) }
                }
                s.development.prepared["sdk"]?.let { local ->
                    OriginRow(tr("SDK local {0}", local.version), tr("Paquet local préparé"), !published, !state.busy) { actions.origin("sdk", ModOrigin.LOCAL) }
                }
                OutlinedButton(actions.loadSdkVersions, enabled = !state.busy) { Text(tr("Choisir une autre version publiée")) }
                state.sdkReleases.forEach { release ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SDK ${release.version}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ actions.installSdk(release) }, enabled = !state.busy) { Text(tr("Préparer")) }
                    }
                }
                OutlinedButton(actions.importLocal, enabled = !state.busy) { Text(tr("Importer un SDK local")) }
                HorizontalDivider()
                SectionTitle(tr("Projet source SDK"))
                val source = s.development.projects["sdk"]
                val build = s.development.builds["sdk"]
                if (source != null) {
                    ProjectHeading(source.project)
                    Text(source.directory, style = MaterialTheme.typography.bodySmall)
                    InfoLine(tr("Dernière lecture des sources"), source.project.version)
                    InfoLine(tr("Construction"), build?.displayStatus ?: tr("À compiler"))
                    if (!build?.error.isNullOrBlank()) Text(build!!.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (source.buildsSdk) Button({ actions.compile("sdk") }, enabled = !state.busy && state.windows) { Text(tr("Construire et préparer le SDK")) }
                    TextButton({ actions.openIdea(source) }, enabled = !state.busy) { Text(tr("Ouvrir les sources dans IntelliJ")) }
                    TextButton({ actions.forgetLocal("sdk") }, enabled = !state.busy) { Text(tr("Retirer le projet source")) }
                }
                OutlinedButton({ actions.addLocal("sdk") }, enabled = !state.busy && state.windows) {
                    Text(if (source == null) tr("Choisir le projet SDK") else tr("Changer de projet SDK"))
                }
                Text(tr("La construction relit VERSION, compile et teste le SDK, prépare son paquet et sélectionne le kit Kotlin correspondant. Recompilez ensuite vos mods. Le jeu utilisera ces fichiers à la prochaine activation du profil."), color = muted, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                SectionTitle(tr("SDK pour compiler"))
                InfoLine(tr("Kit Kotlin sélectionné"), state.kotlinKitVersion.ifBlank { tr("Aucun kit valide sélectionné") })
                DirectoryRow(PathSetting.KOTLIN_SDK.label, s.paths.kotlinSdk, !state.busy) { actions.choosePath(PathSetting.KOTLIN_SDK) }
                OutlinedButton(actions.loadKotlinKits, enabled = !state.busy && state.windows) { Text(tr("Télécharger un kit Kotlin")) }
                Text(tr("Kits publiés · canal {0}", s.selectedChannel("sdk")), color = muted, style = MaterialTheme.typography.bodySmall)
                state.kotlinKits.forEach { kit ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kotlin SDK ${kit.version}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ actions.downloadKotlinKit(kit) }, enabled = !state.busy) { Text(tr("Télécharger et utiliser")) }
                    }
                }
                Text(tr("Le kit Kotlin contient sdk.json et les bibliothèques de compilation. Sa version sera vérifiée avec celle du SDK choisi pour le jeu."), color = muted, style = MaterialTheme.typography.bodySmall)
            } else OutlinedButton(actions.refresh, enabled = !state.busy) { Text(tr("Actualiser le catalogue")) }
        }
        ProjectDetail(sdk, publishedState, actions, Modifier.width(330.dp).fillMaxHeight())
    }
}

@Composable
private fun SettingsPage(state: HubState, actions: HubActions) {
    val s = state.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(tr("Langue"))
                Text(tr("La langue du Hub est indépendante de celle du jeu et des mods."), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            var choosingLanguage by remember { mutableStateOf(false) }
            Box {
                TextButton({ choosingLanguage = true }, enabled = !state.busy) { Text(I18n.languageName(I18n.preference)) }
                DropdownMenu(choosingLanguage, { choosingLanguage = false }) {
                    listOf("auto", "fr", "en").forEach { code ->
                        DropdownMenuItem(text = { Text(I18n.languageName(code)) }, onClick = { choosingLanguage = false; actions.language(code) })
                    }
                }
            }
        }
        HorizontalDivider()
        if (state.recoveryRequired) {
            SectionTitle(tr("Activation interrompue"))
            Text(tr("Une bascule précédente n’a pas pu se terminer. Fermez le jeu pour restaurer son état précédent."), color = muted)
            OutlinedButton(actions.recoverProfile, enabled = !state.busy) { Text(tr("Restaurer l’activation précédente")) }
            HorizontalDivider()
        }
        if (state.windows) {
            SectionTitle(tr("Réparation du chargeur SDK"))
            Text(tr("Restaure la SDL d’origine après vérification du manifeste et des DLL, avec sauvegarde. Fermez le jeu, puis réappliquez le profil ou réinstallez le SDK après réparation."), color = muted)
            OutlinedButton(actions.repairSdk, enabled = !state.busy && !state.gameRunning && !state.recoveryRequired) { Text(tr("Réparer le chargeur SDK")) }
        }
        SectionTitle(tr("Emplacements"))
        PathSetting.entries.filter { !it.development }.forEach { key ->
            DirectoryRow(key.label, s.path(key), !state.busy) { actions.choosePath(key) }
        }
        Text(tr("Les nouveaux emplacements s’appliquent aux prochaines installations. Les projets déjà installés restent à leur emplacement."), color = muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle(tr("Mises à jour"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Mises à jour automatiques"), style = MaterialTheme.typography.bodyMedium)
                Text(if (s.developerMode) tr("Le Hub reste à jour. Les mises à jour automatiques du SDK et des mods sont suspendues.") else tr("Installer les versions compatibles lorsque le jeu est fermé."), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.automatic, actions.automatic, enabled = !state.busy)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Canal du Hub"), Modifier.weight(1f)); ChannelSelector("NRF Hub", s.selectedChannel("hub"), !state.busy) { actions.channel("hub", it) } }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(tr("Mode développeur"))
                Text(tr("Projets locaux, compilation et SDK de test. Le SDK habituel est restauré à la désactivation."), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.developerMode, actions.developerMode, enabled = !state.installing && !state.building,
                modifier = Modifier.semantics { contentDescription = tr("Mode développeur") })
        }
        if (s.developerMode) {
            PathSetting.entries.filter { it.development }.forEach { key -> DirectoryRow(key.label, s.path(key), !state.busy) { actions.choosePath(key) } }
            Text(tr("Les sauvegardes et réglages globaux de NIMBY Rails restent partagés. Utilisez une copie de votre partie pour les essais. Les installations de développement sont séparées."), color = muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(actions.importLocal, enabled = !state.busy && s.developing) { Text(tr("Importer un paquet local")) }
        }
        if (s.legacyProtection) {
            HorizontalDivider()
            Text(tr("Ancien profil protégé"), style = MaterialTheme.typography.titleSmall)
            Text(tr("Les anciennes installations peuvent contenir des paquets locaux. Leur remplacement reste bloqué jusqu’à votre vérification."), color = muted)
            OutlinedButton(actions.releaseLegacy, enabled = !state.busy) { Text(tr("J’ai vérifié les installations")) }
        }
        if (!state.windows) Text(tr("L’intégration au jeu n’est pas disponible sur ce système."), color = muted)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActivityPage(state: HubState, actions: HubActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(if (state.busy) tr("Opération en cours") else tr("Aucune opération en cours"))
        Text(state.status, color = muted)
        Text(state.relayStatus, color = muted, style = MaterialTheme.typography.bodySmall)
        state.readyHubVersion?.let { version -> OutlinedButton(actions.restart, enabled = !state.busy && !state.settings.developerMode) { Text(tr("Redémarrer le Hub pour appliquer {0}", version)) } }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionTitle(tr("Journal"))
            OutlinedButton(actions.openLogs) { Text(tr("Ouvrir le dossier des journaux")) }
            OutlinedButton(actions.exportLogs, enabled = !state.busy) { Text(tr("Exporter les logs NRF")) }
        }
        if (state.logFile.isNotBlank()) SelectionContainer { Text(state.logFile, style = MaterialTheme.typography.bodySmall) }
        if (state.log.isEmpty()) Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Aucun événement pour cette session"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Les opérations et diagnostics du Hub apparaîtront ici. Les journaux des sessions précédentes restent accessibles dans le dossier des journaux ou dans l’export NRF."), color = muted, style = MaterialTheme.typography.bodyMedium)
            }
        } else SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(6.dp)).padding(16.dp), reverseLayout = true) {
                items(state.log.asReversed()) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
@Composable private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun OriginRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, if (enabled) choose else null, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun ChannelSelector(projectName: String, selected: String, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf("stable" to "Stable", "beta" to tr("Bêta"), "alpha" to "Alpha")
    val description = tr("Modifier le canal de {0}", projectName)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
            Text(tr("Canal : {0}", labels[selected] ?: "Stable"))
            Spacer(Modifier.width(10.dp))
            val color = LocalContentColor.current
            Canvas(Modifier.size(16.dp)) {
                drawLine(color, Offset(size.width * .2f, size.height * .35f), Offset(size.width * .5f, size.height * .65f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(size.width * .5f, size.height * .65f), Offset(size.width * .8f, size.height * .35f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            labels.forEach { (channel, label) -> DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { expanded = false; change(channel) }) }
        }
    }
}
@Composable private fun DirectoryRow(label: String, path: String, enabled: Boolean, choose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(path.ifBlank { tr("Non renseigné") }, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(choose, enabled = enabled) { Text(tr("Parcourir…")) }
    }
}
