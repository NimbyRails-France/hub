package fr.nimby.hub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.nimby.hub.i18n.I18n
import fr.nimby.hub.model.*
import fr.nimby.hub.ui.HubActions
import fr.nimby.hub.ui.HubScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HubStatusScreenTest {
    @get:Rule val compose = createComposeRule()
    @org.junit.Before fun french() { I18n.configure("fr", "fr") }
    @org.junit.After fun resetLanguage() { I18n.configure("auto", "fr") }

    private val gameHash = "a".repeat(64)

    private fun project(version: String, id: String = "signals", kind: String = "native-mod") =
        Project(id, kind, version, name = "Projet $id", gameSha256 = listOf(gameHash))

    private fun installed(project: Project, version: String = project.version) =
        InstalledProject(project.id, project.kind, version, "C:/Installed/${project.id}", project.name,
            gameSha256 = listOf(gameHash))

    private fun state(project: Project, installed: InstalledProject? = null) = HubState(
        HubSettings(gameDirectory = "C:/Game", installed = installed?.let { mapOf(it.id to it) }.orEmpty()),
        projects = listOf(project), gameHash = gameHash, availableProjects = setOf(project.id))

    @Test fun successfulFirstInstallationImmediatelyRemovesTheInstallAction() {
        val candidate = project("1.0.0")
        var state by mutableStateOf(state(candidate))
        val requests = mutableListOf<Project>()
        compose.setContent { HubScreen(state, HubActions(install = { requests += it })) }
        compose.onNodeWithText("Installer").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(candidate), requests)
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(installed = mapOf(candidate.id to installed(candidate))))
        }
        compose.onNodeWithText("À jour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Installer").assertDoesNotExist()
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("Installer cette version").assertDoesNotExist()
        assertEquals(1, requests.size)
    }

    @Test fun numericPrereleaseUpdateBecomesUpToDateInBothLanguages() {
        val candidate = project("0.1.0-alpha.10")
        var state by mutableStateOf(state(candidate, installed(candidate, "0.1.0-alpha.9")))
        var requested: Project? = null
        compose.setContent { HubScreen(state, HubActions(install = { requested = it })) }
        compose.onNodeWithText("Mettre à jour").performScrollTo().assertIsEnabled().performClick()
        assertEquals(candidate, requested)
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(installed = mapOf(candidate.id to installed(candidate))))
        }
        compose.onNodeWithText("À jour").assertExists()
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.runOnIdle { I18n.choose("en") }
        compose.onNodeWithText("Up to date").assertExists()
        compose.onNodeWithText("À jour").assertDoesNotExist()
        compose.onNodeWithText("Update").assertDoesNotExist()
    }

    @Test fun alreadyInstalledTcoDoesNotOfferAnUpdate() {
        val candidate = project("0.6.0-alpha.3", "tco", "tco")
        var launches = 0
        compose.setContent { HubScreen(state(candidate, installed(candidate)), HubActions(launchTool = { launches++ })) }
        compose.onNodeWithText("Utilitaires", useUnmergedTree = true).performClick()
        compose.onNodeWithText("À jour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("Lancer l’utilitaire").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, launches)
    }

    @Test fun changingToStableKeepsAnOlderCandidateExplicitlyInstallable() {
        val alpha = project("1.1.0-alpha.1")
        val stable = alpha.copy(version = "1.0.0", channel = "stable")
        var state by mutableStateOf(state(alpha, installed(alpha)).let {
            it.copy(settings = it.settings.copy(channels = mapOf(alpha.id to "alpha")))
        })
        val changes = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<Project>()
        compose.setContent { HubScreen(state, HubActions(install = { requests += it }, channel = { id, channel ->
            changes += id to channel
            // A channel change invalidates the old candidate until the catalogue responds.
            state = state.copy(projects = emptyList(), availableProjects = emptySet(),
                settings = state.settings.copy(channels = state.settings.channels + (id to channel)))
        })) }
        compose.onNodeWithContentDescription("Modifier le canal de ${alpha.name}").performScrollTo().performClick()
        compose.onNodeWithText("Stable").performClick()
        assertEquals(listOf(alpha.id to "stable"), changes)
        compose.onNodeWithText("À jour").assertDoesNotExist()
        compose.onNodeWithText("Installer cette version").assertIsNotEnabled()
        assertEquals(emptyList<Project>(), requests)
        compose.runOnIdle { state = state.copy(projects = listOf(stable), availableProjects = setOf(stable.id)) }
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("Installer cette version").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(stable), requests)
    }

    @Test fun missingCatalogueAndMalformedVersionsNeverClaimUpToDate() {
        val candidate = project("1.0.0")
        var state by mutableStateOf(state(candidate, installed(candidate)).copy(projects = emptyList(), availableProjects = emptySet()))
        compose.setContent { HubScreen(state, HubActions()) }
        compose.onNodeWithText("Non vérifiée").assertExists()
        compose.onNodeWithText("À jour").assertDoesNotExist()
        compose.onNodeWithText("Installer cette version").assertIsNotEnabled()
        // An old imported version must not crash rendering or be mistaken for equality.
        compose.runOnIdle {
            state = state.copy(projects = listOf(candidate), availableProjects = setOf(candidate.id),
                settings = state.settings.copy(installed = mapOf(candidate.id to installed(candidate, "legacy-build"))))
        }
        compose.onNodeWithText("Installer cette version").assertExists()
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("À jour").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(projects = listOf(candidate.copy(version = "legacy-build"))) }
        compose.onNodeWithText("Installer cette version").assertExists()
        compose.onNodeWithText("À jour").assertDoesNotExist()
    }

    @Test fun equalSdkCanBeReinstalledWithoutBypassingInstallationGuards() {
        val sdk = project("0.9.0-alpha.1", "sdk", "sdk")
        val ready = state(sdk, installed(sdk))
        var state by mutableStateOf(ready)
        val requests = mutableListOf<Project>()
        compose.setContent { HubScreen(state, HubActions(install = { requests += it })) }
        compose.onNodeWithText("SDK", useUnmergedTree = true).performClick()
        compose.onNodeWithText("À jour").assertExists()
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("Réinstaller le SDK").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(sdk), requests)
        val blocked = listOf(
            ready.copy(busy = true), ready.copy(windows = false), ready.copy(checkingGame = true),
            ready.copy(gameHash = "b".repeat(64)),
            ready.copy(settings = ready.settings.copy(appliedProfile = HubProfile.DEVELOP)),
            ready.copy(settings = ready.settings.copy(legacyProtection = true)),
        )
        for (snapshot in blocked) {
            compose.runOnIdle { state = snapshot }
            compose.onNodeWithText("Réinstaller le SDK").assertIsNotEnabled()
        }
        compose.runOnIdle { state = ready; I18n.choose("en") }
        compose.onNodeWithText("Reinstall SDK").assertIsEnabled()
        compose.onNodeWithText("Up to date").assertExists()
        assertEquals(1, requests.size)
        compose.runOnIdle { state = ready.copy(availableProjects = emptySet()) }
        compose.onNodeWithText("Reinstall SDK").assertDoesNotExist()
        compose.onNodeWithText("Up to date").assertDoesNotExist()
        compose.onNodeWithText("Install this version").assertIsNotEnabled()
    }

    @Test fun pendingProfileMessageTracksGameClosureAndActualApplication() {
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game", developerMode = true,
            profile = HubProfile.DEVELOP, appliedProfile = HubProfile.PLAY), gameHash = gameHash, gameRunning = true))
        var applications = 0
        compose.setContent { HubScreen(state, HubActions(applyProfile = { applications++ })) }
        val waitingForClose = "Développer en attente · fermeture du jeu nécessaire avant la bascule"
        val readyToApply = "Développer en attente · appliquez le profil pour l’activer"
        compose.onNodeWithText(waitingForClose).assertIsDisplayed()
        compose.onNodeWithText("Appliquer").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(gameRunning = false) }
        compose.onNodeWithText("Jeu fermé").assertExists()
        compose.onNodeWithText("Lancer NIMBY Rails").assertExists()
        compose.onNodeWithText(waitingForClose).assertDoesNotExist()
        compose.onNodeWithText(readyToApply).assertIsDisplayed()
        compose.onNodeWithText("Appliquer").assertIsEnabled().performClick()
        assertEquals(1, applications)
        // Clicking is not evidence of successful activation; retain the pending state.
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText(readyToApply).assertExists()
        compose.onNodeWithText("Appliquer").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(busy = false, settings = state.settings.copy(appliedProfile = HubProfile.DEVELOP))
        }
        compose.onNodeWithText(readyToApply).assertDoesNotExist()
        compose.onNodeWithText("Appliquer").assertDoesNotExist()
        compose.onNodeWithText("Actif : Développer").assertExists()
    }

    @Test fun pendingDeveloperModeDisableRemainsVisibleUntilCommittedEvenWhenProfilesMatch() {
        var state by mutableStateOf(HubState(HubSettings(gameDirectory = "C:/Game", developerMode = true,
            profile = HubProfile.PLAY, appliedProfile = HubProfile.PLAY, disableDeveloperAfterApply = true), gameHash = gameHash))
        compose.setContent { HubScreen(state, HubActions()) }
        compose.onNodeWithText("Jouer en attente · appliquez le profil pour l’activer").assertIsDisplayed()
        compose.onNodeWithText("Appliquer").assertIsEnabled()
        compose.runOnIdle { I18n.choose("en") }
        compose.onNodeWithText("Play pending · apply the profile to activate it").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(gameRunning = true) }
        compose.onNodeWithText("Play pending · close the game to switch profiles").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(gameRunning = false,
            settings = state.settings.copy(developerMode = false, disableDeveloperAfterApply = false)) }
        compose.onNodeWithText("Play pending · apply the profile to activate it").assertDoesNotExist()
        compose.onNodeWithText("Play pending · close the game to switch profiles").assertDoesNotExist()
        compose.onNodeWithText("Apply").assertDoesNotExist()
    }
}
