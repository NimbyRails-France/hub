package fr.nimby.hub.platform.windows

import fr.nimby.hub.model.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Read-only inventory: file/version metadata, OS and selected game modules.
 * Only a read-only PowerShell helper is launched; no game DLL is loaded or injected. No command line or
 * environment secrets are collected. Paths travel as JSON on stdin.
 */
object WindowsDiagnostics {
    fun collect(settings: HubSettings): String {
        val request = buildJsonObject {
            put("game", settings.gameDirectory)
            putJsonArray("projects") {
                (settings.installed.values + settings.activeDevelopment.values).distinctBy { it.directory }.forEach {
                    add(buildJsonObject { put("id", it.id); put("version", it.version); put("directory", it.directory) })
                }
            }
        }
        return runCatching {
            val script = checkNotNull(javaClass.getResourceAsStream("/windows-diagnostics.ps1")).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val result = ReadOnlyProcess.capture(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))),
                request.toString(), timeoutMs = 90_000, maximumBytes = 4 * 1024 * 1024)
            check(result.exitCode == 0) { "Inventory failed: ${result.output.take(4000)}" }
            hubJson.parseToJsonElement(result.output) // Never mislabel PowerShell error text as valid JSON.
            result.output
        }.getOrElse { buildJsonObject { put("collectionError", it.toString()) }.toString() }
    }
}
