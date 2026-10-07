package fr.nimby.hub.install

import fr.nimby.hub.i18n.tr
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.Host
import fr.nimby.hub.storage.jsonText
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*

/** Creates sources only. No compilation, game modification or process execution occurs here. */
object ProjectGenerator {
    private data class Kit(val root: Path, val version: String, val maximum: String, val plugin: String,
        val kotlin: String, val gradle: String, val hashes: List<String>)

    fun create(rootDirectory: Path, kotlinSdkDirectory: Path, request: NewProjectRequest): LocalProject {
        request.validate()
        require(rootDirectory.isAbsolute && kotlinSdkDirectory.isAbsolute) { tr("Choisissez des dossiers avec un chemin complet") }
        val root = rootDirectory.normalize()
        require(root.parent != null) { tr("Choisissez un dossier de projets, pas la racine du disque") }
        checkedAncestors(root)
        val kit = readKit(kotlinSdkDirectory.normalize())
        val destination = root.resolve(request.id)
        require(!destination.startsWith(kit.root) && !kit.root.startsWith(destination)) { tr("Le projet doit être séparé du kit SDK") }
        require(!Files.exists(destination, NOFOLLOW_LINKS)) { tr("Le dossier du projet existe déjà : {0}", destination) }

        // Read all packaged resources and validate all generated inputs before reserving a folder.
        val files = linkedMapOf<String, ByteArray>()
        fun source(path: String, value: String) { files[path] = (value.trimIndent().trimEnd() + "\n").toByteArray(Charsets.UTF_8) }
        listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/LICENSE.txt").forEach { path ->
            files[path] = ProjectGenerator::class.java.getResourceAsStream("/project-template/$path")?.use { it.readBytes() }
                ?: error(tr("Ressource de modèle absente : {0}", path))
        }
        source("settings.gradle.kts", settings(request.id))
        source("build.gradle.kts", "plugins { id(\"fr.nimbyrails.mod\") }")
        source("gradle.properties", "# Local SDK selection. Do not commit this machine-specific file.\nnrfSdkDir=${property(kit.root.toString())}")
        source("gradle/wrapper/gradle-wrapper.properties", """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-${kit.gradle}-bin.zip
            networkTimeout=10000
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """)
        source("mod.json", hubJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("id", request.id); put("name", request.name); put("modId", request.id)
            put("module", "${request.id}-mod"); put("version", request.version); put("language", "kotlin-native")
            put("developmentStatus", "in-development")
            put("sdkMin", kit.version); put("sdkMaxExclusive", kit.maximum)
            putJsonArray("gameSha256") { kit.hashes.forEach { add(it) } }
        }))
        source(".gitignore", ".gradle/\n.kotlin/\n.idea/\nbuild/\ndist/\nout/\ngradle.properties\n*.iml")
        source("README.md", readme(request, kit))
        source("src/main/kotlin/Entry.kt", if (request.template == ProjectTemplate.SIGNAL) signal(request) else tool(request))
        source("src/test/kotlin/ModTests.kt", if (request.template == ProjectTemplate.SIGNAL) signalTests else toolTests)
        if (request.template == ProjectTemplate.SIGNAL) {
            source("assets/closed.svg", svg("#ef4444", 15))
            source("assets/open.svg", svg("#22c55e", 37))
        }

        Files.createDirectories(root)
        checkedAncestors(root)
        Files.createDirectory(destination) // Atomic ownership: never replace an existing file or directory.
        val created = mutableListOf<Path>()
        created.add(destination)
        try {
            files.forEach { (relative, bytes) ->
                val file = destination.resolve(relative)
                val parents = generateSequence(file.parent) { it.parent }.takeWhile { it != destination }.toList().asReversed()
                parents.forEach { parent ->
                    if (!Files.exists(parent, NOFOLLOW_LINKS)) { Files.createDirectory(parent); created.add(parent) }
                }
                checkedAncestors(file.parent)
                Files.newOutputStream(file, CREATE_NEW, WRITE).use { output ->
                    // Track ownership before writing so a disk-full/partial-write failure
                    // still removes our incomplete file during rollback.
                    created.add(file)
                    output.write(bytes)
                }
            }
            if (!Host.windows) require(destination.resolve("gradlew").toFile().setExecutable(true, false)) {
                tr("Impossible de rendre le wrapper Gradle exécutable")
            }
            return LocalProjects.read(destination).also { LocalProjects.kotlinSdk(kit.root.toString(), it.project) }
        } catch (failure: Exception) {
            // Only paths this invocation created, in reverse order. Never recursively remove a
            // pre-existing folder or follow a link introduced during a failed creation.
            created.asReversed().forEach { path ->
                runCatching { checkedAncestors(path.parent); Files.deleteIfExists(path) }
                    .onFailure { failure.addSuppressed(it) }
            }
            throw failure
        }
    }

    private fun checkedAncestors(path: Path) {
        generateSequence(path) { it.parent }.toList().asReversed().forEach { part ->
            if (Files.exists(part, NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(part, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther && part.toRealPath() == part) {
                    tr("Dossier lié ou non valide : {0}", part)
                }
            }
        }
    }

    private fun readKit(root: Path): Kit {
        checkedAncestors(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { tr("Choisissez le kit SDK Kotlin dans les paramètres de développement") }
        require(Files.isRegularFile(root.resolve("sdk.json"), NOFOLLOW_LINKS)) { tr("Métadonnées du kit SDK invalides") }
        val metadata = hubJson.parseToJsonElement(root.resolve("sdk.json").jsonText()).jsonObject
        fun field(key: String) = metadata[key]?.jsonPrimitive?.content ?: error(tr("Kit SDK : champ absent {0}", key))
        val version = field("sdkVersion")
        val plugin = field("gradlePluginVersion")
        val kotlin = field("kotlinVersion")
        val gradle = field("gradleVersion")
        require(metadata["format"]?.jsonPrimitive?.intOrNull == 1 && Versions.valid(version) && Versions.valid(plugin)) { tr("Métadonnées du kit SDK invalides") }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(kotlin) && Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?").matches(gradle)) { tr("Versions des outils du kit SDK invalides") }
        val hashes = metadata["gameSha256"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        require(hashes.isNotEmpty() && hashes.all(ProjectRules.hash::matches)) { tr("Versions du jeu absentes ou invalides") }
        val parts = version.substringBefore('-').split('.').map(String::toInt)
        val maximum = when {
            parts[1] < 9999 -> "${parts[0]}.${parts[1] + 1}.0"
            parts[0] < 9999 -> "${parts[0] + 1}.0.0"
            else -> error(tr("Impossible de déterminer la limite SDK du projet"))
        }
        LocalProjects.kotlinSdk(root.toString(), Project("template", "native-mod", "0.1.0", sdkMin = version, sdkMaxExclusive = maximum))
        val marker = root.resolve("gradle-repository/fr/nimbyrails/mod/fr.nimbyrails.mod.gradle.plugin/$plugin/fr.nimbyrails.mod.gradle.plugin-$plugin.pom")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS)) { tr("Dépôt du plugin Gradle absent du kit SDK") }
        val required = listOf(root.resolve("klib/nimby-mod-api.klib"), root.resolve("bridge/Exports.kt"),
            root.resolve("bin/NimbyKotlinMod.${Host.moduleExtension}"), root.resolve("bin/NimbyRailsFranceSDK.${Host.moduleExtension}"),
            root.resolve("bin/kotlin_loader_test${if (Host.windows) ".exe" else ""}"), marker) +
            if (Host.windows) listOf(root.resolve("bin/libwinpthread-1.dll")) else emptyList()
        required.forEach { file ->
            checkedAncestors(file.parent)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { tr("Kit SDK incomplet : {0}", root.relativize(file)) }
        }
        return Kit(root, version, maximum, plugin, kotlin, gradle, hashes.distinct())
    }

    /** Java Properties consumes backslashes and separators, even in values. */
    private fun property(value: String) = buildString {
        value.forEach { c -> when (c) {
            '\\' -> append("\\\\"); ':' -> append("\\:"); '=' -> append("\\="); ' ' -> append("\\ ")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code > 126 || c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        } }
    }
    private fun quoted(value: String) = JsonPrimitive(value).toString().replace("$", "\\$")

    private fun settings(id: String) = """
        pluginManagement {
            val sdk = providers.gradleProperty("nrfSdkDir")
                .orElse(providers.environmentVariable("NRF_KOTLIN_SDK"))
                .orNull ?: error("Configure nrfSdkDir with the Kotlin SDK kit directory.")
            repositories {
                maven { url = uri(file(sdk).resolve("gradle-repository")) }
                gradlePluginPortal()
                mavenCentral()
            }
            // The plugin, API and native bridge must come from the same SDK kit.
            val metadata = groovy.json.JsonSlurper()
                .parseText(file(sdk).resolve("sdk.json").readText().removePrefix("\uFEFF")) as Map<*, *>
            plugins { id("fr.nimbyrails.mod") version (metadata["gradlePluginVersion"] as String) }
        }
        dependencyResolutionManagement { repositories { mavenCentral() } }
        rootProject.name = ${quoted(id)}
    """

    private fun signal(request: NewProjectRequest) = """
        package nimby.mod

        import nimby.*

        enum class Aspect { Closed, Open }
        enum class Reason { Unknown, Disabled, Occupied, Clear }

        // A missing or stale observation must never grant passage.
        val mainSignal = signalModel(
            id = ${quoted(request.id + ".signal")}, title = ${quoted(request.name)},
            textures = ${quoted(request.id.replace('-', '_') + "_signal")},
            fallback = Indication(Aspect.Closed, Reason.Unknown)
        ) {
            construction(states = listOf("closed.svg", "open.svg"), size = 4, left = true)
            val active = checkbox("active", "Enable signal", defaultValue = true)
            rules {
                when {
                    settingsStatus == SettingsStatus.Unavailable -> Indication(Aspect.Closed, Reason.Unknown)
                    !enabled(active) -> Indication(Aspect.Closed, Reason.Disabled)
                    !fresh || !routeKnown || observation.forcedStop || observation.lampFailed ->
                        Indication(Aspect.Closed, Reason.Unknown)
                    block == Occupancy.Clear -> Indication(Aspect.Open, Reason.Clear)
                    block == Occupancy.Occupied -> Indication(Aspect.Closed, Reason.Occupied)
                    else -> Indication(Aspect.Closed, Reason.Unknown)
                }
            }
            images { if (it.aspect == Aspect.Open) "open.svg" else "closed.svg" }
            driving { if (it.aspect == Aspect.Open) AutomaticDriving.clear() else AutomaticDriving.stop() }
        }

        // createMod is also called by packaging: declare the mod without reading a running game.
        fun createMod(): SignallingMod = signalMod(modInfo) {
            metadata(author = ${quoted(request.author)}, description = ${quoted(request.normalizedDescription)})
            signal(mainSignal)
        }
    """

    private fun tool(request: NewProjectRequest) = """
        package nimby.mod

        import nimby.*

        // Pure presentation can be tested without a running game.
        fun clockMessage(clock: ToolClock): String = "UTC: " + clock.dateTime()

        fun createMod(): ToolMod = toolMod(modInfo) {
            metadata(author = ${quoted(request.author)}, description = ${quoted(request.normalizedDescription)})
            // F8 opens the window while NIMBY Rails has focus. Read only on an explicit event.
            window("main", ${quoted(request.name)}, shortcut = "F8") { event ->
                showWindow(event, clockMessage(clock()), listOf(ToolButton("refresh", "Refresh")))
            }
        }
    """

    private val signalTests = """
        import kotlin.test.*
        import nimby.*
        import nimby.mod.*

        class ModTests {
            @Test fun opensOnlyForKnownClearObservation() {
                val mod = createMod()
                fun aspect(observation: Observation) = assertNotNull(mod.indication(
                    mod.evaluate(mapOf("active" to true), observation))?.of(mainSignal)).aspect
                val clear = Observation(block = Occupancy.Clear, fresh = true, routeKnown = true)
                assertEquals(Aspect.Open, aspect(clear))
                assertEquals(Aspect.Closed, aspect(clear.copy(block = Occupancy.Unknown)))
                assertEquals(Aspect.Closed, aspect(clear.copy(block = Occupancy.Occupied)))
                assertEquals(Aspect.Closed, aspect(clear.copy(fresh = false)))
                assertEquals(Aspect.Closed, aspect(clear.copy(routeKnown = false)))
                assertEquals(Aspect.Closed, aspect(clear.copy(forcedStop = true)))
                assertEquals(Aspect.Closed, aspect(clear.copy(lampFailed = true)))
            }
        }
    """
    private val toolTests = """
        import kotlin.test.*
        import nimby.ToolClock
        import nimby.mod.clockMessage
        import nimby.mod.createMod

        class ModTests {
            @Test fun formatsSimulationTimeRatherThanElapsedTime() {
                val first = clockMessage(ToolClock(0, 5000))
                assertEquals(first, clockMessage(ToolClock(0, 9000)))
                assertNotEquals(first, clockMessage(ToolClock(60, 5000)))
            }
            @Test fun declaresAnIndependentWindowWithoutSignalsOrLiveReads() {
                val mod = createMod()
                assertEquals(listOf("main"), mod.windows.map { it.id })
                assertEquals("F8", mod.windows.single().shortcut)
            }
        }
    """
    private fun svg(color: String, y: Int) = """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="64" viewBox="0 0 32 64"><rect x="6" y="2" width="20" height="48" rx="10" fill="#161616"/><circle cx="16" cy="$y" r="7" fill="$color"/><path d="M16 50v14" stroke="#888" stroke-width="4"/></svg>"""

    private fun readme(request: NewProjectRequest, kit: Kit) = """
        # ${request.name}

        ${request.normalizedDescription.replace("\n", "\n        ")}

        ## Développement / Development

        Projet Kotlin/Native créé par NRF Hub. Les sources sont dans `src/main/kotlin` et les tests dans `src/test/kotlin`.
        Kotlin/Native project created by NRF Hub. Sources are in `src/main/kotlin` and tests in `src/test/kotlin`.

        `mod.json` déclare `developmentStatus` avec la valeur `in-development` pour ce nouveau projet.
        Ce statut est affiché par le Hub, indépendamment du canal de version. Choisissez `stable` lorsque le projet est prêt, ou retirez le champ pour ne pas afficher de badge.
        `mod.json` declares `developmentStatus` as `in-development` for this new project.
        The Hub displays this status independently from the release channel. Choose `stable` when the project is ready, or remove the field to show no badge.

        - SDK : ${kit.version}; plugin : ${kit.plugin}; Kotlin : ${kit.kotlin}; Gradle : ${kit.gradle}.
        - `gradle.properties` choisit le kit local et reste exclu de Git. Chaque développeur renseigne son propre `nrfSdkDir`.
        - `gradle.properties` selects the local kit and is ignored by Git. Each developer configures their own `nrfSdkDir`.
        - Le Hub passe explicitement le kit choisi à Gradle. Hors du Hub, un `nrfSdkDir` dans le `gradle.properties` utilisateur peut prendre priorité sur celui du projet : utilisez `-PnrfSdkDir=chemin-du-kit` pour choisir explicitement.
        - The Hub passes the selected kit explicitly to Gradle. Outside the Hub, a user-level `gradle.properties` can override this project's `nrfSdkDir`: use `-PnrfSdkDir=kit-directory` to select it explicitly.
        - Le plugin est relu depuis le kit choisi à chaque configuration Gradle. Ne mélangez pas API, plugin et bibliothèque de kits différents.
        - The plugin is read from the selected kit at Gradle configuration time. Keep API, plugin and native library from the same kit.

        Sous Windows / On Windows:

        ```powershell
        .\gradlew.bat windowsTest
        .\gradlew.bat verifyNativeMod
        .\gradlew.bat packageMod
        ```

        Sous Linux, utilisez `./gradlew linuxTest`, `./gradlew verifyNativeMod`, puis `./gradlew packageMod` avec un kit Linux compatible.
        On Linux, use those Linux commands with a compatible Linux kit. The first build downloads Gradle and compiler dependencies; Java 21 is required.

        `packageMod` teste, vérifie et crée l’archive dans `build/gradle/distributions` sous Windows (`build/gradle-linux/distributions` sous Linux).
        `packageMod` tests, verifies and creates the archive. No command above installs or publishes the mod.

        Le modèle ${if (request.template == ProjectTemplate.SIGNAL) "signal ferme si l’observation n’est pas fiable ; il est placé à gauche, taille 4" else "outil ouvre une fenêtre avec F8 et lit l’horloge uniquement à l’ouverture ou au clic Actualiser"}.
        The ${if (request.template == ProjectTemplate.SIGNAL) "signal template closes on unreliable observations and uses left placement, size 4" else "tool template opens with F8 and reads the clock only on open or Refresh"}.
        Déclarez les règles de votre système et testez-les sur une partie d’essai avant toute publication.
        Implement your system’s rules and test them on a test save before publication.

        ## Compatibilité / Compatibility

        `mod.json` reprend les empreintes du jeu et la version minimale du kit sélectionné.
        La borne supérieure initiale `${kit.maximum}` exclue est une plage de développement ; testez les versions annoncées avant de publier.
        `mod.json` copies supported game hashes and the selected kit version as its minimum.
        The initial exclusive upper bound `${kit.maximum}` is a development range; test any SDK versions you claim before publishing.

        ## Licences / Licenses

        Le wrapper Gradle conserve ses notices ; sa licence est dans `gradle/LICENSE.txt`.
        The Gradle wrapper retains its notices; its license is in `gradle/LICENSE.txt`.
        Choisissez une licence pour votre code avant publication. No license is assigned to your own code by this template.
    """
}
