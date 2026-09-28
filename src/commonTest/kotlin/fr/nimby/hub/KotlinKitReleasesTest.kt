package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlin.test.*

class KotlinKitReleasesTest {
    private fun release(version: String): GitHubRelease {
        val name = "NimbyRailsFranceSDK-kotlin-$version-windows-x64.zip"
        return GitHubRelease("v$version", prerelease = version.contains('-'), publishedAt = "2026-09-27T12:00:00Z",
            assets = listOf(ReleaseAsset(name, "uploaded", DistributionLocation.page("sdk", version) + name, 42, "sha256:" + "a".repeat(64))))
    }

    @Test fun selectsOnlyPublishedKitsForThePlatformAndChannelInVersionOrder() {
        val releases = listOf(release("0.8.0-alpha.2"), release("0.8.0-alpha.10"), release("0.7.3"),
            release("0.8.0-beta.1"), release("0.9.0").copy(draft = true), release("0.9.1").copy(publishedAt = null))
        assertEquals(listOf("0.8.0-alpha.10", "0.8.0-alpha.2", "0.7.3"), KotlinKitReleases.select(releases, "alpha", "windows-x64").map { it.version })
        assertEquals(listOf("0.7.3"), KotlinKitReleases.select(releases, "stable", "windows-x64").map { it.version })
        assertTrue(KotlinKitReleases.select(releases, "alpha", "linux-x64").isEmpty())
    }

    @Test fun refusesMissingDigestForeignHostAndWrongRelease() {
        val release = release("0.8.0-alpha.2")
        val asset = release.assets.single()
        for (bad in listOf(asset.copy(digest = null), asset.copy(size = 0), asset.copy(digest = "sha256:bad"),
            asset.copy(url = asset.url.replace("releases.nimbyrails-france.fr", "example.org")),
            asset.copy(url = asset.url.replace("/v0.8.0-alpha.2/", "/v0.8.0-alpha.1/")))) {
            assertFails { KotlinKitReleases.select(listOf(release.copy(assets = listOf(bad))), "alpha", "windows-x64") }
        }
    }
}
