package fr.nimby.hub.ui

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
    val compile: (String) -> Unit = {}, val openIdea: (LocalProject) -> Unit = {},
    val forgetLocal: (String) -> Unit = {}, val launchGame: (Boolean) -> Unit = {},
    val launchTool: (String) -> Unit = {}, val applyProfile: () -> Unit = {},
    val loadSdkVersions: () -> Unit = {}, val installSdk: (Project) -> Unit = {},
    val sdkVersion: (String) -> Unit = {}, val clearError: () -> Unit = {},
    val releaseLegacy: () -> Unit = {},
    val recoverProfile: () -> Unit = {},
)

private val ink = Color(0xFF1C2634)
private val muted = Color(0xFF637083)
private val accent = Color(0xFF315FC1)

@Composable
fun HubScreen(state: HubState, actions: HubActions) {
    var page by remember { mutableStateOf(HubPage.MODS) }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = Color(0xFFF5F6F8),
        surface = Color.White, onSurface = ink, onBackground = ink, surfaceVariant = Color(0xFFEBEEF3),
        outline = Color(0xFFB4BDCA))) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.width(184.dp).fillMaxHeight().background(Color(0xFF192332)).padding(16.dp)) {
                Text("NRF", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, start = 10.dp))
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
                Text(if (state.gameRunning) "Jeu en cours" else "Jeu fermé", color = Color(0xFFB7C2D0),
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
                            HubPage.MODS -> "Votre bibliothèque de mods"
                            HubPage.TOOLS -> "Les outils qui accompagnent le jeu"
                            HubPage.SDK -> "SDK et chargeur du jeu"
                            HubPage.ACTIVITY -> "Opérations et journal du Hub"
                            HubPage.SETTINGS -> "Installation et préférences"
                        }, color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.settings.developerMode) {
                        HubProfile.entries.forEach { profile ->
                            if (state.settings.profile == profile) FilledTonalButton({ actions.profile(profile) }, enabled = !state.busy) { Text(profile.label) }
                            else TextButton({ actions.profile(profile) }, enabled = !state.busy) { Text(profile.label) }
                        }
                    }
                    Button({ actions.launchGame(state.gameRunning) }, enabled = !state.busy && state.windows && state.settings.gameDirectory.isNotBlank()) {
                        Text(if (state.gameRunning) "Redémarrer NIMBY Rails" else "Lancer NIMBY Rails")
                    }
                }
                HorizontalDivider(color = Color(0xFFE1E5EB))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.settings.profile != state.settings.appliedProfile || state.settings.disableDeveloperAfterApply) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFFFF1D6)).padding(horizontal = 26.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.settings.profile.label} en attente · fermeture du jeu nécessaire avant la bascule", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(actions.applyProfile, enabled = !state.busy && !state.gameRunning) { Text("Appliquer") }
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
                                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Rechercher…") },
                                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
                                    if (state.settings.developing) OutlinedButton({ actions.addLocal(page.kind!!) }, enabled = !state.busy && state.windows) { Text("Ajouter un projet local") }
                                    OutlinedButton(actions.refresh, enabled = !state.busy) { Text("Actualiser") }
                                }
                                if (projects.isEmpty()) EmptyLibrary(page, state.settings.developing)
                                else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFE1E5EB))) {
                                        LazyColumn {
                                            items(projects, key = { it.id }) { item ->
                                                val record = state.settings.selectedRecord(item.id)
                                                Column(Modifier.fillMaxWidth().background(if (item.id == project?.id) Color(0xFFECF1FB) else Color.White)
                                                    .clickable { selected = item.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(record?.version ?: "Non installé", color = muted, style = MaterialTheme.typography.bodySmall)
                                                        if (state.settings.developing && state.settings.development.origins[item.id] == ModOrigin.LOCAL)
                                                            Text("Local", color = accent, style = MaterialTheme.typography.labelMedium)
                                                    }
                                                }
                                                HorizontalDivider(color = Color(0xFFEEF0F4))
                                            }
                                        }
                                    }
                                    project?.let { ProjectDetail(it, state, actions, Modifier.width(330.dp).fillMaxHeight()) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFE1E5EB))
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 26.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.status, Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Actif : ${state.settings.appliedProfile.label}", color = muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        state.operationError?.let { error -> AlertDialog(onDismissRequest = actions.clearError,
            title = { Text("Opération interrompue") }, text = { Text(error) },
            confirmButton = { TextButton(actions.clearError) { Text("Fermer") } }) }
    }
}

@Composable
private fun EmptyLibrary(page: HubPage, development: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (page == HubPage.MODS) "Aucun mod à afficher" else "Aucun utilitaire à afficher", style = MaterialTheme.typography.titleLarge)
        Text(if (development) "Actualisez le catalogue ou ajoutez un projet local." else "Actualisez le catalogue pour retrouver les projets disponibles.",
            color = muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProjectDetail(project: Project, state: HubState, actions: HubActions, modifier: Modifier = Modifier) {
    val s = state.settings
    val installed = s.installed[project.id]
    val local = s.development.projects[project.id]
    val prepared = s.development.prepared[project.id]
    val localSelected = s.developing && s.development.origins[project.id] == ModOrigin.LOCAL
    val record = s.selectedRecord(project.id)
    val result = s.development.builds[project.id]
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(if (project.kind == "tco") "Utilitaire" else if (project.kind == "sdk") "SDK et chargeur" else "Mod", color = muted, style = MaterialTheme.typography.labelLarge)
        if (s.developing) {
            HorizontalDivider()
            Text("Version au prochain lancement", style = MaterialTheme.typography.titleSmall)
            OriginRow("Version publiée", installed?.version ?: "Non installée", !localSelected, !state.busy) { actions.origin(project.id, ModOrigin.PUBLISHED) }
            OriginRow("Projet ou paquet local", local?.directory ?: prepared?.let { "Version ${it.version}" } ?: "Aucun projet associé",
                localSelected, !state.busy && (local != null || prepared != null)) { actions.origin(project.id, ModOrigin.LOCAL) }
        }
        if (localSelected) {
            Text(result?.status ?: "À compiler", color = if (result?.ready == true) Color(0xFF267249) else muted, style = MaterialTheme.typography.titleSmall)
            result?.completedAt?.takeIf { it.isNotBlank() }?.let { Text("Préparé à $it", color = muted, style = MaterialTheme.typography.bodySmall) }
            result?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            local?.let {
                OutlinedButton({ actions.openIdea(it) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Ouvrir dans IntelliJ") }
                Button({ actions.compile(project.id) }, enabled = !state.busy && it.task.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Compiler") }
                if (it.task.isBlank()) Text("Compilez ce projet dans son IDE puis importez le paquet produit.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(actions.importLocal, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Importer un paquet local") }
            TextButton({ actions.forgetLocal(project.id) }, enabled = !state.busy) { Text("Retirer du profil") }
        } else {
            InfoLine("Installée", installed?.version ?: "—")
            InfoLine("Disponible", if (project.id in state.availableProjects) project.version else "Non vérifiée")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Canal", modifier = Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodyMedium)
                ChannelSelector(s.selectedChannel(project.id), !state.busy) { actions.channel(project.id, it) }
            }
            Button({ actions.install(project) }, enabled = !state.busy && state.windows && project.id in state.availableProjects && s.appliedProfile == HubProfile.PLAY && !s.legacyProtection,
                modifier = Modifier.fillMaxWidth()) { Text(if (installed == null) "Installer" else "Mettre à jour") }
            if (s.appliedProfile != HubProfile.PLAY) Text("Revenez à Jouer pour modifier l’installation habituelle.", color = muted, style = MaterialTheme.typography.bodySmall)
            installed?.let {
                OutlinedButton({ actions.rollback(project) }, enabled = !state.busy && s.appliedProfile == HubProfile.PLAY, modifier = Modifier.fillMaxWidth()) { Text("Restaurer la version précédente") }
                TextButton({ actions.remove(project) }, enabled = !state.busy && s.appliedProfile == HubProfile.PLAY) { Text("Désinstaller") }
            }
        }
        HorizontalDivider()
        val reason = runCatching { ProfileRules.resolve(s).let { ProjectRules.incompatibility(record?.asProject() ?: project, state.gameHash, it) } }.getOrElse { it.message }
        Text(state.releaseErrors[project.id] ?: reason ?: "Compatible avec le profil sélectionné", color = muted, style = MaterialTheme.typography.bodySmall)
        if (record != null) {
            Text(record.directory, color = muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ actions.open(record) }) { Text("Ouvrir le dossier d’installation") }
            if (project.kind == "tco") Button({ actions.launchTool(project.id) }, enabled = !state.busy && (!localSelected || result?.ready == true)) { Text("Lancer l’utilitaire") }
        }
        if (project.releaseUrl.isNotBlank()) TextButton({ actions.notes(project) }) { Text("Notes de version") }
    }
}

@Composable
private fun SdkPage(state: HubState, actions: HubActions) {
    val s = state.settings
    val sdk = state.visibleProjects.firstOrNull { it.id == "sdk" } ?: Project("sdk", "sdk", "0.0.0", "NimbyRails France SDK")
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SectionTitle("SDK du jeu")
            InfoLine("SDK habituel", s.installed["sdk"]?.version ?: "Non installé")
            InfoLine("SDK actif", (if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment["sdk"] else s.installed["sdk"])?.version ?: "Aucun")
            Text("Un seul SDK est actif dans le jeu. Le retour à Jouer restaure le SDK habituel.", color = muted, style = MaterialTheme.typography.bodyMedium)
            if (s.developing) {
                HorizontalDivider()
                SectionTitle("SDK pour les essais")
                val published = s.development.origins["sdk"] != ModOrigin.LOCAL
                OriginRow("SDK habituel", s.installed["sdk"]?.version ?: "Non installé", published && s.development.sdkVersion.isBlank(), !state.busy) { actions.sdkVersion("") }
                s.development.sdkVersions.toSortedMap().forEach { (version, _) ->
                    OriginRow("SDK $version", "Version publiée conservée pour les essais", published && s.development.sdkVersion == version, !state.busy) { actions.sdkVersion(version) }
                }
                s.development.prepared["sdk"]?.let { local ->
                    OriginRow("SDK local ${local.version}", "Paquet local préparé", !published, !state.busy) { actions.origin("sdk", ModOrigin.LOCAL) }
                }
                OutlinedButton(actions.loadSdkVersions, enabled = !state.busy) { Text("Choisir une autre version publiée") }
                state.sdkReleases.forEach { release ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SDK ${release.version}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ actions.installSdk(release) }, enabled = !state.busy) { Text("Préparer") }
                    }
                }
                OutlinedButton(actions.importLocal, enabled = !state.busy) { Text("Importer un SDK local") }
                HorizontalDivider()
                SectionTitle("SDK pour compiler")
                DirectoryRow(PathSetting.KOTLIN_SDK.label, s.paths.kotlinSdk, !state.busy) { actions.choosePath(PathSetting.KOTLIN_SDK) }
                Text("Le kit Kotlin contient sdk.json et les bibliothèques de compilation. Sa version sera vérifiée avec celle du SDK choisi pour le jeu.", color = muted, style = MaterialTheme.typography.bodySmall)
            } else OutlinedButton(actions.refresh, enabled = !state.busy) { Text("Actualiser le catalogue") }
        }
        ProjectDetail(sdk, state.copy(settings = s.copy(profile = HubProfile.PLAY)), actions, Modifier.width(330.dp).fillMaxHeight())
    }
}

@Composable
private fun SettingsPage(state: HubState, actions: HubActions) {
    val s = state.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.recoveryRequired) {
            SectionTitle("Activation interrompue")
            Text("Une bascule précédente n’a pas pu se terminer. Fermez le jeu pour restaurer son état précédent.", color = muted)
            OutlinedButton(actions.recoverProfile, enabled = !state.busy) { Text("Restaurer l’activation précédente") }
            HorizontalDivider()
        }
        SectionTitle("Emplacements")
        PathSetting.entries.filter { !it.development }.forEach { key ->
            DirectoryRow(key.label, s.path(key), !state.busy) { actions.choosePath(key) }
        }
        Text("Les nouveaux emplacements s’appliquent aux prochaines installations. Les projets déjà installés restent à leur emplacement.", color = muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("Mises à jour")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mises à jour automatiques", style = MaterialTheme.typography.bodyMedium)
                Text(if (s.developerMode) "Application automatique suspendue pendant le développement." else "Installer les versions compatibles lorsque le jeu est fermé.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.automatic, actions.automatic, enabled = !state.busy)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Canal du Hub", Modifier.weight(1f)); ChannelSelector(s.selectedChannel("hub"), !state.busy) { actions.channel("hub", it) } }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Mode développeur")
                Text("Projets locaux, compilation et SDK de test. Le SDK habituel est restauré à la désactivation.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.developerMode, actions.developerMode, enabled = !state.installing && !state.building,
                modifier = Modifier.semantics { contentDescription = "Mode développeur" })
        }
        if (s.developerMode) {
            PathSetting.entries.filter { it.development }.forEach { key -> DirectoryRow(key.label, s.path(key), !state.busy) { actions.choosePath(key) } }
            Text("Les sauvegardes et réglages globaux de NIMBY Rails restent partagés. Utilisez une copie de votre partie pour les essais. Les installations de développement sont séparées.", color = muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(actions.importLocal, enabled = !state.busy && s.developing) { Text("Importer un paquet local") }
        }
        if (s.legacyProtection) {
            HorizontalDivider()
            Text("Ancien profil protégé", style = MaterialTheme.typography.titleSmall)
            Text("Les anciennes installations peuvent contenir des paquets locaux. Leur remplacement reste bloqué jusqu’à votre vérification.", color = muted)
            OutlinedButton(actions.releaseLegacy, enabled = !state.busy) { Text("J’ai vérifié les installations") }
        }
        if (!state.windows) Text("L’intégration au jeu n’est pas disponible sur ce système.", color = muted)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActivityPage(state: HubState, actions: HubActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(if (state.busy) "Opération en cours" else "Aucune opération en cours")
        Text(state.status, color = muted)
        Text(state.relayStatus, color = muted, style = MaterialTheme.typography.bodySmall)
        state.readyHubVersion?.let { version -> OutlinedButton(actions.restart, enabled = !state.busy && !state.settings.developerMode) { Text("Redémarrer le Hub pour appliquer $version") } }
        HorizontalDivider()
        SectionTitle("Journal")
        SelectionContainer {
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
@Composable private fun ChannelSelector(selected: String, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf("stable" to "Stable", "beta" to "Bêta", "alpha" to "Alpha")
    Box {
        TextButton({ expanded = true }, enabled = enabled) { Text(labels[selected] ?: "Stable") }
        DropdownMenu(expanded, { expanded = false }) {
            labels.forEach { (channel, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; change(channel) }) }
        }
    }
}
@Composable private fun DirectoryRow(label: String, path: String, enabled: Boolean, choose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(path.ifBlank { "Non renseigné" }, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(choose, enabled = enabled) { Text("Parcourir…") }
    }
}
