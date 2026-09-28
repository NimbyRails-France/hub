package fr.nimby.hub.platform.windows

import fr.nimby.hub.storage.sha256
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Local development can rebuild an alpha without changing its version number.
 * A mod then imports exports absent from the older DLL with the same version.
 * Compare the actual runtime before touching the game or switching its profile.
 * This deliberately requires the same Windows SDK build as the compilation kit. */
object WindowsSdkBuildMatch {
    fun requireSameBuild(kit: Path, distribution: Path) {
        val compiled = kit.resolve("bin/NimbyRailsFranceSDK.dll")
        val installed = distribution.resolve("loader/NimbyRailsFranceSDK.dll")
        require(compiled.isRegularFile() && installed.isRegularFile()) {
            "SDK local incomplet : DLL du kit ou du paquet de jeu absente."
        }
        require(compiled.sha256() == installed.sha256()) {
            "Le SDK du jeu et le kit Kotlin proviennent de builds différents, même si leur version est identique. " +
                "Importez le SDK local correspondant dans SDK pour les essais avant de lancer le jeu."
        }
    }
}
