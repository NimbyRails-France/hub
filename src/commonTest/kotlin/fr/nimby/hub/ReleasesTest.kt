package fr.nimby.hub

import fr.nimby.hub.model.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ReleasesTest {
    private fun release(version: String, prerelease: Boolean = false): GitHubRelease {
        val base = "https://github.com/NimbyRails-France/sdk/releases/download/v$version/"
        return GitHubRelease("v$version", prerelease = prerelease, publishedAt = "2026-09-18T10:00:00Z", assets = listOf(
            ReleaseAsset("project.json", "uploaded", base + "project.json"),
            ReleaseAsset("sdk.zip", "uploaded", base + "sdk.zip", 1234, "sha256:" + "a".repeat(64)),
        ))
    }

    @Test fun semanticOrderingAndStrictVersionSyntax() {
        assertTrue(Versions.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(Versions.compare("1.0.0-beta.1", "1.0.0-alpha.99") > 0)
        assertTrue(Versions.compare("1.0.0", "1.0.0-beta.99") > 0)
        assertTrue(Versions.compare("1.1.0-alpha.1", "1.0.9") > 0)
        listOf("01.0.0", "1.0.0-beta.01", "1.0.0-rc.1", "1.0.0-alpha.0").forEach { assertFalse(Versions.valid(it)) }
    }

    @Test fun selectionKeepsPrereleaseChannelsSeparateAndIncludesStable() {
        val releases = listOf(release("1.0.0"), release("2.0.0-beta.2", true), release("2.0.0-alpha.1", true), release("2.0.0-beta.10", true),
            release("9.0.0").copy(draft = true), release("8.0.0", true), release("7.0.0").copy(assets = emptyList()))
        assertEquals("1.0.0", ReleaseSelection.select(releases, "stable", "project.json")?.version)
        assertEquals("2.0.0-beta.10", ReleaseSelection.select(releases, "beta", "project.json")?.version)
        assertEquals("2.0.0-alpha.1", ReleaseSelection.select(releases, "alpha", "project.json")?.version)
        assertEquals("1.0.0", ReleaseSelection.select(listOf(release("1.0.0")), "beta", "project.json")?.version)
        assertNull(ReleaseSelection.select(releases, "dev", "project.json"))
    }

    @Test fun selectedAssetMustMatchRepositoryTagSizeAndDigest() {
        val release = release("1.0.0")
        val url = release.assets[1].url
        fun validate(repo: String = "sdk", version: String = "1.0.0", channel: String? = null, size: Long = 1234, hash: String = "a".repeat(64)) =
            ReleaseSelection.validateAsset(release, repo, version, channel, url, size, hash)
        validate()
        assertFails { validate(repo = "tco") }
        assertFails { validate(version = "1.0.1") }
        assertFails { validate(channel = "beta") }
        assertFails { validate(size = 1235) }
        assertFails { validate(hash = "b".repeat(64)) }
        val bad = release.copy(assets = listOf(release.assets[0].copy(url = "https://evil.example/project.json")))
        assertFails { ReleaseSelection.assetUrl(bad, "sdk", "project.json") }
        assertFalse(ReleaseSelection.officialAsset(url + "?redirect=evil", "sdk", "v1.0.0"))
        assertFalse(ReleaseSelection.officialAsset(url.replace("sdk.zip", "%2E%2E/other.zip"), "sdk", "v1.0.0"))
    }

    @Test fun stablePromotesAlphaAndBetaOnlyWhenItsVersionIsHigher() {
        val previews = listOf(release("2.0.0-alpha.9", true), release("2.0.0-beta.3", true))
        for (channel in listOf("alpha", "beta")) {
            assertEquals("2.0.0", ReleaseSelection.select(previews + release("2.0.0"), channel, "project.json")?.version)
            assertEquals("2.1.0", ReleaseSelection.select(previews + release("2.1.0"), channel, "project.json")?.version)
            val expected = if (channel == "alpha") "2.0.0-alpha.9" else "2.0.0-beta.3"
            assertEquals(expected, ReleaseSelection.select(previews + release("1.9.9"), channel, "project.json")?.version)
            val next = "2.1.0-$channel.1"
            assertEquals(next, ReleaseSelection.select(previews + release("2.0.0") + release(next, true), channel, "project.json")?.version)
            assertNull(ReleaseSelection.select(listOf(release("3.0.0", true)), channel, "project.json"))
        }
        assertNull(ReleaseSelection.select(previews, "stable", "project.json"))
    }

    @Test fun selectsVersionWithinBothPlatformAndChannel() {
        fun platformRelease(version: String, platform: String) = release(version, Versions.channel(version) != "stable").let { release ->
            release.copy(assets = release.assets.map { if (it.name == "project.json") it.copy(
                name = "project-$platform.json", url = it.url.replace("project.json", "project-$platform.json")) else it })
        }
        val releases = listOf(release("1.0.0"), platformRelease("1.1.0", "windows-x64"),
            platformRelease("1.2.0", "linux-x64"), platformRelease("2.0.0-beta.1", "linux-x64"),
            platformRelease("9.0.0", "linux-arm64"))
        fun pick(channel: String, platform: String) = ReleaseSelection.selectForPlatform(releases, channel, "sdk", platform)
        assertEquals("1.1.0", pick("stable", "windows-x64")?.release?.version)
        assertEquals("1.2.0", pick("stable", "linux-x64")?.release?.version)
        assertEquals("2.0.0-beta.1", pick("beta", "linux-x64")?.release?.version)
        assertEquals("1.2.0", pick("alpha", "linux-x64")?.release?.version)
        assertNull(pick("stable", "macos-arm64"))
        assertEquals("project.json", ReleaseSelection.selectForPlatform(listOf(release("1.0.0")), "stable", "sdk", "windows-x64")?.manifest)
        assertNull(ReleaseSelection.selectForPlatform(listOf(release("1.0.0")), "stable", "sdk", "linux-x64"))
        val promoted = releases + platformRelease("2.0.0", "linux-x64") + platformRelease("3.0.0", "windows-x64")
        assertEquals("2.0.0", ReleaseSelection.selectForPlatform(promoted, "beta", "sdk", "linux-x64")?.release?.version)
    }

    @Test fun profileKeepsIndependentChannelPreferences() {
        val before = HubSettings(channels = mapOf("sdk" to "beta", "hub" to "alpha", "invalid" to "dev"))
        val after = hubJson.decodeFromString<HubSettings>(hubJson.encodeToString(before))
        assertEquals("beta", after.selectedChannel("sdk"))
        assertEquals("alpha", after.selectedChannel("hub"))
        assertEquals("stable", after.selectedChannel("tco"))
        assertEquals("stable", after.selectedChannel("invalid"))
    }
}
