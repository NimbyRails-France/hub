package fr.nimby.hub.platform.windows

import java.nio.file.Path

/** Détection positive sans sous-processus pour la surveillance régulière du jeu.
 * Une commande inaccessible ne prouve jamais que le jeu est fermé : l'adaptateur
 * Windows conserve alors sa vérification par nom de processus. Seule l'identité
 * d'un processus identifié est conservée : sa présence est revérifiée à chaque
 * appel. ProcessHandle vérifie aussi sa date de naissance contre le recyclage
 * d'un PID ; aucune absence ni autorisation d'installation n'est mise en cache. */
internal class RunningExecutable {
    private data class Identified(val executable: String, val process: ProcessHandle)
    @Volatile private var observed: Identified? = null

    fun isRunning(executable: Path, fallback: () -> Boolean): Boolean {
        val expected = executable.toAbsolutePath().normalize().toString()
        val found = runCatching {
            val previous = observed
            if (previous != null && previous.executable.equals(expected, ignoreCase = true) && previous.process.isAlive) {
                true
            } else ProcessHandle.allProcesses().use { processes ->
                val process = processes.filter { candidate ->
                    val command = candidate.info().command().orElse(null)
                    command != null && Path.of(command).toAbsolutePath().normalize().toString()
                        .equals(expected, ignoreCase = true) && candidate.isAlive
                }.findFirst().orElse(null)
                observed = process?.takeIf { it.info().startInstant().isPresent }?.let { Identified(expected, it) }
                process != null
            }
        }.getOrDefault(false)
        return found || fallback()
    }
}
