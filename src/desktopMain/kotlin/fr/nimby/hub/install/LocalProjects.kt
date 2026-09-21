package fr.nimby.hub.install

import fr.nimby.hub.model.*
import fr.nimby.hub.storage.*
import fr.nimby.hub.platform.Host
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** An optional descriptor declares outputs; the Hub never searches for an arbitrary DLL. */
object LocalProjects {
    @Serializable data class Descriptor(val manifest: String = "dist/project.json", val task: String = "", val archive: String = "")

    /** A source manifest has no archive hash yet. Build computes it before installation. */
    private fun sourceProject(mod: JsonObject): Project {
        fun field(name: String) = mod[name]?.jsonPrimitive?.content ?: error("mod.json : $name requis")
        val version = field("version")
        val modId = field("modId")
        val module = field("module")
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,99}").matches(module)) { "Module Kotlin invalide" }
        return Project(id = field("id"), name = field("name"), kind = "native-mod", version = version,
            modId = modId, module = "$module.${Host.moduleExtension}", loaderApi = 1, rootFolder = "$modId-$version",
            platform = Host.id, url = "$modId-$version-${Host.id}.zip", sdkMin = field("sdkMin"), sdkMaxExclusive = field("sdkMaxExclusive"),
            gameSha256 = mod["gameSha256"]?.jsonArray?.map { it.jsonPrimitive.content } ?: error("mod.json : gameSha256 requis"))
    }

    fun inside(root: Path, relative: String): Path {
        val base = root.toAbsolutePath().normalize()
        val path = base.resolve(relative).normalize()
        require(!Path(relative).isAbsolute && path.startsWith(base) && path != base) { "Chemin local hors du projet" }
        if (path.exists()) require(path.toRealPath().startsWith(base.toRealPath())) { "Un lien sort du projet" }
        return path
    }

    fun read(directory: Path, catalogue: List<Project> = emptyList()): LocalProject {
        val root = directory.toRealPath()
        val descriptorPath = root.resolve("hub-local.json")
        val descriptor = if (descriptorPath.isRegularFile()) hubJson.decodeFromString<Descriptor>(descriptorPath.jsonText()) else null
        val manifest = if (descriptor != null) inside(root, descriptor.manifest) else
            listOf(root.resolve("project.json"), root.resolve("dist/project.json")).firstOrNull { it.isRegularFile() }
        val mod = root.resolve("mod.json").takeIf { it.isRegularFile() }?.let { hubJson.parseToJsonElement(it.jsonText()).jsonObject }
        val id = mod?.get("id")?.jsonPrimitive?.content
        val source = if (descriptor == null && mod?.get("language")?.jsonPrimitive?.content == "kotlin-native" && mod.containsKey("modId")) sourceProject(mod) else null
        val template = source ?: manifest?.let { hubJson.decodeFromString<Project>(it.jsonText()) }
            ?: catalogue.firstOrNull { it.id == id }
            ?: error("Manifeste project.json introuvable. Ajoutez le manifeste de distribution ou un hub-local.json qui le référence.")
        var project = template
        if (mod != null) {
            require(template.id == id) { "mod.json et project.json désignent des projets différents" }
            val version = mod.getValue("version").jsonPrimitive.content
            project = template.copy(version = version, rootFolder = template.rootFolder.replace(template.version, version),
                module = mod["module"]?.jsonPrimitive?.content?.let { "$it.${Host.moduleExtension}" } ?: template.module,
                sdkMin = mod["sdkMin"]?.jsonPrimitive?.content ?: template.sdkMin,
                sdkMaxExclusive = mod["sdkMaxExclusive"]?.jsonPrimitive?.content ?: template.sdkMaxExclusive)
        }
        ProjectRules.validate(project, remote = false, requireArtifact = source == null)
        val gradle = root.resolve("gradle/wrapper/gradle-wrapper.jar").isRegularFile()
        val task = descriptor?.task ?: if (gradle && mod?.get("language")?.jsonPrimitive?.content == "kotlin-native") "packageMod" else ""
        require(task.isBlank() || Regex("[A-Za-z][A-Za-z0-9:]*").matches(task)) { "Tâche Gradle invalide" }
        val fileName = template.url.substringAfterLast('/').replace(template.version, project.version)
        val archive = descriptor?.archive?.replace("{version}", project.version)?.takeIf { it.isNotBlank() }
            ?: if (task == "packageMod") "build/${if (Host.windows) "gradle" else "gradle-linux"}/distributions/$fileName" else "dist/$fileName"
        inside(root, archive)
        return LocalProject(project, root.toString(), if (source != null) root.resolve("mod.json").toString() else manifest?.toString().orEmpty(), task, archive)
    }

    fun kotlinSdk(directory: String, project: Project): String {
        require(directory.isNotBlank()) { "Choisissez le kit SDK Kotlin dans les paramètres de développement" }
        val root = Path(directory).toRealPath()
        val metadata = hubJson.parseToJsonElement(root.resolve("sdk.json").jsonText()).jsonObject
        val target = when (Host.id) {
            "windows-x64" -> "mingw_x64"
            "linux-x64" -> "linux_x64"
            else -> error("Le SDK natif du jeu ne prend pas en charge ${Host.id}")
        }
        require(metadata["format"]?.jsonPrimitive?.intOrNull == 1 && metadata["target"]?.jsonPrimitive?.content == target) { "Kit SDK Kotlin incompatible avec ${Host.id}" }
        val version = metadata.getValue("sdkVersion").jsonPrimitive.content
        require(Versions.valid(version)) { "Version du kit SDK invalide" }
        if (project.sdkMin != null) require(Versions.compare(version, project.sdkMin) >= 0 && Versions.compare(version, project.sdkMaxExclusive!!) < 0) { "Kit SDK $version incompatible avec ${project.name}" }
        listOf("klib/nimby-mod-api.klib", "bridge/Exports.kt", "bin/NimbyKotlinMod.${Host.moduleExtension}",
            "bin/NimbyRailsFranceSDK.${Host.moduleExtension}", "bin/kotlin_loader_test${if (Host.windows) ".exe" else ""}").forEach {
            require(root.resolve(it).isRegularFile()) { "Kit SDK incomplet : $it" }
        }
        return version
    }

    fun fingerprint(local: LocalProject, sdk: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val excluded = setOf("build", "dist", ".git", ".gradle", ".kotlin", ".idea", "out", "install") +
            if (local.project.kind == "native-mod" && local.task == "packageMod") setOf("tools", "local-notes", "captures") else emptySet()
        fun collect(root: Path, source: Boolean) {
            val files = mutableListOf<Path>()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): FileVisitResult =
                    if (source && dir != root && dir.fileName.toString() in excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !attrs.isOther) { "Lien non pris en charge dans le projet ou le SDK : $file" }
                    if (attrs.isRegularFile) files.add(file)
                    return FileVisitResult.CONTINUE
                }
            })
            files.sortedBy { root.relativize(it).toString() }.forEach { file ->
                digest.update(root.relativize(file).toString().toByteArray())
                digest.update(file.sha256().toByteArray())
            }
        }
        collect(Path(local.directory), true)
        if (sdk.isNotBlank()) collect(Path(sdk), false)
        digest.update(hubJson.encodeToString(LocalProject.serializer(), local).toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun build(local: LocalProject, sdk: String, output: (String) -> Unit): Pair<Project, Path> {
        require(local.task.isNotBlank()) { "Ce projet ne déclare pas de tâche Gradle. Compilez-le dans votre IDE puis importez son paquet local." }
        val root = Path(local.directory)
        val wrapper = root.resolve("gradle/wrapper/gradle-wrapper.jar")
        require(wrapper.isRegularFile()) { "Wrapper Gradle absent" }
        if (local.project.kind == "native-mod") kotlinSdk(sdk, local.project)
        val archive = inside(root, local.archive)
        val javaRoot = System.getenv("JAVA_HOME")?.takeIf { Path(it, "bin", if (Host.windows) "java.exe" else "java").isRegularFile() } ?: System.getProperty("java.home")
        val java = Path(javaRoot, "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val command = mutableListOf(java.toString(), "-classpath", wrapper.toString(), "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--no-daemon", local.task)
        if (sdk.isNotBlank()) command += "-PnrfSdkDir=$sdk"
        // Process arguments are passed directly. A path is never interpolated into shell code.
        val process = withContext(Dispatchers.IO) { ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start() }
        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { reader ->
                    var count = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = runInterruptible { reader.readLine() } ?: break
                        if (++count <= 100_000) output(line.take(2000))
                    }
                }
                check(runInterruptible { process.waitFor() } == 0) { "Gradle a échoué. Consultez le journal de compilation." }
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroy() }
                process.destroy()
                withContext(NonCancellable + Dispatchers.IO) { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
        }
        require(archive.isRegularFile()) { "Gradle a terminé mais le paquet déclaré est absent : $archive" }
        val project = local.project.copy(size = archive.fileSize(), sha256 = archive.sha256())
        ProjectRules.validate(project, remote = false)
        return project to archive
    }
}
