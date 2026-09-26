package fr.nimby.hub

import fr.nimby.hub.model.HubSettings
import fr.nimby.hub.model.hubJson
import fr.nimby.hub.platform.Host
import fr.nimby.hub.platform.windows.WindowsDiagnostics
import fr.nimby.hub.storage.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class WindowsDiagnosticsTest {
    @Test fun inventoryReportsBuildHashesAndUnicodePathsWithoutReadingSavesOrSteamOwner() {
        if (!Host.windows) return
        val root = Files.createTempDirectory("nrf-inventory-é-'")
        val steamapps = root.resolve("steamapps").createDirectory()
        val game = steamapps.resolve("common/Diagnostic Game").createDirectories()
        game.resolve("NIMBYRails.exe").writeText("fixture, never execute")
        val dll = game.resolve("test.dll").apply { writeText("fixture, never load") }
        game.resolve("private.save").writeText("PRIVATE_SAVE")
        steamapps.resolve("appmanifest_123.acf").writeText("\"installdir\" \"Diagnostic Game\"\n\"appid\" \"123\"\n\"buildid\" \"456\"\n\"LastOwner\" \"PRIVATE_OWNER\"")
        val result = WindowsDiagnostics.collect(HubSettings(gameDirectory = game.toString()))
        val report = hubJson.parseToJsonElement(result).jsonObject
        assertNull(report["collectionError"], result)
        assertTrue(report.getValue("os").jsonObject.getValue("build").jsonPrimitive.content.isNotBlank())
        assertEquals("456", report.getValue("steam").jsonObject.getValue("buildid").jsonPrimitive.content)
        val files = report.getValue("files").jsonArray.map { it.jsonObject }
        assertEquals(2, files.size)
        val entry = files.single { it.getValue("path").jsonPrimitive.content == dll.toString() }
        assertEquals(dll.sha256().uppercase(), entry.getValue("sha256").jsonPrimitive.content.uppercase())
        assertFalse(result.contains("PRIVATE_OWNER"))
        assertFalse(result.contains("private.save"))
        assertEquals(0, report.getValue("gameProcesses").jsonArray.size)
    }
}
