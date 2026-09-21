package fr.nimby.hub.network

import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.sha256
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

interface ReleaseSource {
    suspend fun catalogue(channels: Map<String, String> = emptyMap()): Catalogue
    suspend fun project(id: String, channel: String = "stable"): Project
    suspend fun hub(channel: String = "stable"): HubRelease
    suspend fun download(url: String, size: Long, hash: String, destination: Path)
    suspend fun listen(onEvent: suspend () -> Unit)
    suspend fun sdkVersions(channel: String): List<Project> = listOf(project("sdk", channel))
}

class GitHub : ReleaseSource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private data class Cached(val etag: String, val text: String)
    private val cache = ConcurrentHashMap<String, Cached>()
    @Volatile private var retryAfter = 0L
    private var repositories = emptyList<String>()
    private var discoverUntil = 0L
    private fun response(url: String, etag: String? = null): HttpResponse<java.io.InputStream> {
        require(URI(url).scheme == "https")
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "NRFHub/$HUB_VERSION").GET()
        if (URI(url).host == "api.github.com") {
            builder.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            if (etag != null) builder.header("If-None-Match", etag)
        }
        val request = builder.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() in listOf(403, 429) && URI(url).host == "api.github.com") {
            val reset = response.headers().firstValue("X-RateLimit-Reset").orElse("0").toLongOrNull() ?: 0
            retryAfter = maxOf(System.currentTimeMillis() + 300_000, reset * 1000)
        }
        if (response.statusCode() !in listOf(200, 304) || response.uri().scheme != "https") {
            response.body().close()
            error("Serveur indisponible : HTTP ${response.statusCode()}")
        }
        return response
    }
    private suspend fun text(url: String, limit: Int): String = runInterruptible(Dispatchers.IO) {
        check(System.currentTimeMillis() >= retryAfter) { "Limite GitHub atteinte · nouvelle tentative après ${java.time.Instant.ofEpochMilli(retryAfter)}" }
        val prior = cache[url]
        val response = response(url, prior?.etag)
        response.body().use { input ->
            if (response.statusCode() == 304) return@runInterruptible checkNotNull(prior).text
            val bytes = input.readNBytes(limit + 1)
            require(bytes.size <= limit) { "Réponse trop volumineuse" }
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            if (cache.size > 64) cache.clear()
            response.headers().firstValue("ETag").orElse(null)?.let { cache[url] = Cached(it, text) }
            text
        }
    }

    private suspend fun pages(path: String): JsonArray {
        val result = mutableListOf<JsonElement>()
        for (page in 1..20) {
            val batch = hubJson.parseToJsonElement(text("https://api.github.com/${path}per_page=100&page=$page", 4 * 1024 * 1024)).jsonArray
            result.addAll(batch)
            if (batch.size < 100) return JsonArray(result)
        }
        error("Trop de pages GitHub · aucune sélection partielle de version")
    }

    private suspend fun discover(): List<String> {
        if (System.currentTimeMillis() < discoverUntil) return repositories
        repositories = pages("orgs/NimbyRails-France/repos?type=public&").map { it.jsonObject }.filter { repo ->
            val name = repo["name"]?.jsonPrimitive?.content.orEmpty()
            val owner = repo["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.content
            owner.equals("NimbyRails-France", true) && ProjectRules.identifier.matches(name) &&
                name !in listOf("hub", "website", "site", "bot", "nimbyrailsfrance-bot") &&
                listOf("archived", "disabled", "fork", "private").none { repo[it]?.jsonPrimitive?.booleanOrNull == true }
        }.map { it.getValue("name").jsonPrimitive.content }.distinct().sortedWith(compareBy<String> { it != "sdk" }.thenBy { it })
        discoverUntil = System.currentTimeMillis() + 3_600_000
        return repositories
    }

    override suspend fun catalogue(channels: Map<String, String>): Catalogue {
        val projects = mutableListOf<Project>()
        val errors = mutableMapOf<String, String>()
        for (id in discover()) {
            try { projects += project(id, channels[id]?.takeIf { it in listOf("stable", "beta", "alpha") } ?: "stable") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { errors[id] = failure.message ?: "Release indisponible" }
        }
        return Catalogue(1, projects, errors)
    }

    private suspend fun manifest(repo: String, channel: String): Pair<GitHubRelease, JsonObject> {
        require(ProjectRules.identifier.matches(repo))
        val releases = hubJson.decodeFromJsonElement<List<GitHubRelease>>(pages("repos/NimbyRails-France/$repo/releases?"))
        val selected = ReleaseSelection.selectForPlatform(releases, channel, repo, Host.id)
            ?: error("Aucune release installable pour ${Host.id} sur le canal $channel")
        val manifest = hubJson.parseToJsonElement(text(ReleaseSelection.assetUrl(selected.release, repo, selected.manifest), 65536)).jsonObject
        return selected.release to manifest
    }

    override suspend fun project(id: String, channel: String): Project {
        val (release, manifest) = manifest(id, channel)
        return verifiedProject(id, release, manifest)
    }
    private fun verifiedProject(id: String, release: GitHubRelease, manifest: JsonObject): Project {
        val project = hubJson.decodeFromJsonElement<Project>(manifest)
        require(project.platform == Host.id) { "Paquet ${project.platform} incompatible avec ${Host.id}" }
        ProjectRules.validate(project)
        require(project.id == id && (id !in listOf("sdk", "tco") || project.kind == id))
        ReleaseSelection.validateAsset(release, id, project.version, project.channel, project.url, project.size, project.sha256)
        return project.copy(channel = Versions.channel(project.version), releaseUrl = "https://github.com/NimbyRails-France/$id/releases/tag/${release.tag}", changelog = release.body.orEmpty())
    }
    override suspend fun sdkVersions(channel: String): List<Project> {
        val releases = hubJson.decodeFromJsonElement<List<GitHubRelease>>(pages("repos/NimbyRails-France/sdk/releases?"))
        val eligible = releases.mapNotNull { ReleaseSelection.selectForPlatform(listOf(it), channel, "sdk", Host.id) }
            .sortedWith { a, b -> Versions.compare(b.release.version, a.release.version) }
        return eligible.mapNotNull { selected ->
            try {
                val manifest = hubJson.parseToJsonElement(text(ReleaseSelection.assetUrl(selected.release, "sdk", selected.manifest), 65536)).jsonObject
                verifiedProject("sdk", selected.release, manifest)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    override suspend fun hub(channel: String): HubRelease {
        val (release, manifest) = manifest("hub", channel)
        val hub = hubJson.decodeFromJsonElement<HubRelease>(manifest).also(ProjectRules::validate)
        require(hub.platform == Host.id) { "Mise à jour incompatible avec ${Host.id}" }
        ReleaseSelection.validateAsset(release, "hub", hub.version, hub.channel, hub.url, hub.size, hub.sha256)
        return hub
    }

    override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
        require(ProjectRules.officialUrl(url) && size in 1..536_870_912 && ProjectRules.hash.matches(hash))
        destination.parent.createDirectories()
        try {
            runInterruptible(Dispatchers.IO) {
                response(url).body().use { input ->
                    destination.outputStream().use { output ->
                        val bytes = ByteArray(65536)
                        var count = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val length = input.read(bytes)
                            if (length < 0) break
                            count += length
                            require(count <= size) { "Téléchargement trop volumineux" }
                            output.write(bytes, 0, length)
                        }
                        require(count == size) { "Téléchargement incomplet" }
                    }
                }
                require(destination.sha256().equals(hash, true)) { "Empreinte SHA-256 incorrecte" }
            }
        } catch (failure: Exception) { destination.deleteIfExists(); throw failure }
    }

    override suspend fun listen(onEvent: suspend () -> Unit) = withContext(Dispatchers.IO) {
        val input = runInterruptible { response("https://ntfy.sh/nrf-hub-releases-v1-67e49b30/json").body() }
        try {
            input.bufferedReader().use { reader ->
                while (isActive) {
                    // Bounded line reader: public relay data cannot grow an unbounded buffer.
                    val line = runInterruptible {
                        buildString {
                            while (true) {
                                val next = reader.read()
                                if (next < 0) { if (isEmpty()) error("Relais déconnecté"); break }
                                if (next == 10) break
                                require(length < 65536) { "Événement trop volumineux" }
                                append(next.toChar())
                            }
                        }
                    }
                    val event = runCatching { hubJson.parseToJsonElement(line).jsonObject["event"]?.jsonPrimitive?.content }.getOrNull()
                    // No remote paths or download instructions are ever read from this public relay.
                    if (event == "message" || event == "open") onEvent()
                }
            }
        } finally { input.close() }
    }
}
