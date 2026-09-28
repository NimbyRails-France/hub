package fr.nimby.hub.install

import fr.nimby.hub.i18n.tr

import fr.nimby.hub.model.*
import fr.nimby.hub.storage.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

object KotlinKits {
    fun validate(directory: Path, version: String) {
        require(LocalProjects.kotlinSdk(directory.toString(), Project("sdk", "sdk", version)) == version) {
            tr("Le kit Kotlin ne correspond pas à la version demandée")
        }
        val metadata = hubJson.parseToJsonElement(directory.resolve("sdk.json").jsonText()).jsonObject
        require(metadata["gradlePluginVersion"]?.jsonPrimitive?.content == version &&
            directory.resolve("gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/$version/fr.nimbyrails.mod.gradle.plugin-$version.pom").isRegularFile()) {
            tr("Le plugin Gradle du kit SDK est absent ou sa version est incorrecte")
        }
    }

    /** Extract into a fresh managed directory. The caller selects it only after
     * validation; failures cannot damage or select over an existing working kit. */
    fun prepare(kit: KotlinKit, archive: Path, directory: Path): Path {
        KotlinKitReleases.validate(kit)
        require(archive.fileSize() == kit.size && archive.sha256().equals(kit.sha256, true)) { tr("Archive du kit : empreinte ou taille incorrecte") }
        require(!directory.exists()) { tr("Le dossier du kit doit être neuf") }
        directory.parent.createDirectories()
        val stage = Files.createTempDirectory(directory.parent, ".kit-stage-")
        try {
            Archives.extract(archive, stage, "")
            validate(stage, kit.version)
            Files.move(stage, directory)
            return directory
        } finally {
            // The extractor rejects links, traversal and duplicate paths. This
            // cleanup concerns only the unique directory created by this call.
            if (stage.exists()) Files.walk(stage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
