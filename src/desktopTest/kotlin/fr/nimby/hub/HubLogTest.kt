package fr.nimby.hub

import fr.nimby.hub.storage.HubLog
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class HubLogTest {
    @Test fun survivesRestartWithUnicodeAndCompleteFailureChain() {
        val root = Files.createTempDirectory("nrf-log-")
        val log = HubLog(root)
        val failure = IllegalStateException("Installation échouée", IllegalArgumentException("DLL déjà présente"))
        failure.addSuppressed(Exception("Restauration interrompue"))
        assertNull(log.append("Réparation · démarrée", failure))
        assertNull(HubLog(root).append("Session suivante"))
        val text = log.file.readText()
        listOf("Réparation", "Installation échouée", "DLL déjà présente", "Suppressed:", "Caused by:", "Session suivante").forEach { assertContains(text, it) }
    }

    @Test fun rotatesAndSurfacesUnwritableDirectoryWithoutThrowing() {
        val root = Files.createTempDirectory("nrf-log-rotation-")
        val log = HubLog(root, maximumBytes = 60, archives = 2)
        repeat(8) { assertNull(log.append("Message numéro $it")) }
        assertTrue(root.resolve("hub.log.2").exists())
        assertFalse(root.resolve("hub.log.3").exists())
        assertContains(log.file.readText(), "numéro 7")
        val blocked = root.resolve("file-instead-of-directory").apply { writeText("keep") }
        assertNotNull(HubLog(blocked).append("failure"))
        assertEquals("keep", blocked.readText())
    }
}
