package fr.nimby.hub

import fr.nimby.hub.i18n.*

import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.network.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import fr.nimby.hub.update.SelfUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.LocalTime
import java.util.UUID
import kotlin.io.path.*

class HubController(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    source: ReleaseSource? = null,
    private val notify: (String, String) -> Unit = { _, _ -> },
    private val journal: HubLog = HubLog(DiagnosticPaths.hub()),
    private val windows: DesktopPlatform = desktopPlatform { journal.append(it) },
    selfUpdater: SelfUpdater? = null,
) {
    private val source: ReleaseSource = source ?: ResilientReleases(report = { journal.append(it) })
    val logDirectory: Path get() = journal.directory
    fun exportLogs(path: Path) = work(message("Export des journaux NRF…")) {
        val s = state.value.settings
        val environment = buildString {
            appendLine("NRF Hub $HUB_VERSION / ${java.time.Instant.now()}")
            appendLine("OS=${System.getProperty("os.name")} ${System.getProperty("os.arch")} Java=${System.getProperty("java.version")}")
            appendLine("Profile=${s.appliedProfile} gameSHA256=${state.value.gameHash}")
            s.installed.values.forEach { appendLine("Installed: ${it.id} ${it.version} origin=${it.origin}") }
            s.activeDevelopment.values.forEach { appendLine("Development: ${it.id} ${it.version} origin=${it.origin}") }
        }
        val count = withContext(Dispatchers.IO) {
            val technical = if (Host.windows) fr.nimby.hub.platform.windows.WindowsDiagnostics.collect(s)
                else null
            DiagnosticBundle.export(path, DiagnosticBundle.roots(store.directory), environment, technical)
        }
        log(message("{0} fichiers de diagnostic exportés : {1}", count, path))
    }
    private val initial = store.read().also { I18n.configure(it.language, java.util.Locale.getDefault().toLanguageTag()) }
    private val policy = UpdatePolicy(initial.developerMode || initial.legacyProtection, initial.automatic)
    private fun kitVersion(path: String) = runCatching { LocalProjects.kotlinSdk(path, Project("sdk", "sdk", "0.0.0")) }.getOrDefault("")
    private val mutable = MutableStateFlow(HubState(initial, windows = windows.supported, logFile = journal.file.toString(),
        kotlinKitVersion = kitVersion(initial.paths.kotlinSdk)))
    val state: StateFlow<HubState> = mutable.asStateFlow()
    private var synchronization: Job? = null
    private var relay: Job? = null
    private var periodic: Job? = null
    private var gameMonitor: Job? = null
    private var operation: Job? = null
    private var gameCheckGeneration = 0L
    private val updater = selfUpdater ?: SelfUpdater(store.directory, this.source)

    private fun update(transform: (HubState) -> HubState) { mutable.update(transform) }
    private fun log(text: String, failure: Throwable? = null) = log(UiText(text, literal = true), failure)
    private fun log(message: UiText, failure: Throwable? = null) {
        val text = message.text
        val warning = journal.append(text, failure)
        update { it.copy(statusMessage = message, log = (it.log + listOfNotNull("${LocalTime.now().withNano(0)}  $text", warning)).takeLast(500)) }
    }
    private fun requireRepairFinished() {
        require(!store.directory.resolve(fr.nimby.hub.platform.windows.WindowsSdkRepair.JOURNAL).exists()) {
            tr("Une réparation SDK est interrompue. Reprenez Réparer le chargeur SDK dans Paramètres.")
        }
    }
    fun repairSdk() = work(message("Vérification et réparation du chargeur SDK…")) {
        require(Host.windows) { tr("Réparation disponible sous Windows uniquement") }
        require(!store.directory.resolve("profile-activation.json").exists()) { tr("Restaurez d'abord l'activation interrompue") }
        val game = Path(state.value.settings.gameDirectory)
        require(state.value.settings.gameDirectory.isNotBlank()) { tr("Choisissez le dossier du jeu") }
        update { it.copy(installing = true) }
        withContext(NonCancellable + Dispatchers.IO) {
            fr.nimby.hub.platform.windows.WindowsSdkRepair(store.directory, { windows.requireClosed(it, it) }, { log(it) }).repair(game)
        }
    }

    private fun settings(value: HubSettings) {
        store.write(value)
        val version = if (value.paths.kotlinSdk != state.value.settings.paths.kotlinSdk) kitVersion(value.paths.kotlinSdk) else state.value.kotlinKitVersion
        update { it.copy(settings = value, kotlinKitVersion = version) }
    }
    fun changeLanguage(choice: String) {
        val normalized = I18n.normalize(choice)
        // Commit first: a write failure must not pretend the preference was saved.
        settings(state.value.settings.copy(language = normalized))
        I18n.choose(normalized)
    }
    fun start() {
        log(message("Démarrage du Hub {0} · journal : {1}", HUB_VERSION, journal.file))
        log(message("Environnement : OS={0} {1} {2} Java={3} · jeu={4} · profil={5}", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"), System.getProperty("java.version"), state.value.settings.gameDirectory, state.value.settings.appliedProfile))
        state.value.settings.installed.values.forEach { log(message("Projet installé : {0} {1} · {2}", it.id, it.version, it.directory)) }
        state.value.settings.activeDevelopment.values.forEach { log(message("Projet de développement actif : {0} {1} · {2}", it.id, it.version, it.directory)) }
        if (store.directory.resolve(fr.nimby.hub.platform.windows.WindowsSdkRepair.JOURNAL).exists())
            log(message("Réparation SDK interrompue : reprenez Réparer le chargeur SDK dans Paramètres."))
        update { it.copy(recoveryRequired = store.directory.resolve("profile-activation.json").exists()) }
        val steamGame = Host.defaultGame()
        if (state.value.windows && state.value.settings.gameDirectory.isBlank() && Host.game(steamGame).isRegularFile()) {
            settings(state.value.settings.copy(gameDirectory = steamGame.toString()))
        }
        val cache = store.directory.resolve("catalog-cache.json")
        if (cache.exists()) runCatching { hubJson.decodeFromString<Catalogue>(cache.jsonText()).also { catalog ->
            require(catalog.schema == 1); catalog.projects.forEach { ProjectRules.validate(it) }
        } }.onSuccess { catalogue -> update { it.copy(projects = catalogue.projects) } }.onFailure { log(message("Catalogue enregistré illisible : {0}", it.message)) }
        scope.launch { identifyGame() }
        resumeNetwork()
        if (state.value.windows) gameMonitor = scope.launch {
            while (isActive) {
                val path = state.value.settings.gameDirectory
                if (path.isNotBlank()) {
                    val running = withContext(Dispatchers.IO) { runCatching { windows.gameRunning(Path(path)) }.getOrDefault(true) }
                    update { it.copy(gameRunning = running) }
                    if (!running && state.value.settings.disableDeveloperAfterApply && !state.value.busy && state.value.operationError == null) applySelectedProfile()
                }
                delay(5000)
            }
        }
    }
    fun changeDeveloperMode(enabled: Boolean) {
        if (state.value.installing || operation?.isActive == true) { log(message("Attendez la fin de l'opération.")); return }
        if (!enabled) { selectProfile(HubProfile.PLAY, disableDeveloper = true); return }
        settings(state.value.settings.copy(developerMode = true, profile = HubProfile.DEVELOP, disableDeveloperAfterApply = false))
        policy.change(developerMode = enabled)
        stopNetwork()
        policy.invalidate()
        update { it.copy(busy = false, relayMessage = message("Connexion…")) }
        log(message("Développement activé · choisissez le SDK et les projets à tester"))
        resumeNetwork()
    }
    fun changeAutomatic(enabled: Boolean) {
        if (state.value.installing || operation?.isActive == true) return
        settings(state.value.settings.copy(automatic = enabled))
        policy.change(automatic = enabled)
    }
    fun changeChannel(id: String, channel: String) {
        if (state.value.installing || operation?.isActive == true || channel !in listOf("stable", "beta", "alpha")) return
        settings(state.value.settings.copy(channels = state.value.settings.channels + (id to channel)))
        policy.invalidate()
        stopNetwork()
        if (id == "hub") updater.discard()
        update { it.copy(busy = false, projects = it.projects.filter { p -> p.id != id },
            availableProjects = it.availableProjects - id, readyHubVersion = updater.version,
            sdkReleases = if (id == "sdk") emptyList() else it.sdkReleases,
            kotlinKits = if (id == "sdk") emptyList() else it.kotlinKits) }
        if (policy.canSynchronize) resumeNetwork()
    }
    fun setGame(path: String) {
        if (state.value.busy) return
        if (state.value.settings.installed.isNotEmpty() || state.value.settings.activeDevelopment.isNotEmpty()) {
            fail(tr("Désinstallez les projets du jeu actuel avant de changer son dossier.")); return
        }
        settings(state.value.settings.copy(gameDirectory = path))
        update { it.copy(gameHash = "", gameIssue = null) }
        scope.launch { identifyGame() }
    }
    fun setRoot(path: String) { if (!state.value.busy) settings(state.value.settings.copy(root = path)) }
    fun saveWindow(width: Int, height: Int) {
        settings(state.value.settings.copy(windowWidth = width.coerceIn(700, 4000), windowHeight = height.coerceIn(500, 3000)))
    }

    fun checkGame() { if (!state.value.busy) scope.launch { identifyGame() } }

    private suspend fun identifyGame() {
        val path = state.value.settings.gameDirectory
        val generation = ++gameCheckGeneration
        if (path.isBlank()) {
            update { it.copy(gameHash = "", checkingGame = false, gameIssue = message("Choisissez le dossier du jeu")) }
            return
        }
        update { it.copy(checkingGame = true) }
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = Path(path)
                    require(directory.isAbsolute) { tr("Choisissez un dossier de jeu avec un chemin complet") }
                    val executable = Host.game(directory)
                    require(executable.isRegularFile()) { tr("Le dossier choisi ne contient pas {0}", Host.gameName) }
                    executable.sha256()
                }
            }
            // An older read must never validate a folder selected afterwards.
            if (generation != gameCheckGeneration || state.value.settings.gameDirectory != path) return
            val hash = result.getOrDefault("")
            val issue = if (result.isFailure) message("Impossible de lire le jeu dans ce dossier. Choisissez le dossier contenant {0}.", Host.gameName) else null
            if (state.value.gameHash != hash || state.value.gameIssue != issue) {
                if (issue != null) log(message("Vérification du jeu échouée : {0}", path), result.exceptionOrNull())
                else log(message("Jeu identifié : {0} · SHA-256={1}", path, hash))
            }
            update { it.copy(gameHash = hash, gameIssue = issue) }
        } finally {
            if (generation == gameCheckGeneration) update { it.copy(checkingGame = false) }
        }
    }
    private fun stopNetwork() { synchronization?.cancel(); relay?.cancel(); periodic?.cancel() }
    private fun resumeNetwork(refreshNow: Boolean = true) {
        periodic?.cancel(); relay?.cancel()
        if (refreshNow) refresh()
        periodic = scope.launch { while (isActive) { delay(15 * 60 * 1000L); refresh() } }
        relay = scope.launch {
            var retry = 2000L
            while (isActive && policy.canSynchronize) {
                try {
                    update { it.copy(relayMessage = message("Notifications en direct : connexion…")) }
                    source.listen {
                        withContext(scope.coroutineContext) {
                            if (!policy.canSynchronize) return@withContext
                            update { it.copy(relayMessage = message("Notifications en direct : connectées")) }
                            retry = 2000L
                            // The source paces its catalogue checks; another
                            // debounce here would hide newly published releases.
                            refresh()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { update { it.copy(relayMessage = message("Direct déconnecté · contrôle périodique actif")) } }
                delay(retry); retry = (retry * 2).coerceAtMost(300_000)
            }
        }
    }
    fun refresh() {
        if (!policy.canSynchronize || state.value.busy) return
        update { it.copy(busy = true) }
        synchronization = scope.launch {
            val ticket = policy.generation
            update { it.copy(busy = true) }
            try {
                val settings = state.value.settings
                val catalogue = try { source.catalogue(settings.channels.mapValues { (id, _) -> settings.selectedChannel(id) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    update { it.copy(availableProjects = emptySet()) }
                    log(message("Releases indisponibles · installations conservées : {0}", failure.message), failure)
                    return@launch
                }
                if (!policy.accepts(ticket)) return@launch
                update { it.copy(projects = catalogue.projects, releaseErrors = catalogue.errors, availableProjects = catalogue.projects.map { p -> p.id }.toSet()) }
                store.directory.resolve("catalog-cache.json").atomicWrite(hubJson.encodeToString(catalogue))
                identifyGame()
                log(message("Releases vérifiées · {0} projets · {1} indisponibles", catalogue.projects.size, catalogue.errors.size))
                notifyUpdates()
                try { updater.check(state.value.settings.selectedChannel("hub")) { policy.accepts(ticket) && policy.canUpdateHub } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log(message("Mise à jour du Hub : {0}", failure.message), failure) }
                if (updater.version != null && state.value.readyHubVersion == null) notify(tr("Mise à jour du Hub prête"), tr("Vous pouvez redémarrer le Hub pour appliquer la version {0}.", updater.version))
                update { it.copy(readyHubVersion = updater.version) }
                if (policy.canAutoInstall && policy.accepts(ticket)) {
                    for (project in state.value.projects.sortedBy { if (it.kind == "sdk") 0 else 1 }) {
                        val installed = state.value.settings.installed[project.id] ?: continue
                        if (Versions.compare(project.version, installed.version) <= 0) continue
                        if (!policy.canAutoInstall || !policy.accepts(ticket)) break
                        val reason = ProjectRules.incompatibility(project, state.value.gameHash, state.value.settings.installed)
                        if (reason == null) install(project, Path(installed.directory), null, ticket)
                        else log(message("{0} : mise à jour différée · {1}", project.name, reason))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log(message("Synchronisation interrompue : {0}", failure.message), failure) }
            finally { if (synchronization == currentCoroutineContext()[Job]) update { it.copy(busy = false) } }
        }
    }

    private fun notifyUpdates() {
        if (!policy.canSynchronize) return
        val settings = state.value.settings
        val notified = settings.notified.toMutableMap()
        state.value.projects.forEach { project ->
            val installed = settings.installed[project.id]
            if (installed != null && Versions.compare(project.version, installed.version) > 0 && notified[project.id] != project.version) {
                notify(tr("Mise à jour disponible"), "${project.name} ${project.version}")
                notified[project.id] = project.version
            }
        }
        settings(settings.copy(notified = notified))
    }

    fun installProject(project: Project, destination: Path, localArchive: Path? = null) {
        if (state.value.busy || (localArchive == null && (!policy.canSynchronize || project.id !in state.value.availableProjects))) return
        if (localArchive != null) { importDevelopment(project, localArchive); return }
        val ticket = policy.generation
        update { it.copy(busy = true) }
        synchronization = scope.launch {
            update { it.copy(busy = true) }
            try { install(project, destination, localArchive, ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log(message("Installation échouée : {0}", failure.message), failure); notify(tr("Installation échouée"), project.name) }
            finally { if (synchronization == currentCoroutineContext()[Job]) update { it.copy(busy = false) } }
        }
    }
    private suspend fun install(project: Project, destination: Path, localArchive: Path?, ticket: Long) {
        require(!store.directory.resolve("profile-activation.json").exists()) { tr("Restaurez d’abord l’activation interrompue depuis les paramètres") }
        require(state.value.settings.appliedProfile == HubProfile.PLAY) { tr("Revenez au profil Jouer avant de modifier ses installations") }
        require(!state.value.settings.legacyProtection) { tr("Les anciennes installations locales restent protégées. Vérifiez leur origine dans les paramètres.") }
        val target = destination.toAbsolutePath().normalize()
        val s = state.value.settings
        val protected = s.development.projects.values.map { it.directory } + listOf(s.paths.kotlinSdk, s.paths.development, s.gameDirectory)
        protected.filter { it.isNotBlank() }.map { Path(it).toAbsolutePath().normalize() }.forEach { path ->
            require(!target.startsWith(path) && !path.startsWith(target)) { tr("L’installation doit être séparée du jeu, des sources et des dossiers de développement") }
        }
        ProjectRules.validate(project, remote = localArchive == null)
        identifyGame()
        require(state.value.gameHash.isNotBlank()) {
            state.value.gameIssue?.text ?: tr("Choisissez et vérifiez le dossier du jeu avant l’installation")
        }
        val reason = ProjectRules.incompatibility(project, state.value.gameHash, state.value.settings.installed)
        require(reason == null) { reason.orEmpty() }
        val archive = localArchive ?: store.directory.resolve("downloads/${UUID.randomUUID()}.zip")
        try {
            if (localArchive == null) {
                if (!policy.accepts(ticket)) return
                log(message("Téléchargement : {0}", project.name))
                source.download(project.url, project.size, project.sha256, archive)
                if (!policy.accepts(ticket)) return
            }
            perform(InstallRequest("install", project, destination.toString(), state.value.settings.gameDirectory,
                store.directory.resolve("operation-result.json").toString(), archive.toString(), state.value.gameHash, origin = if (localArchive == null) "published" else "local"))
        } finally { if (localArchive == null) archive.deleteIfExists() }
    }
    fun manage(action: String, project: Project) {
        if (state.value.busy) return
        if (store.directory.resolve("profile-activation.json").exists()) { fail(tr("Restaurez d’abord l’activation interrompue depuis les paramètres")); return }
        if (state.value.settings.appliedProfile != HubProfile.PLAY) { fail(tr("Revenez au profil Jouer avant de modifier ses installations")); return }
        val installed = state.value.settings.installed[project.id] ?: return
        update { it.copy(busy = true) }
        scope.launch {
            update { it.copy(busy = true) }
            try {
                if (action == "rollback") changeAutomatic(false)
                perform(InstallRequest(action, project, installed.directory, state.value.settings.gameDirectory, store.directory.resolve("operation-result.json").toString()))
            } catch (failure: Exception) { log(message("Opération échouée : {0}", failure.message), failure) }
            finally { update { it.copy(busy = false) } }
        }
    }
    private suspend fun perform(request: InstallRequest) {
        requireRepairFinished()
        log(message("{0} : {1} {2} · jeu={3} · destination={4}", request.action, request.project.id, request.project.version, request.gameDirectory, request.destination))
        update { it.copy(installing = true) }
        try {
            // Once filesystem promotion begins, cancellation cannot interrupt rollback/recovery.
            val result = withContext(NonCancellable + Dispatchers.IO) { ProjectManager(windows, { log(it) }).execute(request) }
            val installed = state.value.settings.installed.toMutableMap()
            if (result == null) installed.remove(request.project.id) else installed[result.id] = result
            settings(state.value.settings.copy(installed = installed))
            log(message("Opération terminée : {0}", request.project.name))
            notify(tr("Opération terminée"), request.project.name)
        } finally { update { it.copy(installing = false) } }
    }
    fun quit(relaunch: Boolean = false): Boolean {
        if (state.value.installing || operation?.isActive == true) { log(message("Attendez la fin de l'opération avant de quitter.")); return false }
        try {
            // Automatic installation is a preference; applying an already
            // prepared update explicitly is allowed in either Hub profile.
            val launched = updater.installOnExit(policy.canUpdateHub || relaunch, relaunch)
            update { it.copy(readyHubVersion = updater.version) }
            if (relaunch && !launched) {
                fail(tr("Aucune mise à jour du Hub prête à installer."))
                return false
            }
        } catch (failure: Exception) {
            update { it.copy(readyHubVersion = updater.version) }
            fail(IllegalStateException(tr("Mise à jour du Hub impossible : {0}", failure.message), failure))
            return false
        }
        // A failed installer leaves the Hub open, including its live updates.
        stopNetwork()
        return true
    }
    fun close() { log(message("Fermeture du Hub")); stopNetwork(); gameMonitor?.cancel(); operation?.cancel() }

    fun fail(message: String) { log(message); update { it.copy(operationError = tr("{0}\n\nJournal : {1}", message, journal.file)) } }
    private fun fail(failure: Exception) {
        val message = failure.message ?: tr("Opération échouée")
        log(message, failure)
        update { it.copy(operationError = tr("{0}\n\nJournal : {1}", message, journal.file)) }
    }
    fun clearError() { update { it.copy(operationError = null) } }

    fun setPath(key: PathSetting, path: String) {
        if (state.value.busy) return
        if (key == PathSetting.GAME) { setGame(path); return }
        val s = state.value.settings
        val paths = when (key) {
            PathSetting.MODS -> s.paths.copy(mods = path)
            PathSetting.TOOLS -> s.paths.copy(tools = path)
            PathSetting.SDK -> s.paths.copy(sdk = path)
            PathSetting.LOCAL_MODS -> s.paths.copy(localMods = path)
            PathSetting.LOCAL_TOOLS -> s.paths.copy(localTools = path)
            PathSetting.DEVELOPMENT -> s.paths.copy(development = path)
            PathSetting.KOTLIN_SDK -> s.paths.copy(kotlinSdk = path)
            PathSetting.IDEA -> s.paths.copy(idea = path)
            else -> s.paths
        }
        if (key.development && !s.developerMode) return
        val development = if (key == PathSetting.KOTLIN_SDK && path != s.paths.kotlinSdk) s.development.withKotlinKitChanged() else s.development
        settings(s.copy(paths = paths, development = development))
    }

    private fun work(label: UiText, block: suspend () -> Unit) {
        if (state.value.busy) return
        update { it.copy(busy = true, operationError = null) }
        log(label)
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { log(message("Opération annulée")); throw cancelled }
            catch (failure: Exception) { fail(failure); update { it.copy(recoveryRequired = store.directory.resolve("profile-activation.json").exists()) } }
            finally { update { it.copy(busy = false, building = false, installing = false) } }
        }
    }

    fun selectProfile(profile: HubProfile, disableDeveloper: Boolean = false) {
        if (state.value.installing || operation?.isActive == true) return
        if (profile == HubProfile.DEVELOP && !state.value.settings.developerMode) return
        policy.invalidate(); stopNetwork()
        update { it.copy(busy = false) }
        settings(state.value.settings.copy(profile = profile, disableDeveloperAfterApply = disableDeveloper))
        if (state.value.gameRunning) log(message("Fermez ou redémarrez le jeu pour activer {0}", message(profile.sourceLabel)))
        else applySelectedProfile()
        resumeNetwork(refreshNow = false)
    }

    fun acknowledgeSharedData() {
        settings(state.value.settings.copy(development = state.value.settings.development.copy(sharedDataAcknowledged = true)))
    }

    fun chooseOrigin(id: String, origin: ModOrigin) {
        if (state.value.busy || !state.value.settings.developing) return
        val s = state.value.settings
        if (origin == ModOrigin.PUBLISHED && id !in s.installed && id != "sdk") {
            fail(tr("Aucune version publiée installée pour ce projet")); return
        }
        if (origin == ModOrigin.LOCAL && id !in s.development.projects && id !in s.development.prepared) return
        settings(s.copy(development = s.development.copy(origins = s.development.origins + (id to origin))))
        log(message("Sélection enregistrée · appliquée au prochain lancement"))
    }

    fun chooseSdkVersion(version: String) {
        if (state.value.busy || !state.value.settings.developing) return
        val s = state.value.settings
        if (version.isNotBlank() && version !in s.development.sdkVersions) return
        settings(s.copy(development = s.development.copy(sdkVersion = version,
            origins = s.development.origins + ("sdk" to ModOrigin.PUBLISHED))))
        log(message("SDK sélectionné · appliqué au prochain lancement"))
    }

    fun loadSdkVersions() = work(message("Recherche des versions du SDK…")) {
        val releases = source.sdkVersions(state.value.settings.selectedChannel("sdk"))
        update { it.copy(sdkReleases = releases) }
        log(message("{0} versions SDK disponibles", releases.size))
    }

    fun loadKotlinKits() = work(message("Recherche des kits Kotlin publiés…")) {
        require(state.value.settings.developing && Host.windows)
        val channel = state.value.settings.selectedChannel("sdk")
        val kits = source.kotlinKits(channel)
        update { it.copy(kotlinKits = kits) }
        log(if (kits.isEmpty()) message("Aucun kit Kotlin publié sur le canal {0}", channel) else message("{0} kits Kotlin disponibles · canal {1}", kits.size, channel))
    }

    fun downloadKotlinKit(kit: KotlinKit) = work(message("Téléchargement du kit Kotlin {0}…", kit.version)) {
        require(state.value.settings.developing && Host.windows)
        KotlinKitReleases.validate(kit)
        val archive = java.nio.file.Files.createTempFile(store.directory, ".kotlin-kit-", ".zip")
        try {
            source.download(kit.url, kit.size, kit.sha256, archive)
            val destination = store.directory.resolve("kotlin-kits/${kit.version}-${UUID.randomUUID()}")
            withContext(Dispatchers.IO) { KotlinKits.prepare(kit, archive, destination) }
            val now = state.value.settings
            settings(now.copy(paths = now.paths.copy(kotlinSdk = destination.toString()), development = now.development.withKotlinKitChanged()))
            log(message("Kit Kotlin {0} téléchargé et sélectionné · {1} · recompilez les mods locaux avant de les tester avec le SDK correspondant", kit.version, destination))
        } finally { archive.deleteIfExists() }
    }

    private fun developmentRoot(): Path {
        val s = state.value.settings
        val root = Path(s.paths.development).toAbsolutePath().normalize()
        require(s.paths.development.isNotBlank() && Path(s.paths.development).isAbsolute) { tr("Choisissez le dossier d'installation de développement") }
        val protected = s.installed.values.map { Path(it.directory) } + s.development.projects.values.map { Path(it.directory) } +
            listOfNotNull(s.gameDirectory.takeIf { it.isNotBlank() }?.let(::Path), s.paths.kotlinSdk.takeIf { it.isNotBlank() }?.let(::Path))
        protected.forEach { path ->
            val absolute = path.toAbsolutePath().normalize()
            require(!root.startsWith(absolute) && !absolute.startsWith(root)) { tr("Le dossier de développement doit être séparé du jeu, des sources, du SDK local et des installations habituelles") }
        }
        return root
    }

    private suspend fun prepare(project: Project, archive: Path, origin: String): InstalledProject {
        identifyGame()
        val destination = developmentRoot().resolve("packages/${project.id}-${UUID.randomUUID()}")
        update { it.copy(installing = true) }
        return try {
            withContext(NonCancellable + Dispatchers.IO) { requireNotNull(ProjectManager(windows, { log(it) }).execute(InstallRequest(
                "install", project, destination.toString(), state.value.settings.gameDirectory,
                store.directory.resolve("operation-result.json").toString(), archive.toString(), state.value.gameHash,
                detached = true, origin = origin))) }
        } finally { update { it.copy(installing = false) } }
    }

    fun installSdkVersion(project: Project) = work(message("Préparation du SDK {0}…", project.version)) {
        require(state.value.settings.developing && project.kind == "sdk" && project in state.value.sdkReleases)
        val archive = store.directory.resolve("downloads/${UUID.randomUUID()}.zip")
        try {
            source.download(project.url, project.size, project.sha256, archive)
            val record = prepare(project, archive, "published")
            val s = state.value.settings
            settings(s.copy(development = s.development.copy(sdkVersions = s.development.sdkVersions + (record.version to record),
                sdkVersion = record.version, origins = s.development.origins + ("sdk" to ModOrigin.PUBLISHED))))
            log(message("SDK {0} prêt pour le développement · SDK habituel conservé", record.version))
        } finally { archive.deleteIfExists() }
    }

    fun addLocalProject(directory: Path, expectedKind: String? = null) = work(message("Lecture du projet local…")) {
        require(state.value.settings.developing)
        val local = withContext(Dispatchers.IO) { LocalProjects.read(directory, state.value.projects) }
        require(expectedKind == null || local.project.kind == expectedKind) { tr("Ce dossier ne contient pas le type de projet demandé : {0}", expectedKind) }
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(projects = s.development.projects + (local.project.id to local),
            origins = s.development.origins + (local.project.id to ModOrigin.LOCAL),
            builds = s.development.builds + (local.project.id to BuildResult(status = if (local.task.isBlank()) "Paquet à importer" else "À compiler")))))
        log(message("{0} · projet local ajouté", local.project.name))
    }

    fun createProject(request: NewProjectRequest, onCreated: (LocalProject) -> Unit = {}) = work(message("Création du projet…")) {
        val snapshot = state.value.settings
        require(snapshot.developing) { tr("Sélectionnez Développer pour créer un projet") }
        val known = snapshot.development.projects.keys + snapshot.development.prepared.keys +
            snapshot.installed.keys + state.value.projects.map { it.id }
        require(request.id !in known) { tr("Cet identifiant de projet est déjà utilisé : {0}", request.id) }
        require(snapshot.paths.localMods.isNotBlank()) { tr("Choisissez le dossier des projets locaux dans Paramètres") }
        require(snapshot.paths.kotlinSdk.isNotBlank()) { tr("Choisissez un kit SDK Kotlin dans Paramètres") }
        val local = withContext(Dispatchers.IO) {
            ProjectGenerator.create(Path(snapshot.paths.localMods), Path(snapshot.paths.kotlinSdk), request)
        }
        // Creating sources does not compile or activate code in the running game.
        // Keep the path in the journal even if persisting the profile then fails.
        log(message("Sources créées : {0}", local.directory))
        val current = state.value.settings
        settings(current.copy(development = current.development.copy(
            projects = current.development.projects + (local.project.id to local),
            origins = current.development.origins + (local.project.id to ModOrigin.LOCAL),
            builds = current.development.builds + (local.project.id to BuildResult()))))
        log(message("{0} · projet créé, prêt à compiler", local.project.name))
        onCreated(local)
    }

    fun forgetLocalProject(id: String) {
        if (state.value.busy || !state.value.settings.developing) return
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(projects = s.development.projects - id,
            origins = s.development.origins - id, builds = s.development.builds - id, prepared = s.development.prepared - id)))
        log(message("Projet retiré du profil · sources conservées · activation au prochain lancement"))
    }

    fun importDevelopment(project: Project, archive: Path) = work(message("Préparation du paquet local…")) {
        require(state.value.settings.developing)
        val record = prepare(project, archive, "local")
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(prepared = s.development.prepared + (project.id to record),
            origins = s.development.origins + (project.id to ModOrigin.LOCAL),
            builds = s.development.builds + (project.id to BuildResult("Paquet prêt à tester", true, LocalTime.now().withNano(0).toString())))))
        log(message("{0} {1} prêt · installation habituelle conservée", project.name, project.version))
    }

    fun compile(id: String) = work(message("Compilation du projet local…")) {
        require(state.value.settings.developing)
        val original = state.value.settings.development.projects[id] ?: error(tr("Projet local absent"))
        val s = state.value.settings
        val previous = s.development.builds[id] ?: BuildResult()
        fun result(value: BuildResult) {
            val now = state.value.settings
            settings(now.copy(development = now.development.copy(builds = now.development.builds + (id to value))))
        }
        result(previous.copy(status = "Compilation en cours", ready = false, error = ""))
        update { it.copy(building = true) }
        try {
            val local = withContext(Dispatchers.IO) { LocalProjects.read(Path(original.directory), state.value.projects) }
            require(local.project.id == id) { tr("L'identité du projet source a changé") }
            val sdk = if (local.buildsSdk) "" else s.paths.kotlinSdk
            val sdkVersion = if (local.project.kind == "native-mod") withContext(Dispatchers.IO) { LocalProjects.kotlinSdk(sdk, local.project) } else ""
            val fingerprint = withContext(Dispatchers.IO) { LocalProjects.fingerprint(local, sdk) }
            val built = LocalProjects.build(local, sdk, { log(it) })
            val (project, archive, kit) = built
            require(withContext(Dispatchers.IO) { LocalProjects.fingerprint(local, sdk) } == fingerprint) { tr("Les sources ou le SDK ont changé pendant la compilation. Recompilez.") }
            result(previous.copy(status = "Préparation en cours", ready = false, error = ""))
            val record = prepare(project, archive, "local")
            if (kit != null) fr.nimby.hub.platform.windows.WindowsSdkBuildMatch.requireSameBuild(kit, Path(record.directory))
            val now = state.value.settings
            settings(now.withSuccessfulBuild(local, record,
                BuildResult("Prêt à tester", true, LocalTime.now().withNano(0).toString(), kit?.toString() ?: sdk, fingerprint,
                    sdkVersion = if (kit != null) project.version else sdkVersion), kit?.toString()))
            if (kit != null) log(message("Kit Kotlin {0} sélectionné · les mods locaux doivent être recompilés · jeu inchangé jusqu’à l’activation du profil", project.version))
            log(message("{0} · compilation réussie et paquet prêt à tester", project.name))
        } catch (failure: Exception) {
            // The old prepared package still exists after a failure. Keep its
            // provenance while making it unavailable for activation.
            result(previous.copy(status = if (failure is CancellationException) "Compilation annulée" else "Compilation ou préparation échouée",
                ready = false, error = failure.message.orEmpty()))
            throw failure
        }
    }

    fun applySelectedProfile() = work(message("Activation du profil {0}…", message(state.value.settings.profile.sourceLabel))) { activateSelected() }

    private suspend fun activateSelected() {
        requireRepairFinished()
        val snapshot = state.value.settings
        val game = Path(snapshot.gameDirectory)
        if (snapshot.installed.isEmpty() && snapshot.activeDevelopment.isEmpty() && !snapshot.developing) {
            settings(snapshot.copy(appliedProfile = HubProfile.PLAY, developerMode = if (snapshot.disableDeveloperAfterApply) false else snapshot.developerMode, disableDeveloperAfterApply = false))
            policy.change(developerMode = state.value.settings.developerMode || snapshot.legacyProtection)
            return
        }
        require(snapshot.gameDirectory.isNotBlank()) { tr("Choisissez le dossier du jeu dans les paramètres") }
        withContext(Dispatchers.IO) { windows.requireClosed(game, game) }
        identifyGame()
        val resolved = ProfileRules.resolve(snapshot)
        ProfileRules.validate(resolved, state.value.gameHash)
        if (snapshot.developing) {
            snapshot.development.origins.filterValues { it == ModOrigin.LOCAL }.keys.forEach { id ->
                val local = snapshot.development.projects[id]
                val build = snapshot.development.builds[id]
                if (local != null && build?.fingerprint?.isNotBlank() == true) {
                    val current = withContext(Dispatchers.IO) { LocalProjects.read(Path(local.directory), state.value.projects) }
                    require(withContext(Dispatchers.IO) { LocalProjects.fingerprint(current, snapshot.paths.kotlinSdk) } == build.fingerprint) { tr("{0} : sources ou SDK modifiés, recompilez avant de lancer", local.project.name) }
                    if (local.project.kind == "native-mod") {
                        val version = withContext(Dispatchers.IO) { LocalProjects.kotlinSdk(snapshot.paths.kotlinSdk, local.project) }
                        require(resolved["sdk"]?.version == version) { tr("Le kit de compilation {0} et le SDK du jeu {1} doivent correspondre pour tester ce résultat", version, resolved["sdk"]?.version ?: tr("absent")) }
                        if (Host.windows) fr.nimby.hub.platform.windows.WindowsSdkBuildMatch.requireSameBuild(
                            Path(snapshot.paths.kotlinSdk), Path(resolved.getValue("sdk").directory))
                    }
                }
            }
        }
        val before = if (snapshot.appliedProfile == HubProfile.DEVELOP) snapshot.activeDevelopment else snapshot.installed
        update { it.copy(installing = true) }
        withContext(NonCancellable + Dispatchers.IO) {
            val activation = ProfileActivation(windows)
            val after = if (snapshot.developing) activation.prepareDevelopment(resolved, developmentRoot(), game, snapshot.activeDevelopment) else resolved
            activation.activate(game, before, after, store.directory.resolve("profile-activation.json")) {
                settings(snapshot.copy(appliedProfile = snapshot.profile,
                    activeDevelopment = if (snapshot.developing) after else snapshot.activeDevelopment,
                    developerMode = if (snapshot.disableDeveloperAfterApply) false else snapshot.developerMode,
                    disableDeveloperAfterApply = false))
            }
        }
        update { it.copy(installing = false) }
        policy.change(developerMode = state.value.settings.developerMode || snapshot.legacyProtection)
        log(if (snapshot.developing) message("Profil Développer actif · SDK et mods de test activés") else message("Profil Jouer restauré · SDK et mods habituels actifs"))
    }

    fun launchGame(restart: Boolean = false) = work(if (restart) message("Redémarrage de NIMBY Rails…") else message("Préparation de NIMBY Rails…")) {
        val game = Path(state.value.settings.gameDirectory)
        require(Host.game(game).isRegularFile()) { tr("{0} introuvable", Host.gameName) }
        if (state.value.settings.developing) require(state.value.settings.development.sharedDataAcknowledged) { tr("Vérifiez les données de jeu partagées avant le premier essai") }
        val running = withContext(Dispatchers.IO) { windows.gameRunning(game) }
        if (running && !restart) error(tr("Le jeu est déjà ouvert. Utilisez Redémarrer NIMBY Rails."))
        if (running) {
            withContext(Dispatchers.IO) { windows.requestGameClose(game) }
            log(message("En attente de fermeture du jeu · terminez la sauvegarde ou fermez-le manuellement"))
            val closed = withContext(Dispatchers.IO) {
                withTimeoutOrNull(120_000) { while (windows.gameRunning(game)) delay(1000); true } ?: false
            }
            require(closed) { tr("Le jeu est toujours ouvert. Aucun profil n’a été modifié ; fermez le jeu puis réessayez.") }
        }
        activateSelected()
        withContext(Dispatchers.IO) { windows.launchGame(game) }
        update { it.copy(gameRunning = true) }
        log(message("NIMBY Rails lancé · profil {0}", message(state.value.settings.appliedProfile.sourceLabel)))
    }

    fun launchTool(id: String) = work(message("Lancement de l’utilitaire…")) {
        val resolved = ProfileRules.resolve(state.value.settings)
        ProfileRules.validate(resolved, state.value.gameHash)
        if (state.value.settings.profile != state.value.settings.appliedProfile) activateSelected()
        val s = state.value.settings
        val record = (if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment else s.installed)[id] ?: error(tr("Utilitaire non préparé"))
        val selected = resolved[id] ?: error(tr("Utilitaire non sélectionné"))
        require(record.version == selected.version && record.installedUtc == selected.installedUtc && record.origin == selected.origin) { tr("Redémarrez le jeu pour appliquer la sélection d’utilitaires") }
        require(record.kind == "tco")
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(Path(record.directory).resolve(Host.tcoName).toString()).directory(Path(record.directory).toFile())
            process.environment()["NRF_MANAGED_BY_HUB"] = "1"
            val active = if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment else s.installed
            active["sdk"]?.let { sdk ->
                val name = "NimbyRailsFranceSDK.${Host.moduleExtension}"
                listOf(Path(sdk.directory, "loader", name), Path(sdk.directory, "bin", name))
                    .firstOrNull { it.isRegularFile() }?.let { process.environment()["NRF_SDK_LIBRARY"] = it.toAbsolutePath().toString() }
            }
            process.start()
        }
    }

    fun releaseLegacyProtection() {
        if (state.value.busy) return
        settings(state.value.settings.copy(legacyProtection = false))
        policy.change(developerMode = state.value.settings.developerMode)
        log(message("Protection de l'ancien profil levée sur demande"))
    }

    fun recoverProfile() = work(message("Restauration de l’activation interrompue…")) {
        update { it.copy(installing = true) }
        withContext(NonCancellable + Dispatchers.IO) {
            ProfileActivation(windows).recover(Path(state.value.settings.gameDirectory), store.directory.resolve("profile-activation.json")) { before ->
                val s = state.value.settings
                val profile = if (before == s.installed) HubProfile.PLAY else HubProfile.DEVELOP
                settings(s.copy(profile = profile, appliedProfile = profile, disableDeveloperAfterApply = false,
                    developerMode = s.developerMode || profile == HubProfile.DEVELOP,
                    activeDevelopment = if (profile == HubProfile.DEVELOP) before else s.activeDevelopment))
            }
        }
        update { it.copy(recoveryRequired = false) }
        log(message("Activation précédente restaurée"))
    }
}
