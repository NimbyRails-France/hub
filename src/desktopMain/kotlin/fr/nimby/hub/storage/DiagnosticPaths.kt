package fr.nimby.hub.storage

import java.nio.file.Path

/** Shared on-disk convention with the SDK, mods and TCO. */
object DiagnosticPaths {
    fun root(): Path = System.getenv("NRF_LOG_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "NimbyRailsFrance", "logs")
    fun hub(): Path = root().resolve("hub")
}
