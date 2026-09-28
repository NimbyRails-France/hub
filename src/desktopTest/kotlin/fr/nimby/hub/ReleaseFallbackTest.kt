package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseFallbackTest {
    private class Transport : ReleaseTransport {
        val texts = mutableMapOf<String, String>()
        val requested = mutableListOf<String>()
        var cancelled = false
        var downloaded = ""
        override suspend fun text(url: String, limit: Int): String {
            requested += url
            if (cancelled) throw CancellationException("cancelled")
            return texts[url] ?: throw ReleaseHttpException(404)
        }
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) { downloaded = url }
    }
    private val hash = "a".repeat(64)
    private fun release(version: String) = GitHubRelease("v$version", prerelease = version.contains('-'), publishedAt = "2026-09-28T10:00:00Z", assets = listOf(
        ReleaseAsset("project-windows-x64.json", "uploaded", DistributionLocation.githubDownload("sdk", version, "project-windows-x64.json"), 200),
        ReleaseAsset("sdk.zip", "uploaded", DistributionLocation.githubDownload("sdk", version, "sdk.zip"), 42, "sha256:$hash")))
    private fun Transport.catalogue(vararg versions: String) {
        val releases = versions.map(::release)
        texts[DistributionLocation.githubCatalogue("sdk")] = """{"schema":1,"project":"sdk","releases":${hubJson.encodeToString(releases)}}"""
        versions.forEach { version ->
            val project = Project("sdk", "sdk", version, rootFolder = "SDK", platform = "windows-x64",
                url = DistributionLocation.page("sdk", version) + "sdk.zip", size = 42, sha256 = hash, gameSha256 = listOf(hash))
            texts[DistributionLocation.githubDownload("sdk", version, "project-windows-x64.json")] = hubJson.encodeToString(project)
        }
    }

    @Test fun publicCatalogueDetectsNextReleaseWithoutAnyApiRequest() = runTest {
        val transport = Transport().apply { catalogue("0.8.0-alpha.1", "0.7.3") }
        var now = 1L
        val source = GitHubReleases(transport, "windows-x64") { now }
        assertEquals("0.8.0-alpha.1", source.project("sdk", "alpha").version)
        assertEquals("0.7.3", source.project("sdk", "stable").version)
        transport.catalogue("0.8.0-alpha.2", "0.8.0-alpha.1", "0.7.3")
        now += 61_000_000_000
        val next = source.project("sdk", "alpha")
        assertEquals("0.8.0-alpha.2", next.version)
        assertEquals(DistributionLocation.githubDownload("sdk", next.version, "sdk.zip"), next.url)
        assertEquals(hash, next.sha256)
        assertTrue(transport.requested.none { it.startsWith("https://api.github.com") })
    }

    @Test fun oldManifestServerUrlRequiresMatchingGithubBytes() = runTest {
        val transport = Transport().apply { catalogue("0.8.0-alpha.1") }
        val url = DistributionLocation.githubDownload("sdk", "0.8.0-alpha.1", "project-windows-x64.json")
        val original = transport.texts.getValue(url)
        for (broken in listOf(original.replace(hash, "b".repeat(64)), original.replace("sdk.zip", "other.zip"),
            original.replace("releases/sdk/", "releases/tco/"))) {
            transport.texts[url] = broken
            assertFails { GitHubReleases(transport, "windows-x64").project("sdk", "alpha") }
        }
    }

    @Test fun apiBootstrapIsCachedButPublicCatalogueStillRefreshesEachMinute() = runTest {
        val transport = Transport().apply { catalogue("0.8.0-alpha.1") }
        transport.texts.remove(DistributionLocation.githubCatalogue("sdk"))
        val api = "https://api.github.com/repos/NimbyRails-France/sdk/releases?per_page=100&page=1"
        transport.texts[api] = hubJson.encodeToString(listOf(release("0.8.0-alpha.1")))
        var now = 1L
        val source = GitHubReleases(transport, "windows-x64") { now }
        source.project("sdk", "alpha")
        now += 61_000_000_000; source.project("sdk", "alpha")
        assertEquals(1, transport.requested.count { it == api })
        transport.catalogue("0.8.0-alpha.2")
        now += 61_000_000_000
        assertEquals("0.8.0-alpha.2", source.project("sdk", "alpha").version)
        assertEquals(1, transport.requested.count { it == api })
    }

    @Test fun rateLimitPausesApiRequestsButNeverStopsPublicCatalogueChecks() = runTest {
        var apiCalls = 0; var publicCalls = 0
        val transport = object : ReleaseTransport {
            override suspend fun text(url: String, limit: Int): String {
                if (url.startsWith("https://api.github.com")) { apiCalls++; throw ReleaseHttpException(429) }
                publicCalls++; throw ReleaseHttpException(404)
            }
            override suspend fun download(url: String, size: Long, hash: String, destination: Path) = error("unused")
        }
        var now = 1L
        val source = GitHubReleases(transport, "windows-x64") { now }
        assertFails { source.project("sdk", "alpha") }
        now += 61_000_000_000
        assertFails { source.project("sdk", "alpha") }
        assertEquals(1, apiCalls); assertEquals(2, publicCalls)
        now += 3_600_000_000_000
        assertFails { source.project("sdk", "alpha") }
        assertEquals(2, apiCalls)
    }

    @Test fun sdkKitsAndHubInstallerAlsoUseGithubWhenTheirManifestPointsToNrf() = runTest {
        val transport = Transport()
        val version = "0.8.0-alpha.2"
        val kitName = "NimbyRailsFranceSDK-kotlin-$version-windows-x64.zip"
        val kitRelease = release(version).copy(assets = listOf(ReleaseAsset(kitName, "uploaded",
            DistributionLocation.githubDownload("sdk", version, kitName), 42, "sha256:$hash")))
        transport.texts[DistributionLocation.githubCatalogue("sdk")] = """{"schema":1,"project":"sdk","releases":${hubJson.encodeToString(listOf(kitRelease))}}"""
        val source = GitHubReleases(transport, "windows-x64")
        assertTrue(DistributionLocation.github(source.kotlinKits("alpha").single().url))
        val installer = HubRelease(1, "NRFHub", "windows-x64", "0.4.1-alpha.4",
            DistributionLocation.page("hub", "0.4.1-alpha.4") + "setup.exe", hash, 42, "alpha")
        val manifestUrl = DistributionLocation.githubDownload("hub", installer.version, "hub-latest-windows-x64.json")
        val hubRelease = GitHubRelease("v${installer.version}", prerelease = true, publishedAt = "2026-09-28T00:00:00Z", assets = listOf(
            ReleaseAsset("hub-latest-windows-x64.json", "uploaded", manifestUrl, 200),
            ReleaseAsset("setup.exe", "uploaded", DistributionLocation.githubMirror(installer.url), 42, "sha256:$hash")))
        transport.texts[DistributionLocation.githubCatalogue("hub")] = """{"schema":1,"project":"hub","releases":${hubJson.encodeToString(listOf(hubRelease))}}"""
        transport.texts[manifestUrl] = hubJson.encodeToString(installer)
        assertEquals(DistributionLocation.githubMirror(installer.url), source.hub("alpha").url)
        source.download(installer.url, 42, hash, Path.of("unused"))
        assertEquals(DistributionLocation.githubMirror(installer.url), transport.downloaded)
    }

    private class Source : ReleaseSource {
        var calls = 0; var failure: Exception? = null
        private fun called() { calls++; failure?.let { throw it } }
        override suspend fun catalogue(channels: Map<String, String>): Catalogue { called(); return Catalogue(1, emptyList()) }
        override suspend fun project(id: String, channel: String): Project { called(); return Project(id, "sdk", "0.8.0-alpha.1") }
        override suspend fun hub(channel: String): HubRelease = error("unused")
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) { called() }
        override suspend fun listen(onEvent: suspend () -> Unit) = awaitCancellation()
    }

    @Test fun offlinePrimaryUsesGithubAndRecoversWithoutRestart() = runTest {
        val primary = Source().apply { failure = IOException("offline") }; val github = Source()
        var now = 1L; val log = mutableListOf<String>()
        val source = ResilientReleases(primary, github, log::add) { now }
        source.project("sdk", "alpha"); source.project("sdk", "alpha")
        assertEquals(1, primary.calls); assertEquals(2, github.calls)
        primary.failure = null; now += 61_000_000_000
        source.project("sdk", "alpha")
        assertEquals(2, primary.calls); assertEquals(2, github.calls)
        assertEquals(2, log.size)
    }

    @Test fun userCancellationNeverStartsFallback() = runTest {
        val primary = Source().apply { failure = CancellationException("user cancelled") }; val github = Source()
        assertFailsWith<CancellationException> { ResilientReleases(primary, github).project("sdk", "alpha") }
        assertEquals(0, github.calls)
    }

    @Test fun pollingNotifiesEveryMinute() = runTest {
        var notifications = 0
        val task = backgroundScope.launch { ResilientReleases(Source(), Source()).listen { notifications++ } }
        runCurrent(); advanceTimeBy(60_000); runCurrent(); assertEquals(1, notifications)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, notifications)
        task.cancel()
    }

    @Test fun mirrorsAndRedirectsCannotLeaveTheAllowedOrigins() {
        val url = DistributionLocation.githubDownload("sdk", "0.8.0-alpha.1", "sdk.zip")
        assertEquals(url, DistributionLocation.githubMirror(DistributionLocation.serverMirror(url)))
        assertTrue(HttpReleaseTransport.allowedRedirect(url, URI("https://release-assets.githubusercontent.com/file?sig=public")))
        for (target in listOf("http://release-assets.githubusercontent.com/file", "https://github.com.evil.test/file",
            "https://evil.test/file", "https://user@release-assets.githubusercontent.com/file", "https://release-assets.githubusercontent.com:444/file")) {
            assertFalse(HttpReleaseTransport.allowedRedirect(url, URI(target)))
        }
        assertFalse(HttpReleaseTransport.allowedInitial("https://api.github.com/repos/Other/sdk/releases?per_page=100&page=1"))
        assertFails { DistributionLocation.githubMirror("https://evil.test/sdk.zip") }
    }
}
