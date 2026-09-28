package fr.nimby.hub.network

import fr.nimby.hub.model.*
import java.nio.file.Path

interface ReleaseSource {
    suspend fun catalogue(channels: Map<String, String> = emptyMap()): Catalogue
    suspend fun project(id: String, channel: String = "stable"): Project
    suspend fun hub(channel: String = "stable"): HubRelease
    suspend fun download(url: String, size: Long, hash: String, destination: Path)
    suspend fun listen(onEvent: suspend () -> Unit)
    suspend fun sdkVersions(channel: String): List<Project> = listOf(project("sdk", channel))
    suspend fun kotlinKits(channel: String): List<KotlinKit> = emptyList()
}

