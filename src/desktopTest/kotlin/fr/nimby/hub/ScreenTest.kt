package fr.nimby.hub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asSkiaBitmap
import fr.nimby.hub.model.*
import fr.nimby.hub.ui.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture() {
        compose.setContent {
            val mod = Project("signalisationfrancaiserealiste", "native-mod", "0.2.0", name = "Signalisation française réaliste")
            val sdk = Project("sdk", "sdk", "0.7.3", name = "NimbyRails France SDK")
            var settings by remember { mutableStateOf(HubSettings(gameDirectory = "C:/Jeux/NIMBY Rails", root = "C:/NRF/Projets",
                installed = mapOf(mod.id to InstalledProject(mod.id, mod.kind, "0.2.0", "C:/NRF/Mods/SignalisationFrancaiseRealiste"),
                    "sdk" to InstalledProject("sdk", "sdk", "0.7.2", "C:/NRF/SDK")),
                development = DevelopmentSettings(sdkVersion = "0.7.3", sdkVersions = mapOf("0.7.3" to InstalledProject("sdk", "sdk", "0.7.3", "C:/NRF/Developpement/SDK"))))) }
            HubScreen(HubState(settings, projects = listOf(mod, sdk), availableProjects = setOf(mod.id, "sdk"), gameRunning = true), HubActions(
                developerMode = { settings = settings.copy(developerMode = it, profile = if (it) HubProfile.DEVELOP else HubProfile.PLAY) },
                profile = { settings = settings.copy(profile = it) }))
        }
    }

    @Test fun localPathsAreHiddenUntilDevelopmentIsEnabled() {
        fixture()
        compose.onNodeWithText("Ajouter un projet local").assertDoesNotExist()
        capture("mods")
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithText("Projets locaux de mods").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mode développeur").performScrollTo().performClick()
        compose.onNodeWithText("Projets locaux de mods").assertExists()
        compose.onNodeWithText("Projets locaux d’utilitaires").assertExists()
        compose.onNodeWithText("Installation de développement").performScrollTo().assertIsDisplayed()
        capture("settings-development")
        compose.onNodeWithText("Mods", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Ajouter un projet local").assertExists()
        compose.onNodeWithText("Redémarrer NIMBY Rails").assertExists()
    }

    @Test fun sdkHasItsOwnPageAndSeparatesNormalFromTestingVersion() {
        fixture()
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithContentDescription("Mode développeur").performScrollTo().performClick()
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("SDK habituel", substring = false).assertCountEquals(2)
        compose.onNodeWithText("SDK 0.7.3").assertExists()
        compose.onNodeWithText("Choisir une autre version publiée").assertExists()
        capture("sdk-development")
        compose.onNodeWithText("Utilitaires", useUnmergedTree = true).performClick()
        compose.onNodeWithText("NimbyRails France SDK").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage()
        val output = Path.of("build/gradle/reports/ui/$name.png")
        Files.createDirectories(output.parent)
        org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).use { rendered ->
            rendered.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
        }
    }
}
