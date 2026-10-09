package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.network.*
import fr.nimby.hub.platform.Host
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*

/** Exercise discovery with a fresh profile; no install state supplies the mod ID. */
class TrainModDiscoveryTest {
    private val id = "bc-train-super-long"
    private val hash = "a".repeat(64)
    private val filename = "BC-Train-super-long-0.1.0-${Host.id}.zip"
    private val manifest = "project-${Host.id}.json"
    private val api = "https://api.github.com/repos/NimbyRails-France/$id/releases?per_page=100&page=1"

    private class Transport : ReleaseTransport {
        val texts = mutableMapOf<String, String>()
        val requests = mutableListOf<String>()
        override suspend fun text(url: String, limit: Int): String {
            requests += url
            return texts[url] ?: throw ReleaseHttpException(404)
        }
        override suspend fun download(url: String, size: Long, hash: String, destination: Path) = error("No download expected")
    }

    private fun project() = Project(id, "native-mod", "0.1.0", name = "BC Train super long",
        url = DistributionLocation.page(id, "0.1.0") + filename, size = 42, sha256 = hash,
        // The synthetic release targets the test host, including its native library format.
        rootFolder = "BC-Train-super-long-0.1.0", modId = id, module = "$id-mod.${Host.moduleExtension}", loaderApi = 1,
        platform = Host.id, gameSha256 = listOf(hash), sdkMin = "0.9.0-alpha.3", sdkMaxExclusive = "0.10.0",
        developmentStatus = "in-development")

    private fun release(server: Boolean = false): GitHubRelease {
        fun url(name: String) = if (server) DistributionLocation.page(id, "0.1.0") + name
            else DistributionLocation.githubDownload(id, "0.1.0", name)
        return GitHubRelease("v0.1.0", publishedAt = "2026-10-09T12:00:00Z", body = "Train composition length limit.", assets = listOf(
            ReleaseAsset(manifest, "uploaded", url(manifest), 200),
            ReleaseAsset(filename, "uploaded", url(filename), 42, "sha256:$hash")))
    }

    private fun githubTransport(staticCatalogue: Boolean = true) = Transport().apply {
        if (staticCatalogue) texts[DistributionLocation.githubCatalogue(id)] = buildJsonObject {
            put("schema", 1); put("project", id); put("releases", hubJson.encodeToJsonElement(listOf(release())))
        }.toString()
        else texts[api] = hubJson.encodeToString(listOf(release()))
        texts[DistributionLocation.githubDownload(id, "0.1.0", manifest)] = hubJson.encodeToString(project())
    }

    private fun assertTrainMod(value: Project) {
        assertEquals(id, value.id)
        assertEquals("BC Train super long", value.name)
        assertEquals("0.1.0", value.version)
        assertEquals("stable", value.channel)
        assertEquals("in-development", value.developmentStatus)
        assertEquals(Host.id, value.platform)
        assertEquals("$id-mod.${Host.moduleExtension}", value.module)
        assertEquals(1, value.loaderApi)
        assertEquals("0.9.0-alpha.3", value.sdkMin)
        assertEquals("0.10.0", value.sdkMaxExclusive)
        assertEquals("Train composition length limit.", value.changelog)
        assertEquals(hash, value.sha256)
    }

    @Test fun freshProfileDiscoversTrainModFromPublicGithubCatalogue() = runTest {
        val transport = githubTransport()
        val catalogue = GitHubReleases(transport, Host.id).catalogue(emptyMap())
        val value = catalogue.projects.single { it.id == id }
        assertTrainMod(value)
        assertEquals(DistributionLocation.githubDownload(id, "0.1.0", filename), value.url)
        assertTrue(DistributionLocation.githubCatalogue(id) in transport.requests)
        assertFalse(transport.requests.any { it.startsWith("https://api.github.com/repos/NimbyRails-France/$id/") })
    }

    @Test fun firstTrainModReleaseBootstrapsWithoutStaticGithubCatalogue() = runTest {
        val transport = githubTransport(staticCatalogue = false)
        val value = GitHubReleases(transport, Host.id).catalogue(emptyMap()).projects.single { it.id == id }
        assertTrainMod(value)
        assertTrue(api in transport.requests)
        assertTrue(DistributionLocation.githubCatalogue(id) in transport.requests)
    }

    @Test fun releaseServerAcceptsTrainModCatalogueAndPreservesSdkPrerequisite() = runTest {
        val transport = Transport().apply {
            texts[DistributionLocation.catalogue] = buildJsonObject {
                put("schema", 1); put("generatedAt", "2026-10-09T12:00:00Z")
                put("projects", buildJsonObject { put(id, hubJson.encodeToJsonElement(listOf(release(server = true)))) })
            }.toString()
            texts[DistributionLocation.page(id, "0.1.0") + manifest] = hubJson.encodeToString(project())
        }
        val catalogue = ReleaseServer(transport).catalogue(emptyMap())
        assertTrue(catalogue.errors.isEmpty())
        assertTrainMod(catalogue.projects.single())
        assertEquals(project().url, catalogue.projects.single().url)
        assertTrue(transport.requests.all { it.startsWith(DistributionLocation.origin) })
    }

    @Test fun serverMissingTrainModAndOfflineServerBothUseGithubDiscovery() = runTest {
        // A valid older NRF index can omit a newly published mod. A failed NRF
        // request takes the full catalogue fallback. Both must work on first use.
        for (offline in listOf(false, true)) {
            val primary = object : ReleaseSource {
                override suspend fun catalogue(channels: Map<String, String>): Catalogue {
                    if (offline) throw IOException("offline")
                    return Catalogue(1, emptyList())
                }
                override suspend fun project(id: String, channel: String): Project = error("Unexpected primary project lookup")
                override suspend fun hub(channel: String): HubRelease = error("Unexpected Hub lookup")
                override suspend fun download(url: String, size: Long, hash: String, destination: Path) = error("No download expected")
                override suspend fun listen(onEvent: suspend () -> Unit) = awaitCancellation()
            }
            val transport = githubTransport()
            val result = ResilientReleases(primary, GitHubReleases(transport, Host.id)).catalogue(emptyMap())
            assertTrainMod(result.projects.single { it.id == id })
            assertFalse(id in result.errors)
            assertTrue(DistributionLocation.githubCatalogue(id) in transport.requests)
        }
    }
}
