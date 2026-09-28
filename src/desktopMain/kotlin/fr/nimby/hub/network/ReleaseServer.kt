package fr.nimby.hub.network

import fr.nimby.hub.i18n.tr

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path

@Serializable
private data class DistributionIndex(val schema: Int, val generatedAt: String,
    val projects: Map<String, List<GitHubRelease>>)

/** Preferred NRF source. GitHub fallback is coordinated by ResilientReleases. */
class ReleaseServer(private val transport: ReleaseTransport = HttpReleaseTransport()) : ReleaseSource {
    private val indexLock = Mutex()
    private var lastIndex: DistributionIndex? = null
    private var indexUntil = 0L

    private suspend fun index(): DistributionIndex = indexLock.withLock {
        if (System.nanoTime() < indexUntil) return@withLock checkNotNull(lastIndex)
        val next = hubJson.decodeFromString<DistributionIndex>(transport.text(DistributionLocation.catalogue, 4 * 1024 * 1024))
        require(next.schema == 1 && next.generatedAt.isNotBlank() && next.projects.size <= 1000)
        require(next.projects.keys.all(ProjectRules.identifier::matches))
        lastIndex = next
        indexUntil = System.nanoTime() + 30_000_000_000L
        next
    }

    override suspend fun catalogue(channels: Map<String, String>): Catalogue {
        val projects = mutableListOf<Project>()
        val errors = mutableMapOf<String, String>()
        for (id in index().projects.keys.filter { it != "hub" }.sortedWith(compareBy<String> { it != "sdk" }.thenBy { it })) {
            try { projects += project(id, channels[id] ?: "stable") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { errors[id] = failure.message ?: tr("Release indisponible") }
        }
        return Catalogue(1, projects, errors)
    }

    private suspend fun manifest(repo: String, channel: String): Pair<GitHubRelease, JsonObject> {
        val selected = ReleaseSelection.selectForPlatform(index().projects[repo].orEmpty(), channel, repo, Host.id)
            ?: error(tr("Aucune release installable pour {0} sur le canal {1}", Host.id, channel))
        return selected.release to readManifest(repo, selected)
    }

    private suspend fun readManifest(repo: String, selected: ReleaseSelection.Selected): JsonObject {
        val url = ReleaseSelection.assetUrl(selected.release, repo, selected.manifest)
        require(DistributionLocation.forRelease(url, repo, selected.release.version))
        return hubJson.parseToJsonElement(transport.text(url, 65536)).jsonObject
    }

    private fun verifiedProject(id: String, release: GitHubRelease, manifest: JsonObject): Project {
        val project = hubJson.decodeFromJsonElement<Project>(manifest)
        ProjectRules.validate(project)
        require(project.platform == Host.id && project.id == id)
        require(id !in listOf("sdk", "tco") || project.kind == id)
        require(DistributionLocation.forRelease(project.url, id, project.version))
        ReleaseSelection.validateAsset(release, id, project.version, project.channel, project.url, project.size, project.sha256)
        return project.copy(channel = Versions.channel(project.version),
            releaseUrl = DistributionLocation.page(id, project.version), changelog = release.body.orEmpty())
    }

    override suspend fun project(id: String, channel: String): Project {
        val (release, manifest) = manifest(id, channel)
        return verifiedProject(id, release, manifest)
    }

    override suspend fun sdkVersions(channel: String): List<Project> = index().projects["sdk"].orEmpty()
        .mapNotNull { ReleaseSelection.selectForPlatform(listOf(it), channel, "sdk", Host.id) }
        .sortedWith { a, b -> Versions.compare(b.release.version, a.release.version) }
        .mapNotNull { selected ->
            try { verifiedProject("sdk", selected.release, readManifest("sdk", selected)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    override suspend fun kotlinKits(channel: String): List<KotlinKit> =
        KotlinKitReleases.select(index().projects["sdk"].orEmpty(), channel, Host.id)

    override suspend fun hub(channel: String): HubRelease {
        val (release, manifest) = manifest("hub", channel)
        val hub = hubJson.decodeFromJsonElement<HubRelease>(manifest).also(ProjectRules::validate)
        require(hub.platform == Host.id && DistributionLocation.forRelease(hub.url, "hub", hub.version))
        ReleaseSelection.validateAsset(release, "hub", hub.version, hub.channel, hub.url, hub.size, hub.sha256)
        return hub
    }

    override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
        transport.download(DistributionLocation.serverMirror(url), size, hash, destination)
    }

    override suspend fun listen(onEvent: suspend () -> Unit) {
        var generation = index().generatedAt
        while (currentCoroutineContext().isActive) {
            delay(60_000)
            val next = index().generatedAt
            if (next != generation) { generation = next; onEvent() }
        }
    }
}
