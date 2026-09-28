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

    @Test fun localModShowsBuiltSelectedAndActiveSdkSeparately() {
        val mod = Project("signals", "native-mod", "1.0.0", "Signaux")
        val result = BuildResult("Prêt à tester", true, sdkVersion = "0.8.0-alpha.1")
        val settings = HubSettings(developerMode = true, profile = HubProfile.DEVELOP, appliedProfile = HubProfile.DEVELOP,
            activeDevelopment = mapOf("sdk" to InstalledProject("sdk", "sdk", "0.8.0-alpha.3", "C:/active")),
            development = DevelopmentSettings(origins = mapOf(mod.id to ModOrigin.LOCAL), builds = mapOf(mod.id to result),
                prepared = mapOf(mod.id to InstalledProject(mod.id, mod.kind, mod.version, "C:/prepared"))))
        var state by mutableStateOf(HubState(settings, kotlinKitVersion = "0.8.0-alpha.2"))
        compose.setContent { HubScreen(state, HubActions()) }
        compose.onNodeWithText("Compilé avec SDK 0.8.0-alpha.1").assertIsDisplayed()
        compose.onNodeWithText("SDK du paquet local").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.8.0-alpha.1").assertExists()
        compose.onNodeWithText("Prochaine compilation").assertExists()
        compose.onNodeWithText("0.8.0-alpha.2").assertExists()
        compose.onNodeWithText("SDK actif dans le jeu").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.8.0-alpha.3").assertExists()
        capture("mod-sdk-provenance")
        compose.runOnIdle { state = state.copy(kotlinKitVersion = "0.8.0-alpha.4") }
        compose.onNodeWithText("Compilé avec SDK 0.8.0-alpha.1").assertExists()
        compose.onNodeWithText("0.8.0-alpha.4").assertExists()
        // Old/imported packages must never borrow the currently selected kit's version.
        compose.runOnIdle { state = state.copy(settings = settings.copy(development = settings.development.copy(
            builds = mapOf(mod.id to result.copy(sdkVersion = ""))))) }
        compose.onNodeWithText("SDK de compilation non enregistré").assertExists()
        compose.onNodeWithText("Non enregistré").assertExists()
    }

    @Test fun sdkSourceBuildAndKitDownloadActionsAreAccessible() {
        val sdk = Project("sdk", "sdk", "0.8.0-alpha.2", "SDK local")
        val local = LocalProject(sdk, "C:/dev/nrf/sdk", "C:/dev/nrf/sdk/hub-local.json", "buildSdk", builder = "windows-sdk")
        val kit = KotlinKit("0.8.0-alpha.1", "windows-x64", "", 1, "")
        var state by mutableStateOf(HubState(HubSettings(developerMode = true, profile = HubProfile.DEVELOP,
            channels = mapOf("sdk" to "alpha"), development = DevelopmentSettings(projects = mapOf("sdk" to local))),
            kotlinKits = listOf(kit), kotlinKitVersion = "0.8.0-alpha.2"))
        var built = ""
        var downloaded: KotlinKit? = null
        compose.setContent {
            HubScreen(state, HubActions(compile = { built = it }, downloadKotlinKit = { downloaded = it }))
        }
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Construire et préparer le SDK").performScrollTo().performClick()
        org.junit.Assert.assertEquals("sdk", built)
        compose.onNodeWithText("Télécharger et utiliser").performScrollTo().performClick()
        org.junit.Assert.assertEquals(kit, downloaded)
        capture("sdk-kotlin-download")
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText("Télécharger et utiliser").assertIsNotEnabled()
        compose.onNodeWithText("Construire et préparer le SDK").assertIsNotEnabled()
    }

    @Test fun persistentLogLocationAndRepairAreAccessible() {
        var opened = false
        var repaired = false
        compose.setContent {
            HubScreen(HubState(HubSettings(), logFile = "C:/NRF/logs/hub.log"),
                HubActions(openLogs = { opened = true }, repairSdk = { repaired = true }))
        }
        compose.onNodeWithText("Téléchargements", useUnmergedTree = true).performClick()
        compose.onNodeWithText("C:/NRF/logs/hub.log").assertIsDisplayed()
        compose.onNodeWithText("Ouvrir le dossier des journaux").performClick()
        org.junit.Assert.assertTrue(opened)
        capture("persistent-journal")
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithText("Réparer le chargeur SDK").performScrollTo().performClick()
        org.junit.Assert.assertTrue(repaired)
        capture("sdk-repair")
    }

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
