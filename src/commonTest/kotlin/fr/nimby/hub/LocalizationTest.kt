package fr.nimby.hub

import fr.nimby.hub.i18n.*
import fr.nimby.hub.model.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class LocalizationTest {
    @AfterTest fun reset() { I18n.configure("auto", "fr") }

    @Test fun automaticLanguageAndExistingModelObjectsRefresh() {
        val page = HubPage.SETTINGS
        val result = BuildResult("Prêt à tester", true)
        val state = HubState(HubSettings())
        I18n.configure("auto", "fr-CA")
        assertEquals("Paramètres", page.label)
        I18n.configure("auto", "de-DE")
        assertEquals("Settings", page.label)
        assertEquals("Develop", HubProfile.DEVELOP.label)
        assertEquals("Game directory", PathSetting.GAME.label)
        assertEquals("Ready to test", result.displayStatus)
        assertEquals("Ready", state.status)
        assertEquals("Notifications stopped", state.relayStatus)
        assertEquals("Prêt à tester", result.status) // Persistent value never depends on locale.
        I18n.choose("fr"); assertEquals("Prêt à tester", result.displayStatus)
        I18n.choose("invalid"); assertEquals("auto", I18n.preference)
    }

    @Test fun translationsPreserveEveryArgument() {
        val placeholder = Regex("\\{[0-9]+}")
        assertTrue(english.size >= 450)
        english.forEach { (source, translated) ->
            assertTrue(translated.isNotBlank())
            assertEquals(placeholder.findAll(source).map { it.value }.toSet(), placeholder.findAll(translated).map { it.value }.toSet(), source)
        }
        val text = message("Téléchargement : {0}", "Étoile {1}")
        I18n.choose("en"); assertEquals("Downloading: Étoile {1}", text.text)
        I18n.choose("fr"); assertEquals("Téléchargement : Étoile {1}", text.text)
        assertEquals("custom {0}", BuildResult("custom {0}").displayStatus)
    }

    @Test fun settingsRemainCompatibleAndPreserveLanguage() {
        assertEquals("auto", hubJson.decodeFromString<HubSettings>("{}").language)
        val settings = HubSettings(language = "en", gameDirectory = "C:/Étoile {0}", profile = HubProfile.DEVELOP)
        assertEquals(settings, hubJson.decodeFromString<HubSettings>(hubJson.encodeToString(settings)))
    }
}
