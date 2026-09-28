package fr.nimby.hub.network

import fr.nimby.hub.i18n.tr
import fr.nimby.hub.model.*
import fr.nimby.hub.storage.sha256
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

/** Shared by both official sources; tests provide a transport with no network.
 * A mirror never relaxes size, hash or identity checks. No access token is used. */
interface ReleaseTransport {
    suspend fun text(url: String, limit: Int): String
    suspend fun download(url: String, size: Long, hash: String, destination: Path)
}

class ReleaseHttpException(val status: Int) : IOException("Distribution HTTP $status")

class HttpReleaseTransport : ReleaseTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private data class Cached(val etag: String?, val text: String)
    private val cache = ConcurrentHashMap<String, Cached>()

    companion object {
        private val api = Regex("https://api\\.github\\.com/repos/NimbyRails-France/[a-z][a-z0-9-]{0,63}/releases\\?per_page=100&page=[1-9][0-9]?")
        internal fun allowedInitial(url: String) = url == DistributionLocation.catalogue ||
            ProjectRules.officialUrl(url) || DistributionLocation.isGithubCatalogue(url) || api.matches(url)
        internal fun allowedRedirect(original: String, next: URI): Boolean =
            (DistributionLocation.github(original) || DistributionLocation.isGithubCatalogue(original)) && next.scheme == "https" && next.userInfo == null &&
                next.port in listOf(-1, 443) && next.fragment == null &&
                (DistributionLocation.github(next.toString()) || DistributionLocation.isGithubCatalogue(next.toString()) ||
                    next.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
    }

    private suspend fun <T> limited(milliseconds: Long, block: suspend () -> T): T =
        try { withTimeout(milliseconds) { block() } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // An actual user cancellation never triggers a mirror.
            throw IOException("Distribution request timed out", timeout)
        }

    private fun response(url: String, etag: String? = null): HttpResponse<InputStream> {
        require(allowedInitial(url)) { tr("Adresse de distribution non officielle") }
        var current = URI(url)
        repeat(5) {
            val request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NRFHub/$HUB_VERSION").GET()
            if (DistributionLocation.isGithubCatalogue(url)) request.header("Cache-Control", "no-cache")
            if (current.host == "api.github.com") {
                request.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
            }
            if (current.toString() == url) etag?.let { request.header("If-None-Match", it) }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            when (response.statusCode()) {
                200, 304 -> return response
                301, 302, 303, 307, 308 -> {
                    response.body().close()
                    val target = current.resolve(response.headers().firstValue("Location").orElseThrow())
                    require(allowedRedirect(url, target)) { tr("Redirection de téléchargement non autorisée") }
                    current = target
                }
                else -> { response.body().close(); throw ReleaseHttpException(response.statusCode()) }
            }
        }
        error(tr("Trop de redirections de téléchargement"))
    }

    override suspend fun text(url: String, limit: Int): String = limited(if (url.startsWith(DistributionLocation.origin)) 10_000 else 30_000) {
        runInterruptible(Dispatchers.IO) {
            val prior = cache[url]
            val response = response(url, prior?.etag)
            response.body().use { input ->
                if (response.statusCode() == 304) return@runInterruptible checkNotNull(prior).text
                val bytes = input.readNBytes(limit + 1)
                require(bytes.size <= limit) { tr("Catalogue trop volumineux") }
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                if (cache.size > 128) cache.clear()
                cache[url] = Cached(response.headers().firstValue("ETag").orElse(null), text)
                text
            }
        }
    }

    override suspend fun download(url: String, size: Long, hash: String, destination: Path) {
        require(ProjectRules.officialUrl(url) && size in 1..536_870_912 && ProjectRules.hash.matches(hash))
        destination.parent.createDirectories()
        try {
            limited(180_000) { runInterruptible(Dispatchers.IO) {
                val response = response(url)
                require(response.statusCode() == 200)
                response.body().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val length = input.read(buffer)
                        if (length < 0) break
                        count += length
                        require(count <= size) { tr("Téléchargement trop volumineux") }
                        output.write(buffer, 0, length)
                    }
                    require(count == size) { tr("Téléchargement incomplet") }
                } }
                require(destination.sha256().equals(hash, true)) { tr("Empreinte SHA-256 incorrecte") }
            } }
        } catch (failure: Exception) { destination.deleteIfExists(); throw failure }
    }
}
