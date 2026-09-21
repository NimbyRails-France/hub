package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class PolicyTest {
    private val project = Project("fixture", "native-mod", "1.2.3", url = "https://github.com/NimbyRails-France/fixture/releases/download/v1.2.3/fixture.zip",
        size = 100, sha256 = "a".repeat(64), rootFolder = "Fixture", gameSha256 = listOf("b".repeat(64)), modId = "Fixture", loaderApi = 1, module = "Fixture.dll")

    @Test fun catalogueValidation() {
        ProjectRules.validate(project)
        listOf(project.copy(id = "../bad"), project.copy(url = "https://github.com.evil.test/NimbyRails-France/x"),
            project.copy(url = "http://github.com/NimbyRails-France/x"), project.copy(size = -1),
            project.copy(module = "../outside.dll"), project.copy(loaderApi = 2), project.copy(rootFolder = "../x"),
            project.copy(sdkMin = "0.7.0"), project.copy(gameSha256 = emptyList())).forEach { bad ->
            assertFails { ProjectRules.validate(bad) }
        }
    }
    @Test fun developerModeKeepsCatalogueButProtectsAgainstAutomaticInstallation() {
        val policy = UpdatePolicy()
        val before = policy.generation
        assertTrue(policy.accepts(before))
        policy.change(developerMode = true)
        assertTrue(policy.canSynchronize)
        assertFalse(policy.canAutoInstall)
        assertFalse(policy.accepts(before))
        policy.change(developerMode = false)
        assertTrue(policy.canSynchronize)
        assertFalse(policy.accepts(before), "A stale download must remain invalid after an on/off cycle")
        policy.change(automatic = false)
        assertTrue(policy.canSynchronize)
        assertFalse(policy.canAutoInstall)
    }
    @Test fun nativeLibraryMustMatchPackagePlatform() {
        ProjectRules.validate(project.copy(platform = "linux-x64", module = "Fixture.so"))
        ProjectRules.validate(project.copy(platform = "macos-arm64", module = "Fixture.dylib"))
        assertFails { ProjectRules.validate(project.copy(platform = "linux-x64")) }
        assertFails { ProjectRules.validate(project.copy(module = "Fixture.so")) }
        assertFails { ProjectRules.validate(project.copy(platform = "macos-arm64", module = "Fixture.so")) }
    }
    @Test fun localModAndAlternateSdkOnlyReplaceDevelopmentProfile() {
        val sdk = InstalledProject("sdk", "sdk", "0.7.2", "C:/normal/sdk", gameSha256 = listOf("b".repeat(64)), loaderApi = 1)
        val testSdk = sdk.copy(version = "0.7.3", directory = "C:/test/sdk")
        val published = InstalledProject("fixture", "native-mod", "1.0.0", "C:/normal/mod", modId = "fixture")
        val local = published.copy(version = "1.1.0", directory = "C:/test/mod")
        val s = HubSettings(developerMode = true, installed = mapOf("sdk" to sdk, "fixture" to published),
            development = DevelopmentSettings(origins = mapOf("fixture" to ModOrigin.LOCAL), prepared = mapOf("fixture" to local),
                builds = mapOf("fixture" to BuildResult(ready = true)), sdkVersion = "0.7.3", sdkVersions = mapOf("0.7.3" to testSdk)))
        assertEquals(s.installed, ProfileRules.resolve(s))
        val development = ProfileRules.resolve(s.copy(profile = HubProfile.DEVELOP))
        assertEquals(testSdk, development["sdk"])
        assertEquals(local, development["fixture"])
        assertEquals(sdk, s.installed["sdk"])
        assertEquals(published, s.installed["fixture"])
        assertFailsWith<IllegalArgumentException> { ProfileRules.resolve(s.copy(profile = HubProfile.DEVELOP,
            development = s.development.copy(builds = mapOf("fixture" to BuildResult("Échec"))))) }
    }
    @Test fun conflictingModIdentitiesAndIncompatibleSdkBlockWholeProfile() {
        val sdk = InstalledProject("sdk", "sdk", "0.7.3", "C:/sdk", gameSha256 = listOf("b".repeat(64)), loaderApi = 1)
        val mod = InstalledProject("fixture", "native-mod", "1.0.0", "C:/mod", modId = "Fixture", gameSha256 = sdk.gameSha256,
            sdkMin = "0.7.3", sdkMaxExclusive = "0.8.0", loaderApi = 1, module = "Mod.dll")
        ProfileRules.validate(mapOf("sdk" to sdk, "fixture" to mod), "b".repeat(64))
        assertFails { ProfileRules.validate(mapOf("sdk" to sdk.copy(version = "0.7.2"), "fixture" to mod), "b".repeat(64)) }
        assertFails { ProfileRules.validate(mapOf("sdk" to sdk, "fixture" to mod, "other" to mod.copy(id = "other", modId = "fixture")), "b".repeat(64)) }
    }
    @Test fun olderSettingsRemainReadableAndDeveloperModePersists() {
        val old = """{"gameDirectory":"C:/game","root":"C:/mods","automatic":false,"installed":{},"notified":{},"geometry":"QtGeometry"}"""
        val settings = hubJson.decodeFromString<HubSettings>(old)
        assertFalse(settings.developerMode)
        assertFalse(settings.automatic)
        assertEquals("QtGeometry", settings.geometry)
        val saved = hubJson.encodeToString(settings.copy(developerMode = true))
        assertTrue(hubJson.decodeFromString<HubSettings>(saved).developerMode)
    }
    @Test fun sdkCompatibilityAndNumericVersions() {
        assertTrue(Versions.compare("0.10.0", "0.9.9") > 0)
        val sdk = InstalledProject("sdk", "sdk", "0.7.3", "C:/sdk")
        val p = project.copy(sdkMin = "0.7.2", sdkMaxExclusive = "0.8.0")
        assertNull(ProjectRules.incompatibility(p, "b".repeat(64), mapOf("sdk" to sdk)))
        assertNotNull(ProjectRules.incompatibility(p, "b".repeat(64), mapOf("sdk" to sdk.copy(version = "0.8.0"))))
        assertNotNull(ProjectRules.incompatibility(p, "c".repeat(64), mapOf("sdk" to sdk)))
    }
}
