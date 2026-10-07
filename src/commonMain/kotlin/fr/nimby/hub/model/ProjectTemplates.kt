package fr.nimby.hub.model

import fr.nimby.hub.i18n.tr

/** Both templates are in-game Native mods, not standalone utilities. */
enum class ProjectTemplate { SIGNAL, TOOL }

data class NewProjectRequest(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val template: ProjectTemplate,
    val version: String = "0.1.0",
) {
    val normalizedDescription get() = description.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
    /** Shared with the dialog; filesystem and SDK checks remain in the generator. */
    fun validate() {
        require(ProjectRules.identifier.matches(id)) { tr("Identifiant de projet invalide") }
        require(id !in setOf("sdk", "hub", "tco") && !Regex("(?i)con|prn|aux|nul|com[1-9]|lpt[1-9]").matches(id)) {
            tr("Cet identifiant est réservé")
        }
        require(Versions.valid(version)) { tr("Version invalide") }
        require(name.isNotBlank() && name.encodeToByteArray().size <= 256 && name.none { it.isISOControl() }) {
            tr("Le nom doit contenir de 1 à 256 octets, sans caractère de contrôle")
        }
        require(author.isNotBlank() && author.encodeToByteArray().size <= 256 && author.none { it.isISOControl() }) {
            tr("L’auteur doit contenir de 1 à 256 octets, sans caractère de contrôle")
        }
        require(description.isNotBlank() && description.encodeToByteArray().size <= 4096 &&
            normalizedDescription.replace("\n", "<br>").length <= 4096 &&
            description.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            tr("La description doit contenir de 1 à 4096 octets, sans caractère de contrôle")
        }
    }
}
