package fr.nimby.hub.model

/** Official download locations. Mirroring preserves project, version and filename. */
object DistributionLocation {
    const val origin = "https://releases.nimbyrails-france.fr"
    const val catalogue = "$origin/v1/catalog.json"
    // Discover official mods even before the profile has an installed record or
    // a channel preference, including when the NRF catalogue needs GitHub fallback.
    val projects = setOf("sdk", "hub", "tco", "signalisationfrancaiserealiste", "signal-placement", "time-change", "bc-train-super-long")
    // Source repositories may be renamed; manifests, installed IDs and NRF paths stay stable.
    private val repositories = mapOf(
        "signalisationfrancaiserealiste" to "ab-signalisation-lumineuse",
        "signal-placement" to "ba-signal-placement",
        "time-change" to "bb-timechange",
    )
    fun projectForRepository(repository: String): String =
        repositories.entries.firstOrNull { it.value == repository }?.key ?: repository
    fun githubRepository(project: String): String = repositories[project] ?: project
    fun githubCatalogue(project: String): String {
        require(ProjectRules.identifier.matches(project))
        return "https://github.com/NimbyRails-France/${githubRepository(project)}/releases/download/catalogue/releases.json"
    }
    private val githubCatalogue = Regex("https://github\\.com/NimbyRails-France/([a-z][a-z0-9-]{0,63})/releases/download/catalogue/releases\\.json")
    fun isGithubCatalogue(value: String) = githubCatalogue.matches(value)
    fun sameGithubCatalogue(first: String, second: String): Boolean {
        val left = githubCatalogue.matchEntire(first) ?: return false
        val right = githubCatalogue.matchEntire(second) ?: return false
        return projectForRepository(left.groupValues[1]) == projectForRepository(right.groupValues[1])
    }
    private val asset = Regex("https://releases\\.nimbyrails-france\\.fr/releases/([a-z][a-z0-9-]{0,63})/v([^/]+)/([A-Za-z0-9][A-Za-z0-9._-]{0,180})")
    fun official(value: String): Boolean {
        val match = asset.matchEntire(value) ?: return false
        return Versions.valid(match.groupValues[2]) && match.groupValues[3] !in listOf(".", "..")
    }
    fun forRelease(value: String, project: String, version: String) =
        official(value) && value.startsWith("$origin/releases/$project/v$version/")
    fun page(project: String, version: String) = "$origin/releases/$project/v$version/"

    private val githubAsset = Regex("https://github\\.com/NimbyRails-France/([a-z][a-z0-9-]{0,63})/releases/download/v([^/]+)/([A-Za-z0-9][A-Za-z0-9._-]{0,180})")
    fun github(value: String): Boolean {
        val match = githubAsset.matchEntire(value) ?: return false
        return Versions.valid(match.groupValues[2]) && match.groupValues[3] !in listOf(".", "..")
    }
    fun githubForRelease(value: String, project: String, version: String): Boolean {
        if (!github(value)) return false
        val parts = checkNotNull(githubAsset.matchEntire(value)).groupValues
        return projectForRepository(parts[1]) == projectForRepository(project) && parts[2] == version
    }
    fun sameGithubAsset(first: String, second: String) =
        github(first) && github(second) && githubMirror(first) == githubMirror(second)
    fun githubPage(project: String, version: String) = "https://github.com/NimbyRails-France/${githubRepository(project)}/releases/tag/v$version"
    fun githubDownload(project: String, version: String, name: String) =
        "https://github.com/NimbyRails-France/${githubRepository(project)}/releases/download/v$version/$name"
    fun githubMirror(value: String): String {
        if (github(value)) {
            val parts = checkNotNull(githubAsset.matchEntire(value)).groupValues
            return githubDownload(parts[1], parts[2], parts[3])
        }
        require(official(value))
        val parts = checkNotNull(asset.matchEntire(value)).groupValues
        return githubDownload(parts[1], parts[2], parts[3])
    }
    fun serverMirror(value: String): String {
        if (official(value)) return value
        require(github(value))
        val parts = checkNotNull(githubAsset.matchEntire(value)).groupValues
        return page(projectForRepository(parts[1]), parts[2]) + parts[3]
    }
}
