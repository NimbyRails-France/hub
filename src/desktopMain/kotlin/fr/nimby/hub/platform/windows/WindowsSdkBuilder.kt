package fr.nimby.hub.platform.windows

import fr.nimby.hub.i18n.tr

import fr.nimby.hub.install.LocalProjects
import fr.nimby.hub.install.KotlinKits
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.*

/** Windows toolchain orchestration stays in the SDK source repository. The Hub
 * accepts only a fresh receipt after a successful process and verifies its outputs. */
object WindowsSdkBuilder {
    @Serializable data class Receipt(val project: String, val archive: String, val kotlinKit: String)

    suspend fun build(local: LocalProject, output: (String) -> Unit): LocalProjects.Built {
        require(Host.windows && local.buildsSdk && local.project.id == "sdk")
        val root = Path(local.directory)
        val parent = LocalProjects.inside(root, "install/hub").createDirectories()
        val destination = Files.createTempDirectory(parent, "sdk-")
        val script = LocalProjects.inside(root, "tools/windows/build-for-hub.ps1")
        output(tr("SDK {0} · construction locale dans {1}", local.project.version, destination))
        LocalProjects.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.toString(), "-OutputDirectory", destination.toString()), root, tr("La compilation SDK"), output)
        return readResult(local, destination)
    }

    internal fun readResult(local: LocalProject, destination: java.nio.file.Path): LocalProjects.Built {
        val receipt = hubJson.decodeFromString<Receipt>(destination.resolve("hub-result.json").jsonText())
        val manifest = LocalProjects.inside(destination, receipt.project)
        val project = hubJson.decodeFromString<Project>(manifest.jsonText())
        ProjectRules.validate(project, remote = false)
        require(project.id == "sdk" && project.kind == "sdk" && project.platform == "windows-x64" &&
            project.version == local.project.version && project.rootFolder == local.project.rootFolder &&
            project.gameSha256 == local.project.gameSha256 && project.loaderApi == 1) { tr("Le paquet SDK ne correspond pas aux sources choisies") }
        val archive = LocalProjects.inside(destination, receipt.archive)
        require(archive.isRegularFile() && archive.fileSize() == project.size && archive.sha256().equals(project.sha256, true)) {
            tr("Le paquet SDK produit est absent ou son empreinte a changé")
        }
        val kit = LocalProjects.inside(destination, receipt.kotlinKit)
        KotlinKits.validate(kit, project.version)
        return LocalProjects.Built(project, archive, kit)
    }
}
