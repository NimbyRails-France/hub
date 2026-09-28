package fr.nimby.hub.model

/** Official download locations. Mirroring preserves project, version and filename. */
object DistributionLocation {
    const val origin = "https://releases.nimbyrails-france.fr"
    const val catalogue = "$origin/v1/catalog.json"
    val projects = setOf("sdk", "hub", "tco", "signalisationfrancaiserealiste", "signal-placement", "time-change")
    fun githubCatalogue(project: String): String {
        require(ProjectRules.identifier.matches(project))
        return "https://github.com/NimbyRails-France/$project/releases/download/catalogue/releases.json"
    }
    fun isGithubCatalogue(value: String) = Regex("https://github\\.com/NimbyRails-France/[a-z][a-z0-9-]{0,63}/releases/download/catalogue/releases\\.json").matches(value)
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
    fun githubPage(project: String, version: String) = "https://github.com/NimbyRails-France/$project/releases/tag/v$version"
    fun githubDownload(project: String, version: String, name: String) =
        "https://github.com/NimbyRails-France/$project/releases/download/v$version/$name"
    fun githubMirror(value: String): String {
        if (github(value)) return value
        require(official(value))
        val parts = checkNotNull(asset.matchEntire(value)).groupValues
        return githubDownload(parts[1], parts[2], parts[3])
    }
    fun serverMirror(value: String): String {
        if (official(value)) return value
        require(github(value))
        val parts = checkNotNull(githubAsset.matchEntire(value)).groupValues
        return page(parts[1], parts[2]) + parts[3]
    }
}
