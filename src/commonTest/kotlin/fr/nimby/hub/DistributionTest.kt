package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlin.test.*

class DistributionTest {
    @Test fun renamedRepositoriesPreserveDistributionIdsAndAssetIdentity() {
        val renamed = mapOf("signalisationfrancaiserealiste" to "ab-signalisation-lumineuse",
            "signal-placement" to "ba-signal-placement", "time-change" to "bb-timechange")
        for ((id, repository) in renamed) {
            val legacy = "https://github.com/NimbyRails-France/$id/releases/download/v1.0.0/mod.zip"
            val canonical = "https://github.com/NimbyRails-France/$repository/releases/download/v1.0.0/mod.zip"
            val server = "${DistributionLocation.origin}/releases/$id/v1.0.0/mod.zip"
            assertEquals(repository, DistributionLocation.githubRepository(id))
            assertEquals(id, DistributionLocation.projectForRepository(repository))
            assertTrue(id in DistributionLocation.projects)
            assertFalse(repository in DistributionLocation.projects)
            assertEquals(canonical, DistributionLocation.githubMirror(legacy))
            assertEquals(canonical, DistributionLocation.githubMirror(server))
            assertEquals(server, DistributionLocation.serverMirror(canonical))
            assertEquals(server, DistributionLocation.serverMirror(legacy))
            assertEquals("https://github.com/NimbyRails-France/$repository/releases/tag/v1.0.0",
                DistributionLocation.githubPage(id, "1.0.0"))
            assertTrue(DistributionLocation.sameGithubAsset(legacy, canonical))
            assertTrue(ReleaseSelection.officialAsset(canonical, id, "v1.0.0"))
            assertTrue(ReleaseSelection.officialAsset(legacy, id, "v1.0.0"))
            assertFalse(ReleaseSelection.officialAsset(canonical, "sdk", "v1.0.0"))
            assertFalse(ReleaseSelection.officialAsset(canonical, id, "v1.0.1"))
            for (bad in listOf(canonical.replace(repository, "sdk"), canonical.replace("v1.0.0", "v1.0.1"),
                canonical.replace("mod.zip", "other.zip"), canonical + "?redirect=1", canonical + "#fragment",
                canonical.replace("mod.zip", "%6dod.zip"), canonical.replace("NimbyRails-France", "OtherOrg"))) {
                assertFalse(DistributionLocation.sameGithubAsset(legacy, bad), bad)
            }
            val oldCatalogue = "https://github.com/NimbyRails-France/$id/releases/download/catalogue/releases.json"
            assertTrue(DistributionLocation.sameGithubCatalogue(oldCatalogue, DistributionLocation.githubCatalogue(id)))
            assertFalse(DistributionLocation.sameGithubCatalogue(oldCatalogue, DistributionLocation.githubCatalogue("sdk")))
        }
        assertEquals("future-mod", DistributionLocation.githubRepository("future-mod"))
    }

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
