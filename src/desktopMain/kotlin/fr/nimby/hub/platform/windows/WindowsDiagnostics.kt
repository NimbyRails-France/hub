package fr.nimby.hub.platform.windows

import fr.nimby.hub.model.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
            var output = ""
            val reader = thread(isDaemon = true, name = "diagnostic-inventory") {
                output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly(); reader.join(2000)
                error("Inventory exceeded 90 seconds; no game process was stopped")
            }
            reader.join(2000)
            check(!reader.isAlive && process.exitValue() == 0) { "Inventory failed: ${output.take(4000)}" }
            hubJson.parseToJsonElement(output) // Never mislabel PowerShell error text as valid JSON.
            output
        }.getOrElse { buildJsonObject { put("collectionError", it.toString()) }.toString() }
    }
}
