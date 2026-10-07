package fr.nimby.hub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.nimby.hub.i18n.tr
import fr.nimby.hub.model.*

/** Keep form state while creation is running or has failed. Only the owner of
 * the operation closes this dialog after a successful creation. */
@Composable
fun NewProjectDialog(state: HubState, onDismiss: () -> Unit, onCreate: (NewProjectRequest) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("0.1.0") }
    var template by remember { mutableStateOf(ProjectTemplate.SIGNAL) }
    val request = NewProjectRequest(id = id, name = name, author = author, description = description, template = template, version = version)
    val fieldIssue = runCatching { request.validate() }.exceptionOrNull()?.message
    val knownIds = state.projects.map { it.id } + state.settings.installed.keys +
        state.settings.development.projects.keys + state.settings.development.prepared.keys
    val environmentIssue = when {
        !state.settings.developing -> tr("Sélectionnez Développer pour créer un projet")
        !state.windows -> tr("La création de projets est disponible sous Windows.")
        state.settings.paths.localMods.isBlank() -> tr("Choisissez le dossier des projets locaux dans Paramètres")
        state.settings.paths.kotlinSdk.isBlank() || !Versions.valid(state.kotlinKitVersion) -> tr("Choisissez un kit SDK Kotlin dans Paramètres")
        knownIds.any { it.equals(id, ignoreCase = true) } -> tr("Cet identifiant de projet est déjà utilisé : {0}", id)
        else -> null
    }
    val canCreate = !state.busy && fieldIssue == null && environmentIssue == null
    val destination = if (state.settings.paths.localMods.isBlank()) tr("Dossier des projets locaux non choisi") else {
        val root = state.settings.paths.localMods.trimEnd('/', '\\')
        val separator = if ('\\' in root) "\\" else "/"
        root + separator + id.ifBlank { tr("identifiant-du-projet") }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF315FC1))) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) onDismiss() },
            modifier = Modifier.width(720.dp).testTag("new-project-dialog"),
            containerColor = Color.White,
            title = { Text(tr("Créer un projet")) },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tr("Choisissez un point de départ. Le Hub crée les sources dans votre dossier de projets locaux."))
                    Text(tr("Type de projet"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = template == ProjectTemplate.SIGNAL, onClick = { template = ProjectTemplate.SIGNAL },
                            label = { Text(tr("Signal")) }, enabled = !state.busy)
                        FilterChip(selected = template == ProjectTemplate.TOOL, onClick = { template = ProjectTemplate.TOOL },
                            label = { Text(tr("Outil en jeu")) }, enabled = !state.busy)
                    }
                    Text(if (template == ProjectTemplate.SIGNAL) tr("Un point de départ pour créer vos propres signaux.")
                        else tr("Un outil intégré à NIMBY Rails, accessible depuis le jeu."), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(id, { id = it }, label = { Text(tr("Identifiant du projet")) },
                            supportingText = { Text(tr("Lettres minuscules, chiffres et tirets ; commencez par une lettre.")) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new-project-id"))
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Nom du projet")) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new-project-name"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(author, { author = it }, label = { Text(tr("Auteur")) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new-project-author"))
                        OutlinedTextField(version, { version = it }, label = { Text(tr("Version initiale")) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new-project-version"))
                    }
                    OutlinedTextField(description, { description = it }, label = { Text(tr("Description")) },
                        minLines = 2, maxLines = 4, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("new-project-description"))
                    HorizontalDivider()
                    Text(tr("Dossier à créer"), style = MaterialTheme.typography.titleSmall)
                    Text(destination, modifier = Modifier.testTag("new-project-destination"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("Kit SDK Kotlin : {0}", state.kotlinKitVersion.ifBlank { tr("Non renseigné") }), style = MaterialTheme.typography.titleSmall)
                    if (state.settings.paths.kotlinSdk.isNotBlank()) Text(state.settings.paths.kotlinSdk, style = MaterialTheme.typography.bodySmall)
                    Text(tr("Le projet comprend mod.json, la configuration Gradle, Entry.kt et des tests."), style = MaterialTheme.typography.bodySmall)
                    if (template == ProjectTemplate.SIGNAL) Text(tr("Les textures de départ sont fournies dans le dossier assets."), style = MaterialTheme.typography.bodySmall)
                    Text(tr("Après création, compilez le projet dans le Hub, puis ouvrez-le dans IntelliJ pour le personnaliser."), style = MaterialTheme.typography.bodySmall)
                    environmentIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (id.isNotEmpty() || name.isNotEmpty() || author.isNotEmpty() || description.isNotEmpty() || version != "0.1.0") {
                        fieldIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    state.operationError?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) Text(tr("Création du projet…"), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { Button({ if (canCreate) onCreate(request) }, enabled = canCreate) { Text(tr("Créer le projet")) } },
            dismissButton = { TextButton({ if (!state.busy) onDismiss() }, enabled = !state.busy) { Text(tr("Annuler")) } },
        )
    }
}
