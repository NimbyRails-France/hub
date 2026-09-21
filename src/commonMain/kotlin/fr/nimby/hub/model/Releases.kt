package fr.nimby.hub.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseAsset(
    val name: String,
    val state: String = "",
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
    val body: String? = null,
) {
    val version get() = tag.removePrefix("v")
}

object ReleaseSelection {
    data class Selected(val release: GitHubRelease, val manifest: String)

    fun manifests(product: String, platform: String): List<String> {
        require(platform in setOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64"))
        val base = if (product == "hub") "hub-latest" else "project"
        return listOf("$base-$platform.json") + if (platform == "windows-x64") listOf("$base.json") else emptyList()
    }

    fun selectForPlatform(releases: List<GitHubRelease>, channel: String, product: String, platform: String): Selected? =
        releases.mapNotNull { release ->
            manifests(product, platform).firstOrNull { select(listOf(release), channel, it) != null }
                ?.let { Selected(release, it) }
        }.maxWithOrNull { a, b -> Versions.compare(a.release.version, b.release.version) }

    fun select(releases: List<GitHubRelease>, channel: String, manifest: String): GitHubRelease? {
        if (channel !in listOf("stable", "beta", "alpha")) return null
        return releases.filter { release ->
            val actualChannel = Versions.channel(release.version)
            release.tag.startsWith('v') && !release.draft && !release.publishedAt.isNullOrBlank() &&
                actualChannel != null && (actualChannel == channel || actualChannel == "stable") &&
                release.prerelease == (actualChannel != "stable") &&
                release.assets.any { it.name == manifest && it.state == "uploaded" }
        }.maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }
    }

    fun officialAsset(url: String, repo: String, tag: String): Boolean {
        val prefix = "https://github.com/NimbyRails-France/$repo/releases/download/$tag/"
        if (!url.startsWith(prefix)) return false
        val name = url.removePrefix(prefix)
        return name.isNotEmpty() && name !in listOf(".", "..") && name.none { it in "/\\?#%" || it.code < 32 }
    }

    fun assetUrl(release: GitHubRelease, repo: String, name: String): String = release.assets.firstOrNull {
        it.name == name && it.state == "uploaded" && officialAsset(it.url, repo, release.tag)
    }?.url ?: error("Manifeste absent ou non officiel")

    fun validateAsset(release: GitHubRelease, repo: String, version: String, channel: String?, url: String, size: Long, hash: String) {
        require(version == release.version && (channel == null || channel == Versions.channel(version))) { "Version ou canal incohérent avec la release" }
        require(officialAsset(url, repo, release.tag)) { "Asset d'une autre release" }
        val asset = release.assets.firstOrNull { it.url == url && it.state == "uploaded" && it.size == size }
        require(asset != null && (asset.digest.isNullOrEmpty() || asset.digest.equals("sha256:$hash", true))) { "Taille ou empreinte différente de l'asset GitHub" }
    }
}
