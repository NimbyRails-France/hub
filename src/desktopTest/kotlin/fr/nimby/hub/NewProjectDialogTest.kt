package fr.nimby.hub

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.nimby.hub.i18n.I18n
import fr.nimby.hub.model.*
import fr.nimby.hub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NewProjectDialogTest {
    @get:Rule val compose = createComposeRule()
    @org.junit.Before fun french() { I18n.configure("fr", "fr") }
    @org.junit.After fun resetLanguage() { I18n.configure("auto", "fr") }

    private fun state() = HubState(HubSettings(gameDirectory = "C:/Game", developerMode = true, profile = HubProfile.DEVELOP,
        paths = HubPaths(localMods = "C:/Mes projets", kotlinSdk = "C:/SDK/0.9.0-alpha.1")),
        kotlinKitVersion = "0.9.0-alpha.1", gameHash = "a".repeat(64))

    private fun fill(id: String = "mes-signaux") {
        compose.onNodeWithTag("new-project-id").performScrollTo().performTextInput(id)
        compose.onNodeWithTag("new-project-name").performScrollTo().performTextInput("Mes signaux")
        compose.onNodeWithTag("new-project-author").performScrollTo().performTextInput("Équipe NRF")
        compose.onNodeWithTag("new-project-description").performScrollTo().performTextInput("Un signal pour ma carte.")
    }

    @Test fun developerFlowCreatesSignalRequestAndKeepsFieldsThroughFailure() {
        var state by mutableStateOf(state())
        var open by mutableStateOf(false)
        var dismissed = 0
        var requested: NewProjectRequest? = null
        compose.setContent {
            HubScreen(if (open) state.copy(operationError = null) else state, HubActions(createProject = { open = true }))
            if (open) NewProjectDialog(state, onDismiss = { dismissed++; open = false }, onCreate = { requested = it })
        }
        compose.onNodeWithText("Créer un projet").performClick()
        compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        fill()
        compose.onNodeWithTag("new-project-destination").performScrollTo().assertTextEquals("C:/Mes projets/mes-signaux")
        compose.onNodeWithText("Kit SDK Kotlin : 0.9.0-alpha.1").assertExists()
        capture("new-project-signal-ready")
        compose.onNodeWithText("Créer le projet").assertIsEnabled().performClick()
        assertEquals(NewProjectRequest("mes-signaux", "Mes signaux", "Équipe NRF", "Un signal pour ma carte.", ProjectTemplate.SIGNAL), requested)
        assertEquals(0, dismissed)
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        compose.onNodeWithText("Annuler").assertIsNotEnabled()
        compose.onNodeWithTag("new-project-id").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false, operationError = "Le dossier du projet existe déjà : C:/Mes projets/mes-signaux") }
        compose.onNodeWithText("Le dossier du projet existe déjà : C:/Mes projets/mes-signaux").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Opération interrompue").assertDoesNotExist()
        compose.onNodeWithTag("new-project-name").assertTextContains("Mes signaux")
        compose.onNodeWithTag("new-project-author").assertTextContains("Équipe NRF")
        compose.onNodeWithTag("new-project-version").assertTextContains("0.1.0")
        compose.onNodeWithText("Créer le projet").assertIsEnabled()
        capture("new-project-failure-preserves-form")
        compose.onNodeWithTag("new-project-id").performScrollTo().performTextReplacement("mes-signaux-bis")
        compose.onNodeWithText("Créer le projet").performClick()
        assertEquals("mes-signaux-bis", requested?.id)
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("new-project-dialog").assertDoesNotExist()
    }

    @Test fun toolTemplateAndRequiredEnvironmentAreAvailableInEnglish() {
        I18n.choose("en")
        var state by mutableStateOf(state().copy(settings = state().settings.copy(paths = HubPaths()), kotlinKitVersion = ""))
        var requested: NewProjectRequest? = null
        compose.setContent { NewProjectDialog(state, {}, { requested = it }) }
        fill("my-tool")
        compose.onNodeWithText("Choose the local projects directory in Settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create project").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(paths = HubPaths(localMods = "C:\\My projects"))) }
        compose.onNodeWithText("Choose a Kotlin SDK kit in Settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create project").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(paths = state.settings.paths.copy(kotlinSdk = "C:/SDK")), kotlinKitVersion = "0.9.0-alpha.1") }
        compose.onNodeWithText("In-game tool").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("A tool integrated into NIMBY Rails, accessible in the game.").assertIsDisplayed()
        capture("new-project-tool-english")
        compose.onNodeWithTag("new-project-destination").performScrollTo().assertTextEquals("C:\\My projects\\my-tool")
        compose.onNodeWithText("Create project").assertIsEnabled().performClick()
        assertEquals(ProjectTemplate.TOOL, requested?.template)
    }

    @Test fun invalidFieldsAndProjectIdentityCollisionsDisableCreation() {
        var state by mutableStateOf(state())
        var created = 0
        compose.setContent { NewProjectDialog(state, {}, { created++ }) }
        fill()
        compose.onNodeWithText("Créer le projet").assertIsEnabled()
        compose.onNodeWithTag("new-project-id").performScrollTo().performTextReplacement("../escape")
        compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        compose.onNodeWithText("Identifiant de projet invalide").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("new-project-id").performScrollTo().performTextReplacement("sdk")
        compose.onNodeWithText("Cet identifiant est réservé").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("new-project-id").performScrollTo().performTextReplacement("mes-signaux")
        compose.onNodeWithTag("new-project-name").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        compose.onNodeWithTag("new-project-name").performTextInput("Mes signaux")
        compose.onNodeWithTag("new-project-version").performScrollTo().performTextReplacement("latest")
        compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        compose.onNodeWithTag("new-project-version").performTextReplacement("0.1.0-alpha.1")
        compose.onNodeWithText("Créer le projet").assertIsEnabled()
        val sameProject = Project("mes-signaux", "native-mod", "1.0.0")
        val original = state
        val variants = listOf(
            original.copy(projects = listOf(sameProject)),
            original.copy(settings = original.settings.copy(installed = mapOf(sameProject.id to InstalledProject(sameProject.id, sameProject.kind, sameProject.version, "C:/installed")))),
            original.copy(settings = original.settings.copy(development = DevelopmentSettings(projects = mapOf(sameProject.id to LocalProject(sameProject, "C:/local", "C:/local/mod.json"))))),
        )
        variants.forEach { collision ->
            compose.runOnIdle { state = collision }
            compose.onNodeWithText("Cet identifiant de projet est déjà utilisé : mes-signaux").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Créer le projet").assertIsNotEnabled()
        }
        compose.runOnIdle { state = original }
        compose.onNodeWithText("Créer le projet").assertIsEnabled()
        assertEquals(0, created)
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("new-project-dialog").captureToImage()
        val output = Path.of("build/gradle/reports/ui/$name.png")
        Files.createDirectories(output.parent)
        org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).use { rendered ->
            rendered.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
        }
    }
}
