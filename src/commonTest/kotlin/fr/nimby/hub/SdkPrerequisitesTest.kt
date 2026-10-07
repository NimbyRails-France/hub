package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlin.test.*

class SdkPrerequisitesTest {
    private val candidate = Project("signals", "native-mod", "1.1.0", sdkMin = "0.8.0-alpha.9", sdkMaxExclusive = "0.9.0", loaderApi = 1)
    private fun sdk(version: String, loaderApi: Int? = 1) = InstalledProject("sdk", "sdk", version, "C:/sdk", loaderApi = loaderApi)
    private fun settings(version: String) = HubSettings(installed = mapOf("sdk" to sdk(version)))
    private fun status(version: String) = SdkPrerequisites.forProject(candidate, settings(version)).status

    @Test fun evaluatesCandidateRequirementsInsteadOfInstalledModRequirements() {
        val oldMod = InstalledProject(candidate.id, candidate.kind, "1.0.0", "C:/mod", sdkMin = "0.7.0", sdkMaxExclusive = "0.8.0")
        val result = SdkPrerequisites.forProject(candidate, settings("0.7.3").copy(installed = mapOf("sdk" to sdk("0.7.3"), candidate.id to oldMod)))
        assertEquals("0.8.0-alpha.9", result.minimum)
        assertEquals("0.9.0", result.maximumExclusive)
        assertEquals("0.7.3", result.selectedVersion)
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, result.status)
    }

    @Test fun minimumIsInclusiveAndMaximumExclusiveWithNumericPrereleaseOrdering() {
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, status("0.8.0-alpha.8"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, status("0.8.0-alpha.9"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, status("0.8.0-alpha.10"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, status("0.8.0-beta.1"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, status("0.8.0"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, status("0.9.0-alpha.1"))
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, status("0.9.0"))
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, status("0.10.0"))
    }

    @Test fun missingSdkAndUndeclaredRequirementsAreDifferent() {
        assertEquals(SdkPrerequisiteStatus.MISSING, SdkPrerequisites.forProject(candidate, HubSettings()).status)
        val noRequirement = candidate.copy(sdkMin = null, sdkMaxExclusive = null, loaderApi = null)
        assertEquals(SdkPrerequisiteStatus.NOT_DECLARED, SdkPrerequisites.forProject(noRequirement, HubSettings()).status)
        assertEquals(SdkPrerequisiteStatus.NOT_DECLARED, SdkPrerequisites.forProject(noRequirement, settings("0.7.0")).status)
    }

    @Test fun loaderOnlyRequirementsKeepTheLegacyLoaderVersionFallback() {
        val loaderOnly = candidate.copy(sdkMin = null, sdkMaxExclusive = null)
        fun check(version: String, api: Int? = null) = SdkPrerequisites.forProject(loaderOnly, HubSettings(installed = mapOf("sdk" to sdk(version, api)))).status
        assertEquals(SdkPrerequisiteStatus.MISSING, SdkPrerequisites.forProject(loaderOnly, HubSettings()).status)
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, check("0.7.1"))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, check("0.7.2"))
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, check("0.8.0", 0))
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, check("0.7.1", 1))
    }

    @Test fun playAndDevelopmentUseTheirOwnSelectedSdkNotTheCurrentlyActiveOne() {
        val alternate = sdk("0.8.0-alpha.10")
        val base = settings("0.7.3").copy(developerMode = true, appliedProfile = HubProfile.DEVELOP,
            activeDevelopment = mapOf("sdk" to sdk("0.9.0")),
            development = DevelopmentSettings(sdkVersion = alternate.version, sdkVersions = mapOf(alternate.version to alternate)))
        val play = SdkPrerequisites.forProject(candidate, base)
        assertEquals("0.7.3", play.selectedVersion)
        assertEquals(SdkPrerequisiteStatus.INCOMPATIBLE, play.status)
        val develop = SdkPrerequisites.forProject(candidate, base.copy(profile = HubProfile.DEVELOP))
        assertEquals(alternate.version, develop.selectedVersion)
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, develop.status)
        assertEquals("0.7.3", base.installed["sdk"]?.version, "Presentation never replaces the usual installation")
    }

    @Test fun missingSelectedAlternateDoesNotFallBackToUsualSdk() {
        val s = settings("0.8.0").copy(developerMode = true, profile = HubProfile.DEVELOP,
            development = DevelopmentSettings(sdkVersion = "0.8.1"))
        val result = SdkPrerequisites.forProject(candidate, s)
        assertNull(result.selectedVersion)
        assertEquals(SdkPrerequisiteStatus.MISSING, result.status)
    }

    @Test fun localSdkTakesPriorityButMustBePreparedAndOtherLocalModsDoNotAffectIt() {
        val s = settings("0.7.3").copy(developerMode = true, profile = HubProfile.DEVELOP,
            development = DevelopmentSettings(origins = mapOf("sdk" to ModOrigin.LOCAL, "other" to ModOrigin.LOCAL),
                sdkVersion = "0.9.0", sdkVersions = mapOf("0.9.0" to sdk("0.9.0")),
                prepared = mapOf("sdk" to sdk("0.8.0")), builds = mapOf("sdk" to BuildResult(ready = true))))
        assertEquals("0.8.0", SdkPrerequisites.forProject(candidate, s).selectedVersion)
        assertEquals(SdkPrerequisiteStatus.COMPATIBLE, SdkPrerequisites.forProject(candidate, s).status)
        assertEquals(SdkPrerequisiteStatus.NOT_READY, SdkPrerequisites.forProject(candidate,
            s.copy(development = s.development.copy(builds = emptyMap()))).status)
        assertEquals(SdkPrerequisiteStatus.MISSING, SdkPrerequisites.forProject(candidate,
            s.copy(development = s.development.copy(prepared = emptyMap()))).status)
    }

    @Test fun malformedImportedRequirementsAndVersionsAreUnverifiable() {
        listOf(candidate.copy(sdkMin = null), candidate.copy(sdkMaxExclusive = null),
            candidate.copy(sdkMin = "tomorrow"), candidate.copy(sdkMaxExclusive = "latest"),
            candidate.copy(sdkMin = "0.9.0"), candidate.copy(sdkMin = "1.0.0")).forEach { invalid ->
            assertEquals(SdkPrerequisiteStatus.UNVERIFIABLE, SdkPrerequisites.forProject(invalid, settings("0.8.0")).status)
        }
        assertEquals(SdkPrerequisiteStatus.UNVERIFIABLE, status("local-build"))
    }
}
