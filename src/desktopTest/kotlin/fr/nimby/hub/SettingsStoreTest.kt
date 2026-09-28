package fr.nimby.hub

import fr.nimby.hub.model.*
import fr.nimby.hub.storage.SettingsStore
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class SettingsStoreTest {
    @Test fun qtNullPlaceholdersMeanUninstalledAndPreservePreferences() {
        val directory = Files.createTempDirectory("nrf-qt-profile-")
        val original = """{
            "automatic":false,"developerMode":true,
            "channels":{"hub":"alpha","sdk":"stable"},
            "gameDirectory":"D:/Steam/NIMBY Rails","root":"C:/existing/mods","geometry":"Qt-window-state",
            "installed":{"sdk":null,"signalisationfrancaiserealiste":null,"tco":null},
            "notified":{"sdk":"0.7.2"}
        }"""
        val file = directory.resolve("settings.json")
        file.writeText(original)
        val store = SettingsStore(directory)
        val settings = store.read()
        assertTrue(settings.installed.isEmpty())
        assertFalse(settings.automatic)
        assertTrue(settings.developerMode && settings.legacyProtection)
        assertEquals("alpha", settings.channels["hub"])
        assertEquals("stable", settings.channels["sdk"])
        assertEquals("D:/Steam/NIMBY Rails", settings.gameDirectory)
        assertEquals("C:/existing/mods", settings.root)
        assertEquals("Qt-window-state", settings.geometry)
        assertEquals("0.7.2", settings.notified["sdk"])
        assertEquals(original, file.readText())
        assertEquals(original, directory.resolve("settings.before-profiles.json").readText())
        store.write(settings)
        assertEquals(settings, store.read())
        assertEquals(original, directory.resolve("settings.before-profiles.json").readText())
    }

    @Test fun installedRecordsSurviveAlongsideNullPlaceholders() {
        // Also accept a previously migrated profile containing Qt placeholders.
        val directory = Files.createTempDirectory("nrf-mixed-profile-")
        val original = """{"schema":2,"installed":{"sdk":null,"tco":null,
            "signalisationfrancaiserealiste":{"id":"signalisationfrancaiserealiste","kind":"native-mod",
            "version":"0.1.0","directory":"C:/mods/sfr","name":"SFR","loaderApi":1,
            "module":"SignalisationFrancaiseRealisteMod.dll","modId":null,"origin":"unknown",
            "modLink":"C:/Saved Games/mods/SignalisationFrancaiseRealiste",
            "loaderLink":"D:/NIMBY Rails/NRFMods/signalisationfrancaiserealiste",
            "sdkMin":"0.7.3","sdkMaxExclusive":"0.8.0","installedUtc":"2026-09-20T23:28:20Z",
            "gameSha256":["${"a".repeat(64)}"],"platform":"windows-x64"}}}"""
        directory.resolve("settings.json").writeText(original)
        val store = SettingsStore(directory)
        val settings = store.read()
        assertEquals(setOf("signalisationfrancaiserealiste"), settings.installed.keys)
        val expected = InstalledProject("signalisationfrancaiserealiste", "native-mod", "0.1.0", "C:/mods/sfr",
            name = "SFR", gameSha256 = listOf("a".repeat(64)), sdkMin = "0.7.3", sdkMaxExclusive = "0.8.0",
            loaderApi = 1, module = "SignalisationFrancaiseRealisteMod.dll",
            modLink = "C:/Saved Games/mods/SignalisationFrancaiseRealiste",
            loaderLink = "D:/NIMBY Rails/NRFMods/signalisationfrancaiserealiste", installedUtc = "2026-09-20T23:28:20Z")
        assertEquals(expected, settings.installed.getValue(expected.id))
        assertEquals("SignalisationFrancaiseRealiste", settings.installed.getValue(expected.id).asProject().modId)
        store.write(settings)
        assertEquals(settings, store.read())
        assertEquals(original, directory.resolve("settings.before-profiles.json").readText())
    }

    @Test fun invalidRecordsAreNeverDiscardedToMakeMigrationSucceed() {
        val directory = Files.createTempDirectory("nrf-invalid-profile-")
        val file = directory.resolve("settings.json")
        val store = SettingsStore(directory)
        for (record in listOf("42", "\"broken\"", "{}",
            """{"id":"tco","kind":"sdk","version":"0.7.3","directory":"C:/sdk"}""")) {
            val original = """{"installed":{"sdk":$record,"tco":null}}"""
            file.writeText(original)
            assertFails { store.read() }
            assertEquals(original, file.readText())
            assertFalse(directory.resolve("settings.before-profiles.json").exists())
        }
    }
}
