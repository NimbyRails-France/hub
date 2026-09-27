package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlin.test.*

class DistributionTest {
    @Test fun serverAssetsAreBoundToTheirProjectAndVersion() {
        val url = "${DistributionLocation.origin}/releases/sdk/v0.8.0-alpha.1/sdk.zip"
        assertTrue(DistributionLocation.official(url))
        assertTrue(ReleaseSelection.officialAsset(url, "sdk", "v0.8.0-alpha.1"))
        assertFalse(ReleaseSelection.officialAsset(url, "tco", "v0.8.0-alpha.1"))
        assertFalse(ReleaseSelection.officialAsset(url, "sdk", "v0.8.0-alpha.2"))
        for (bad in listOf(url + "?redirect=1", url + "#fragment", url.replace("sdk.zip", "../sdk.zip"),
            url.replace("sdk.zip", "%2e%2e"), url.replace("https://", "http://"),
            url.replace(".fr/", ".fr.evil.example/"))) assertFalse(DistributionLocation.official(bad))
    }

    @Test fun serverHubManifestStillRequiresDigestAndCorrectVersion() {
        val release = HubRelease(schema = 1, product = "NRFHub", version = "0.4.1-alpha.3", platform = "windows-x64",
            url = "${DistributionLocation.origin}/releases/hub/v0.4.1-alpha.3/NRFHub.exe",
            size = 100, sha256 = "a".repeat(64), channel = "alpha")
        ProjectRules.validate(release)
        assertFails { ProjectRules.validate(release.copy(version = "0.4.1-alpha.4")) }
        assertFails { ProjectRules.validate(release.copy(sha256 = "invalid")) }
    }
}
