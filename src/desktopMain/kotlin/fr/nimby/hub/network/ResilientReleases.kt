package fr.nimby.hub.network

import fr.nimby.hub.i18n.tr
import fr.nimby.hub.model.*
import kotlinx.coroutines.*
import java.nio.file.Path

/** NRF first, then public GitHub assets. A short cooldown prevents each project
 * from waiting on the same offline server. The preferred server is retried later. */
class ResilientReleases(
    private val primary: ReleaseSource = ReleaseServer(),
    private val fallback: ReleaseSource = GitHubReleases(),
    private val report: (String) -> Unit = {},
    private val now: () -> Long = System::nanoTime,
) : ReleaseSource {
    @Volatile private var retryAt = 0L

    private suspend fun <T> read(operation: suspend (ReleaseSource) -> T): T {
        var primaryFailure: Exception? = null
        if (retryAt == 0L || now() >= retryAt) {
            try {
                val result = operation(primary)
                if (retryAt != 0L) report(tr("Serveur NRF de nouveau disponible"))
                retryAt = 0L
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                primaryFailure = failure
                retryAt = now() + 60_000_000_000L
                report(tr("Serveur NRF indisponible : utilisation de GitHub · {0}", failure.message))
            }
        }
        try { return operation(fallback) }
        catch (failure: Exception) { primaryFailure?.let(failure::addSuppressed); throw failure }
    }

    override suspend fun catalogue(channels: Map<String, String>): Catalogue {
        val result = read { it.catalogue(channels) }
        if (retryAt != 0L) return result
        val projects = result.projects.toMutableList(); val errors = result.errors.toMutableMap()
        val missing = DistributionLocation.projects - result.projects.map { it.id }.toSet() - "hub"
        for (id in (errors.keys + missing).toList()) {
            try {
                projects += fallback.project(id, channels[id] ?: "stable"); errors.remove(id)
                report(tr("Projet {0} récupéré depuis GitHub", id))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the original per-project diagnostic. */ }
        }
        return result.copy(projects = projects, errors = errors)
    }
    override suspend fun project(id: String, channel: String) = read { it.project(id, channel) }
    override suspend fun hub(channel: String) = read { it.hub(channel) }
    override suspend fun sdkVersions(channel: String) = read { it.sdkVersions(channel) }.ifEmpty { fallback.sdkVersions(channel) }
    override suspend fun kotlinKits(channel: String) = read { it.kotlinKits(channel) }.ifEmpty { fallback.kotlinKits(channel) }
    override suspend fun download(url: String, size: Long, hash: String, destination: Path) =
        read { it.download(url, size, hash, destination) }
    override suspend fun listen(onEvent: suspend () -> Unit) {
        // Poll through read() so recovery also works while GitHub is serving.
        while (currentCoroutineContext().isActive) { delay(60_000); onEvent() }
    }
}
