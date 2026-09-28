package fr.nimby.hub.i18n

import androidx.compose.runtime.*

/** Only presentation depends on this observable choice. Profile identities,
 * paths, manifests and in-flight operations retain their original values. */
object I18n {
    var preference by mutableStateOf("auto"); private set
    private var system by mutableStateOf("fr")
    val language get() = if (preference == "auto") system else preference
    fun configure(choice: String, systemLanguage: String) {
        system = if (systemLanguage.lowercase().replace('_', '-').substringBefore('-') == "fr") "fr" else "en"
        choose(choice)
    }
    fun choose(choice: String) { preference = normalize(choice) }
    fun normalize(choice: String) = choice.takeIf { it in listOf("auto", "fr", "en") } ?: "auto"
    fun languageName(code: String) = when (code) { "fr" -> "Français"; "en" -> "English"; else -> tr("Automatique (système)") }
}

private val argument = Regex("\\{([0-9]+)}")

/** Substitute once after lookup: a user's path or name is literal data, never
 * another template. Missing translations retain their French source text. */
fun tr(source: String, vararg values: Any?): String {
    val pattern = if (I18n.language == "fr") source else english[source] ?: source
    return argument.replace(pattern) { match ->
        val index = match.groupValues[1].toInt()
        require(index < values.size) { "Missing translation argument $index: $source" }
        when (val value = values[index]) { is UiText -> value.text; else -> value.toString() }
    }
}

/** The status can change language in place, while historical log entries keep
 * the exact text emitted at the time. External diagnostics remain literal. */
data class UiText(val source: String, val values: List<Any?> = emptyList(), val literal: Boolean = false) {
    val text get() = if (literal) source else tr(source, *values.toTypedArray())
}
fun message(source: String, vararg values: Any?) = UiText(source, values.toList())
