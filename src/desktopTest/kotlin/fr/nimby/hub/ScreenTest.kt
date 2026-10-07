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
    @org.junit.Before fun french() { fr.nimby.hub.i18n.I18n.configure("fr", "fr") }
    @org.junit.After fun resetLanguage() { fr.nimby.hub.i18n.I18n.configure("auto", "fr") }
    @get:Rule val compose = createComposeRule()

    @Test fun projectStatusIsVisibleInBothProfilesAndIndependentOfTheReleaseChannel() {
        val development = Project("signals", "native-mod", "1.0.0", "AB Signalisation lumineuse",
            developmentStatus = "in-development")
        val stable = Project("placement", "native-mod", "0.1.0-alpha.3", "BA Signal Placement",
            developmentStatus = "stable")
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game"), gameHash = "a".repeat(64),
            projects = listOf(development, stable)))
        compose.setContent { HubScreen(state, HubActions()) }
        // The selected project has a badge both in the library and beside its title.
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(2)
        compose.onAllNodesWithText("En cours de développement")[0].assertIsDisplayed()
        compose.onAllNodesWithText("En cours de développement")[1].assertIsDisplayed()
        compose.onAllNodesWithText("Stable").assertCountEquals(1)
        capture("project-status-fr")
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(developerMode = true,
            profile = HubProfile.DEVELOP, appliedProfile = HubProfile.DEVELOP)) }
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(2)
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("en") }
        compose.onAllNodesWithText("In development").assertCountEquals(2)
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(0)
        capture("project-status-en")
    }

    @Test fun legacyAndUnknownStatusesDoNotShowAProjectBadge() {
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game"), gameHash = "a".repeat(64),
            projects = listOf(Project("signals", "native-mod", "0.1.0-alpha.1", "Signaux"))))
        compose.setContent { HubScreen(state, HubActions()) }
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(0)
        compose.onAllNodesWithText("Stable").assertCountEquals(0)
        compose.runOnIdle { state = state.copy(projects = state.projects.map { it.copy(developmentStatus = "future-status") }) }
        compose.onAllNodesWithText("future-status").assertCountEquals(0)
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(0)
        compose.onAllNodesWithText("Stable").assertCountEquals(0)
    }

    @Test fun sdkAndUtilitiesShowTheirDeclaredStatus() {
        val sdk = Project("sdk", "sdk", "0.9.0-alpha.1", "NRF SDK", developmentStatus = "in-development")
        val tool = Project("tco", "tco", "1.0.0", "Nimby TCO", developmentStatus = "in-development")
        compose.setContent { HubScreen(HubState(HubSettings(gameDirectory = "C:/Game"), gameHash = "a".repeat(64),
            projects = listOf(sdk, tool)), HubActions()) }
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onNodeWithText("NRF SDK").assertIsDisplayed()
        compose.onNodeWithText("En cours de développement").assertIsDisplayed()
        compose.onNodeWithText("Utilitaires", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("En cours de développement").assertCountEquals(2)
    }

    @Test fun sdkInstallationKeepsThePublishedManifestWhenLocalSourcesAreSelected() {
        val published = Project("sdk", "sdk", "0.9.0-alpha.1", "SDK publié", gameSha256 = listOf("a".repeat(64)),
            developmentStatus = "in-development")
        val local = LocalProject(published.copy(name = "SDK local", version = "0.9.0-alpha.2", developmentStatus = "stable"),
            "C:/dev/sdk", "C:/dev/sdk/hub-local.json", "buildSdk", builder = "windows-sdk")
        val settings = HubSettings(gameDirectory = "C:/Game", developerMode = true, profile = HubProfile.DEVELOP,
            development = DevelopmentSettings(projects = mapOf("sdk" to local), origins = mapOf("sdk" to ModOrigin.LOCAL)))
        var requested: Project? = null
        compose.setContent { HubScreen(HubState(settings, gameHash = "a".repeat(64), projects = listOf(published),
            availableProjects = setOf("sdk")), HubActions(install = { requested = it })) }
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onNodeWithText("SDK publié").assertIsDisplayed()
        compose.onNodeWithText("En cours de développement").assertIsDisplayed()
        compose.onNodeWithText("SDK local").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stable").assertIsDisplayed()
        compose.onNodeWithText("Installer").performScrollTo().performClick()
        org.junit.Assert.assertEquals(published, requested)
    }

    @Test fun firstInstallationGuidesFolderSelectionAndBlocksUnverifiedGames() {
        val supported = "a".repeat(64)
        val mod = Project("signals", "native-mod", "1.0.0", gameSha256 = listOf(supported))
        var state by mutableStateOf(HubState(HubSettings(), projects = listOf(mod), availableProjects = setOf(mod.id)))
        var selected = 0
        var installed = 0
        compose.setContent { HubScreen(state, HubActions(chooseGame = { selected++ }, install = { installed++ })) }
        compose.onNodeWithText("Préparer votre première installation").assertIsDisplayed()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.onNodeWithText("Choisir le dossier du jeu").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, selected)
        // Cancelling the picker leaves the guide and the installation guard in place.
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(gameDirectory = "C:/Game"), checkingGame = true) }
        compose.onNodeWithText("Vérification du jeu…").assertExists()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(checkingGame = false, gameHash = "b".repeat(64)) }
        compose.onNodeWithText("Installation habituelle : Version du jeu non prise en charge").assertExists()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(gameHash = supported) }
        compose.onNodeWithText("Préparer votre première installation").assertDoesNotExist()
        compose.onNodeWithText("Installer").performScrollTo().assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(1, installed)
    }

    @Test fun languageMenuUpdatesTheCurrentPageWithoutChangingDirectories() {
        var settings by mutableStateOf(HubSettings(root = "C:/Étoile {0}"))
        compose.setContent { HubScreen(HubState(settings), HubActions(language = {
            settings = settings.copy(language = it); fr.nimby.hub.i18n.I18n.choose(it)
        })) }
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithText("Français").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("Installation and preferences").assertIsDisplayed()
        compose.onNodeWithText("Game directory").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals("C:/Étoile {0}", settings.root)
        org.junit.Assert.assertEquals("en", settings.language)
        capture("settings-english")
    }

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
        compose.onNodeWithText("Journaux", useUnmergedTree = true).performClick()
        compose.onNodeWithText("C:/NRF/logs/hub.log").assertIsDisplayed()
        compose.onNodeWithText("Ouvrir le dossier des journaux").performClick()
        org.junit.Assert.assertTrue(opened)
        capture("persistent-journal")
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithText("Réparer le chargeur SDK").performScrollTo().performClick()
        org.junit.Assert.assertTrue(repaired)
        capture("sdk-repair")
    }

    @Test fun playShowsCandidateSdkRequirementsAndNavigatesToSdkWithoutChangingProfile() {
        val hash = "a".repeat(64)
        val mod = Project("signals", "native-mod", "1.1.0", "Signaux", gameSha256 = listOf(hash),
            sdkMin = "0.8.0-alpha.9", sdkMaxExclusive = "0.9.0", loaderApi = 1)
        val sdk = InstalledProject("sdk", "sdk", "0.8.0-alpha.8", "C:/sdk", loaderApi = 1)
        val oldMod = InstalledProject(mod.id, mod.kind, "1.0.0", "C:/mod", sdkMin = "0.7.0", sdkMaxExclusive = "0.8.0")
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game", installed = mapOf("sdk" to sdk, mod.id to oldMod)),
            gameHash = hash, projects = listOf(mod), availableProjects = setOf(mod.id)))
        var profileChanges = 0
        compose.setContent { HubScreen(state, HubActions(profile = { profileChanges++ })) }
        compose.onNodeWithText("Prérequis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pour la version 1.1.0").assertExists()
        compose.onNodeWithText("SDK minimum : 0.8.0-alpha.9 (inclus)").assertExists()
        compose.onNodeWithText("SDK maximum : 0.9.0 (exclu)").assertExists()
        compose.onNodeWithText("SDK incompatible").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mettre à jour").assertIsNotEnabled()
        capture("prerequisites-play-incompatible")
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(installed = mapOf("sdk" to sdk.copy(version = "0.8.0-alpha.10"), mod.id to oldMod))) }
        compose.onNodeWithText("Compatible avec ce SDK").assertExists()
        compose.onNodeWithText("SDK prévu pour le profil Jouer : 0.8.0-alpha.10").assertExists()
        compose.onNodeWithText("Mettre à jour").assertIsEnabled()
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("en") }
        compose.onNodeWithText("Minimum SDK: 0.8.0-alpha.9 (inclusive)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Maximum SDK: 0.9.0 (exclusive)").assertExists()
        compose.onNodeWithText("SDK planned for the Play profile: 0.8.0-alpha.10").assertExists()
        compose.onNodeWithText("Compatible with this SDK").assertExists()
        capture("prerequisites-play-english")
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("fr") }
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(installed = mapOf(mod.id to oldMod))) }
        compose.onNodeWithText("SDK manquant").assertExists()
        compose.onNodeWithText("SDK prévu pour le profil Jouer : Non installé").assertExists()
        compose.onNodeWithText("Mettre à jour").assertIsNotEnabled()
        compose.onNodeWithText("Aller au SDK").performScrollTo().performClick()
        compose.onNodeWithText("SDK du jeu").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, profileChanges)
    }

    @Test fun selectedDevelopmentSdkDoesNotAuthorizeInstallationIntoUsualProfile() {
        val hash = "a".repeat(64)
        val mod = Project("signals", "native-mod", "1.1.0", "Signaux", gameSha256 = listOf(hash),
            sdkMin = "0.8.0-alpha.1", sdkMaxExclusive = "0.9.0", loaderApi = 1)
        val normal = InstalledProject("sdk", "sdk", "0.7.3", "C:/sdk", loaderApi = 1)
        val settings = HubSettings(gameDirectory = "C:/Game", developerMode = true, profile = HubProfile.DEVELOP,
            installed = mapOf("sdk" to normal), development = DevelopmentSettings(sdkVersion = "0.8.0",
                sdkVersions = mapOf("0.8.0" to normal.copy(version = "0.8.0", directory = "C:/test-sdk"))))
        compose.setContent { HubScreen(HubState(settings, projects = listOf(mod), gameHash = hash, availableProjects = setOf(mod.id)), HubActions()) }
        compose.onNodeWithText("Compatible avec ce SDK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SDK prévu pour le profil Développer : 0.8.0").assertExists()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.onNodeWithText("Installation habituelle : SDK 0.8.0-alpha.1 requis").performScrollTo().assertIsDisplayed()
        capture("prerequisites-development-distinct")
    }

    @Test fun sdkPageKeepsMalformedImportedRequirementsDisabledInsteadOfCrashing() {
        val hash = "a".repeat(64)
        val sdk = Project("sdk", "sdk", "0.8.0", gameSha256 = listOf(hash), loaderApi = 1)
        val malformed = InstalledProject("signals", "native-mod", "1.0.0", "C:/mod", sdkMin = "0.8.0-alpha.1")
        compose.setContent { HubScreen(HubState(HubSettings(gameDirectory = "C:/Game", installed = mapOf(malformed.id to malformed)),
            projects = listOf(sdk), gameHash = hash, availableProjects = setOf(sdk.id)), HubActions()) }
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
        compose.onNodeWithText("Installation habituelle : Prérequis de l’installation non vérifiables").performScrollTo().assertIsDisplayed()
    }

    @Test fun channelButtonsIdentifyTheirProjectAndUseStableStorageKeysInBothLanguages() {
        val mod = Project("signals", "native-mod", "1.0.0", "Signaux")
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game", channels = mapOf(mod.id to "alpha", "hub" to "beta")),
            projects = listOf(mod), gameHash = "a".repeat(64), availableProjects = setOf(mod.id)))
        val changes = mutableListOf<Pair<String, String>>()
        var installations = 0
        compose.setContent { HubScreen(state, HubActions(install = { installations++ }, channel = { project, channel ->
            changes += project to channel
            state = state.copy(settings = state.settings.copy(channels = state.settings.channels + (project to channel)))
        })) }
        compose.onNodeWithContentDescription("Modifier le canal de Signaux").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stable").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Bêta").performClick()
        compose.onNodeWithText("Canal : Bêta").assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf("signals" to "beta"), changes)
        capture("project-channel-button")
        compose.onNodeWithText("Paramètres").performClick()
        compose.onNodeWithContentDescription("Modifier le canal de NRF Hub").performScrollTo().performClick()
        compose.onNodeWithText("Alpha").performClick()
        org.junit.Assert.assertEquals("hub" to "alpha", changes.last())
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("en") }
        compose.onNodeWithContentDescription("Change the channel for NRF Hub").performScrollTo().performClick()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithText("Channel: Beta").assertIsDisplayed()
        org.junit.Assert.assertEquals("hub" to "beta", changes.last())
        capture("hub-channel-button-english")
        compose.onNodeWithContentDescription("Change the channel for NRF Hub").performClick()
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithContentDescription("Change the channel for NRF Hub").assertIsNotEnabled()
        org.junit.Assert.assertEquals(0, installations)
    }

    @Test fun logsPageRetainsExportAndOperationsWithAnEmptySessionAndEnglishLabels() {
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game"), gameHash = "a".repeat(64), logFile = "C:/NRF/logs/hub.log"))
        var opened = 0
        var exported = 0
        compose.setContent { HubScreen(state, HubActions(openLogs = { opened++ }, exportLogs = { exported++ })) }
        compose.onNodeWithText("Journaux", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Aucun événement pour cette session").assertIsDisplayed()
        compose.onNodeWithText("Aucune opération en cours").assertIsDisplayed()
        compose.onNodeWithText("Ouvrir le dossier des journaux").performClick()
        compose.onNodeWithText("Exporter les logs NRF").performClick()
        org.junit.Assert.assertEquals(1, opened)
        org.junit.Assert.assertEquals(1, exported)
        capture("logs-empty-session")
        compose.runOnIdle { state = state.copy(log = listOf("Signal package verified", "SDK ready"), busy = true) }
        compose.onNodeWithText("Aucun événement pour cette session").assertDoesNotExist()
        compose.onNodeWithText("SDK ready").assertIsDisplayed()
        compose.onNodeWithText("Opération en cours").assertIsDisplayed()
        compose.onNodeWithText("Exporter les logs NRF").assertIsNotEnabled()
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("en"); state = state.copy(busy = false, log = emptyList()) }
        compose.onAllNodesWithText("Logs", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("No events in this session").assertIsDisplayed()
        capture("logs-english")
    }

    @Test fun unverifiedCatalogueNeverPresentsInstalledRequirementsAsCandidateRequirements() {
        val mod = Project("signals", "native-mod", "1.0.0", "Signaux", sdkMin = "0.7.0", sdkMaxExclusive = "0.8.0")
        compose.setContent { HubScreen(HubState(HubSettings(gameDirectory = "C:/Game"), projects = listOf(mod), gameHash = "a".repeat(64)), HubActions()) }
        compose.onNodeWithText("Actualisez le catalogue pour vérifier les prérequis de la version disponible.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SDK minimum : 0.7.0 (inclus)").assertDoesNotExist()
        compose.onNodeWithText("Installer").assertIsNotEnabled()
    }

    @Test fun createProjectIsAvailableOnlyInDevelopModsAndCallsItsAction() {
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game"), gameHash = "a".repeat(64)))
        var created = 0
        compose.setContent { HubScreen(state, HubActions(createProject = { created++ })) }
        compose.onNodeWithText("Créer un projet").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(developerMode = true, profile = HubProfile.DEVELOP)) }
        compose.onNodeWithText("Créer un projet").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, created)
        compose.onNodeWithText("Ajouter un projet local").assertIsDisplayed()
        capture("create-project-develop")
        compose.onNodeWithText("Utilitaires", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Créer un projet").assertDoesNotExist()
        compose.onNodeWithText("Mods", useUnmergedTree = true).performClick()
        compose.runOnIdle { fr.nimby.hub.i18n.I18n.choose("en"); state = state.copy(busy = true) }
        compose.onNodeWithText("Create a project").assertIsNotEnabled()
    }

    private fun fixture() {
        compose.setContent {
            val mod = Project("signalisationfrancaiserealiste", "native-mod", "0.2.0", name = "AB Signalisation lumineuse")
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
