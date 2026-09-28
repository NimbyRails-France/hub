package fr.nimby.hub.model

import fr.nimby.hub.i18n.tr

/** A development kit is a downloadable build dependency, not a game installation. */
data class KotlinKit(val version: String, val platform: String, val url: String, val size: Long, val sha256: String)

object KotlinKitReleases {
    fun validate(kit: KotlinKit) {
        require(Versions.valid(kit.version) && kit.platform == "windows-x64") { tr("Kit Kotlin incompatible") }
        val name = "NimbyRailsFranceSDK-kotlin-${kit.version}-${kit.platform}.zip"
        require(kit.url in listOf(DistributionLocation.page("sdk", kit.version) + name,
            DistributionLocation.githubDownload("sdk", kit.version, name)) &&
            kit.size in 1..536_870_912 && ProjectRules.hash.matches(kit.sha256)) { tr("Archive du kit Kotlin non vérifiable") }
    }

    fun select(releases: List<GitHubRelease>, channel: String, platform: String): List<KotlinKit> {
        if (platform != "windows-x64") return emptyList()
        return releases.mapNotNull { release ->
            val name = "NimbyRailsFranceSDK-kotlin-${release.version}-$platform.zip"
            if (ReleaseSelection.select(listOf(release), channel, name) == null) return@mapNotNull null
            val asset = release.assets.single { it.name == name && it.state == "uploaded" }
            val hash = asset.digest?.takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')
                ?: error(tr("Kit {0} : empreinte SHA-256 absente du catalogue", release.version))
            KotlinKit(release.version, platform, asset.url, asset.size, hash).also(::validate)
        }.sortedWith { a, b -> Versions.compare(b.version, a.version) }
    }
}
