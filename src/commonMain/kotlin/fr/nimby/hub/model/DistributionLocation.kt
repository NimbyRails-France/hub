package fr.nimby.hub.model

/** Public distribution trust boundary. No credentials or GitHub API in clients. */
object DistributionLocation {
    const val origin = "https://releases.nimbyrails-france.fr"
    const val catalogue = "$origin/v1/catalog.json"
    private val asset = Regex("https://releases\\.nimbyrails-france\\.fr/releases/([a-z][a-z0-9-]{0,63})/v([^/]+)/([A-Za-z0-9][A-Za-z0-9._-]{0,180})")
    fun official(value: String): Boolean {
        val match = asset.matchEntire(value) ?: return false
        return Versions.valid(match.groupValues[2]) && match.groupValues[3] !in listOf(".", "..")
    }
    fun forRelease(value: String, project: String, version: String) =
        official(value) && value.startsWith("$origin/releases/$project/v$version/")
    fun page(project: String, version: String) = "$origin/releases/$project/v$version/"
}
