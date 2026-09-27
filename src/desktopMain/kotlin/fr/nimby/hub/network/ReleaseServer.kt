package fr.nimby.hub.network

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

@Serializable
private data class DistributionIndex(val schema: Int, val generatedAt: String,
    val projects: Map<String, List<GitHubRelease>>)

/** Uses the NRF static catalogue and packages exclusively. The release record
 * format retains its old field names for compatibility, not its GitHub transport.
 * One catalogue fetch serves project discovery, channels and SDK history.
 */
class ReleaseServer : ReleaseSource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private data class Cached(val etag: String?, val text: String)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val indexLock = Mutex()
    private var lastIndex: DistributionIndex? = null
    private var indexUntil = 0L

    private fun response(url: String, etag: String? = null): HttpResponse<java.io.InputStream> {
        require(url == DistributionLocation.catalogue || DistributionLocation.official(url)) { "Adresse de distribution non officielle" }
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(120))
            .header("User-Agent", "NRFHub/$HUB_VERSION").GET()
        etag?.let { request.header("If-None-Match", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()).also {
            if (it.statusCode() !in listOf(200, 304)) {
                it.body().close()
                error("Serveur de distribution indisponible : HTTP ${it.statusCode()}")
            }
        }
    }

    private suspend fun text(url: String, limit: Int): String = runInterruptible(Dispatchers.IO) {
        val prior = cache[url]
        val response = response(url, prior?.etag)
        response.body().use { input ->
            if (response.statusCode() == 304) return@runInterruptible checkNotNull(prior).text
            val bytes = input.readNBytes(limit + 1)
            require(bytes.size <= limit) { "Catalogue trop volumineux" }
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            if (cache.size > 128) cache.clear()
            cache[url] = Cached(response.headers().firstValue("ETag").orElse(null), text)
            text
        }
    }

    private suspend fun index(): DistributionIndex = indexLock.withLock {
        if (System.nanoTime() < indexUntil) return@withLock checkNotNull(lastIndex)
        val next = hubJson.decodeFromString<DistributionIndex>(text(DistributionLocation.catalogue, 4 * 1024 * 1024))
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
            catch (failure: Exception) { errors[id] = failure.message ?: "Release indisponible" }
        }
        return Catalogue(1, projects, errors)
    }

    private suspend fun manifest(repo: String, channel: String): Pair<GitHubRelease, JsonObject> {
        val selected = ReleaseSelection.selectForPlatform(index().projects[repo].orEmpty(), channel, repo, Host.id)
            ?: error("Aucune release installable pour ${Host.id} sur le canal $channel")
        return selected.release to readManifest(repo, selected)
    }

    private suspend fun readManifest(repo: String, selected: ReleaseSelection.Selected): JsonObject {
        val url = ReleaseSelection.assetUrl(selected.release, repo, selected.manifest)
        require(DistributionLocation.forRelease(url, repo, selected.release.version))
        return hubJson.parseToJsonElement(text(url, 65536)).jsonObject
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

    override suspend fun hub(channel: String): HubRelease {
        val (release, manifest) = manifest("hub", channel)
        val hub = hubJson.decodeFromJsonElement<HubRelease>(manifest).also(ProjectRules::validate)
        require(hub.platform == Host.id && DistributionLocation.forRelease(hub.url, "hub", hub.version))
        ReleaseSelection.validateAsset(release, "hub", hub.version, hub.channel, hub.url, hub.size, hub.sha256)
        return hub
    }

    override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
        require(DistributionLocation.official(url) && size in 1..536_870_912 && ProjectRules.hash.matches(hash))
        destination.parent.createDirectories()
        try {
            runInterruptible(Dispatchers.IO) {
                val response = response(url)
                require(response.statusCode() == 200)
                response.body().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var count = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val length = input.read(buffer)
                            if (length < 0) break
                            count += length
                            require(count <= size) { "Téléchargement trop volumineux" }
                            output.write(buffer, 0, length)
                        }
                        require(count == size) { "Téléchargement incomplet" }
                    }
                }
                require(destination.sha256().equals(hash, true)) { "Empreinte SHA-256 incorrecte" }
            }
        } catch (failure: Exception) { destination.deleteIfExists(); throw failure }
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
