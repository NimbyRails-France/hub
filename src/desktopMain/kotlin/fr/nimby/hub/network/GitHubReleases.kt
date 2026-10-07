package fr.nimby.hub.network

import fr.nimby.hub.i18n.tr
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Path

/** Public release catalogues are regular GitHub assets, not API requests.
 * API discovery only bootstraps repositories that do not have a catalogue yet.
 * Neither transport ever contains a publisher token or user credentials. */
class GitHubReleases(
    private val transport: ReleaseTransport = HttpReleaseTransport(),
    private val platform: String = Host.id,
    private val now: () -> Long = System::nanoTime,
) : ReleaseSource {
    @Serializable private data class Index(val schema: Int, val project: String, val releases: List<GitHubRelease>)
    private data class Cached(val until: Long, val apiUntil: Long, val releases: List<GitHubRelease>)
    private val cache = mutableMapOf<String, Cached>()
    private val lock = Mutex()
    private var apiWindow = now()
    private var apiRequests = 0
    private var apiBlockedUntil = Long.MIN_VALUE

    private suspend fun releases(repo: String): List<GitHubRelease> = lock.withLock {
        require(ProjectRules.identifier.matches(repo))
        cache[repo]?.takeIf { now() < it.until }?.let { return@withLock it.releases }
        var apiUntil = cache[repo]?.apiUntil ?: 0L
        val result = try {
            val index = hubJson.decodeFromString<Index>(transport.text(DistributionLocation.githubCatalogue(repo), 4 * 1024 * 1024))
            require(index.schema == 1 && index.project == repo && index.releases.size <= 1000)
            index.releases
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Public catalogue checks continue every minute. Only API bootstrap
            // is cached longer; a newly published static catalogue wins immediately.
            val prior = cache[repo]
            if (prior != null && now() < apiUntil) prior.releases
            else try {
                apiUntil = now() + FIFTEEN_MINUTES
                apiReleases(repo)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { prior?.releases ?: throw failure }
        }
        cache[repo] = Cached(now() + MINUTE, apiUntil, result)
        result
    }

    private suspend fun apiReleases(repo: String): List<GitHubRelease> {
        val result = mutableListOf<GitHubRelease>()
        for (page in 1..10) {
            val time = now()
            if (time - apiWindow >= HOUR) { apiWindow = time; apiRequests = 0 }
            check(time >= apiBlockedUntil && apiRequests < 40) { tr("GitHub limite les requêtes. Réessayez plus tard.") }
            apiRequests++
            val batch = try {
                hubJson.decodeFromString<List<GitHubRelease>>(transport.text(
                    "https://api.github.com/repos/NimbyRails-France/${DistributionLocation.githubRepository(repo)}/releases?per_page=100&page=$page", 4 * 1024 * 1024))
            } catch (failure: ReleaseHttpException) {
                if (failure.status in listOf(403, 429)) apiBlockedUntil = time + HOUR
                if (failure.status == 404) return emptyList()
                throw failure
            }
            require(batch.size <= 100)
            result += batch
            if (batch.size < 100) return result
        }
        throw IOException("GitHub release history exceeds the supported catalogue size")
    }

    override suspend fun catalogue(channels: Map<String, String>): Catalogue {
        val projects = mutableListOf<Project>(); val errors = mutableMapOf<String, String>()
        for (id in (DistributionLocation.projects + channels.keys).filter { it != "hub" && ProjectRules.identifier.matches(it) }.sortedWith(compareBy<String> { it != "sdk" }.thenBy { it })) {
            try { projects += project(id, channels[id] ?: "stable") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { errors[id] = failure.message ?: tr("Release indisponible") }
        }
        return Catalogue(1, projects, errors)
    }

    private suspend fun manifest(repo: String, selected: ReleaseSelection.Selected): JsonObject {
        val url = ReleaseSelection.assetUrl(selected.release, repo, selected.manifest)
        require(DistributionLocation.github(url))
        return hubJson.parseToJsonElement(transport.text(DistributionLocation.githubMirror(url), 65536)).jsonObject
    }

    private suspend fun selected(repo: String, channel: String) =
        ReleaseSelection.selectForPlatform(releases(repo), channel, repo, platform)
            ?: error(tr("Aucune release installable pour {0} sur le canal {1}", repo, channel))

    private fun mirrored(release: GitHubRelease, repo: String, version: String, channel: String?, url: String, size: Long, hash: String): String {
        require(ReleaseSelection.officialAsset(url, repo, release.tag))
        val target = DistributionLocation.githubMirror(url)
        // Older GitHub manifests may point to the offline NRF server. Only the
        // same filename/version is substituted, with the original hash and size.
        ReleaseSelection.validateAsset(release, repo, version, channel, target, size, hash)
        return target
    }

    private suspend fun project(repo: String, selected: ReleaseSelection.Selected): Project {
        val value = hubJson.decodeFromJsonElement<Project>(manifest(repo, selected))
        ProjectRules.validate(value)
        require(value.id == repo && value.platform == platform && (repo !in listOf("sdk", "tco") || value.kind == repo))
        return value.copy(url = mirrored(selected.release, repo, value.version, value.channel, value.url, value.size, value.sha256),
            channel = Versions.channel(value.version), releaseUrl = DistributionLocation.githubPage(repo, value.version), changelog = selected.release.body.orEmpty())
    }

    override suspend fun project(id: String, channel: String) = project(id, selected(id, channel))
    override suspend fun sdkVersions(channel: String): List<Project> = releases("sdk")
        .mapNotNull { ReleaseSelection.selectForPlatform(listOf(it), channel, "sdk", platform) }
        .sortedWith { a, b -> Versions.compare(b.release.version, a.release.version) }
        .mapNotNull { try { project("sdk", it) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } }
    override suspend fun kotlinKits(channel: String) = KotlinKitReleases.select(releases("sdk"), channel, platform)
    override suspend fun hub(channel: String): HubRelease {
        val selected = selected("hub", channel)
        val value = hubJson.decodeFromJsonElement<HubRelease>(manifest("hub", selected)).also(ProjectRules::validate)
        require(value.platform == platform)
        return value.copy(url = mirrored(selected.release, "hub", value.version, value.channel, value.url, value.size, value.sha256))
    }
    override suspend fun download(url: String, size: Long, hash: String, destination: Path) =
        transport.download(DistributionLocation.githubMirror(url), size, hash, destination)
    override suspend fun listen(onEvent: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) { delay(60_000); onEvent() }
    }
    companion object {
        private const val MINUTE = 60_000_000_000L
        private const val FIFTEEN_MINUTES = 900_000_000_000L
        private const val HOUR = 3_600_000_000_000L
    }
}
