package fr.nimby.hub

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
    private val source: ReleaseSource = GitHub(),
    private val notify: (String, String) -> Unit = { _, _ -> },
    private val windows: DesktopPlatform = desktopPlatform(),
) {
    private val initial = store.read()
    private val policy = UpdatePolicy(initial.developerMode || initial.legacyProtection, initial.automatic)
    private val mutable = MutableStateFlow(HubState(initial, windows = windows.supported))
    val state: StateFlow<HubState> = mutable.asStateFlow()
    private var synchronization: Job? = null
    private var relay: Job? = null
    private var periodic: Job? = null
    private var gameMonitor: Job? = null
    private var operation: Job? = null
    private var lastPush = 0L
    private val updater = SelfUpdater(store.directory, source)

    private fun update(transform: (HubState) -> HubState) { mutable.update(transform) }
    private fun log(text: String) = update { it.copy(status = text, log = (it.log + "${LocalTime.now().withNano(0)}  $text").takeLast(500)) }
    private fun settings(value: HubSettings) { store.write(value); update { it.copy(settings = value) } }
    fun start() {
        update { it.copy(recoveryRequired = store.directory.resolve("profile-activation.json").exists()) }
        val steamGame = Host.defaultGame()
        if (state.value.windows && state.value.settings.gameDirectory.isBlank() && Host.game(steamGame).isRegularFile()) {
            settings(state.value.settings.copy(gameDirectory = steamGame.toString()))
        }
        val cache = store.directory.resolve("catalog-cache.json")
        if (cache.exists()) runCatching { hubJson.decodeFromString<Catalogue>(cache.jsonText()).also { catalog ->
            require(catalog.schema == 1); catalog.projects.forEach { ProjectRules.validate(it) }
        } }.onSuccess { catalogue -> update { it.copy(projects = catalogue.projects) } }.onFailure { log("Catalogue enregistré illisible : ${it.message}") }
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
        if (state.value.installing || operation?.isActive == true) { log("Attendez la fin de l'opération."); return }
        if (!enabled) { selectProfile(HubProfile.PLAY, disableDeveloper = true); return }
        settings(state.value.settings.copy(developerMode = true, profile = HubProfile.DEVELOP, disableDeveloperAfterApply = false))
        policy.change(developerMode = enabled)
        stopNetwork()
        policy.invalidate()
        update { it.copy(busy = false, relayStatus = "Connexion…") }
        log("Développement activé · choisissez le SDK et les projets à tester")
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
            availableProjects = it.availableProjects - id, readyHubVersion = updater.version) }
        if (policy.canSynchronize) resumeNetwork()
    }
    fun setGame(path: String) {
        if (state.value.busy) return
        if (state.value.settings.installed.isNotEmpty() || state.value.settings.activeDevelopment.isNotEmpty()) {
            fail("Désinstallez les projets du jeu actuel avant de changer son dossier."); return
        }
        settings(state.value.settings.copy(gameDirectory = path))
        scope.launch { identifyGame() }
    }
    fun setRoot(path: String) { if (!state.value.busy) settings(state.value.settings.copy(root = path)) }
    fun saveWindow(width: Int, height: Int) {
        settings(state.value.settings.copy(windowWidth = width.coerceIn(700, 4000), windowHeight = height.coerceIn(500, 3000)))
    }

    private suspend fun identifyGame() {
        val path = state.value.settings.gameDirectory
        val hash = withContext(Dispatchers.IO) { runCatching { Host.game(Path(path)).sha256() }.getOrDefault("") }
        if (state.value.settings.gameDirectory == path) update { it.copy(gameHash = hash) }
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
                    update { it.copy(relayStatus = "Notifications en direct : connexion…") }
                    source.listen {
                        withContext(scope.coroutineContext) {
                            if (!policy.canSynchronize) return@withContext
                            update { it.copy(relayStatus = "Notifications en direct : connectées") }
                            retry = 2000L
                            val now = System.nanoTime()
                            if (now - lastPush > 300_000_000_000L || lastPush == 0L) { lastPush = now; refresh() }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { update { it.copy(relayStatus = "Direct déconnecté · contrôle périodique actif") } }
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
                val catalogue = try { source.catalogue(state.value.settings.channels) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    update { it.copy(availableProjects = emptySet()) }
                    log("Releases indisponibles · installations conservées : ${failure.message}")
                    return@launch
                }
                if (!policy.accepts(ticket)) return@launch
                update { it.copy(projects = catalogue.projects, releaseErrors = catalogue.errors, availableProjects = catalogue.projects.map { p -> p.id }.toSet()) }
                store.directory.resolve("catalog-cache.json").atomicWrite(hubJson.encodeToString(catalogue))
                identifyGame()
                log("Releases vérifiées · ${catalogue.projects.size} projets · ${catalogue.errors.size} indisponibles")
                notifyUpdates()
                try { updater.check(state.value.settings.selectedChannel("hub")) { policy.accepts(ticket) && policy.canAutoInstall } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log("Mise à jour du Hub : ${failure.message}") }
                if (updater.version != null && state.value.readyHubVersion == null) notify("Mise à jour du Hub prête", "Vous pouvez redémarrer le Hub pour appliquer la version ${updater.version}.")
                update { it.copy(readyHubVersion = updater.version) }
                if (policy.canAutoInstall && policy.accepts(ticket)) {
                    for (project in state.value.projects.sortedBy { if (it.kind == "sdk") 0 else 1 }) {
                        val installed = state.value.settings.installed[project.id] ?: continue
                        if (Versions.compare(project.version, installed.version) <= 0) continue
                        if (!policy.canAutoInstall || !policy.accepts(ticket)) break
                        val reason = ProjectRules.incompatibility(project, state.value.gameHash, state.value.settings.installed)
                        if (reason == null) install(project, Path(installed.directory), null, ticket)
                        else log("${project.name} : mise à jour différée · $reason")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log("Synchronisation interrompue : ${failure.message}") }
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
                notify("Mise à jour disponible", "${project.name} ${project.version}")
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
            catch (failure: Exception) { log("Installation échouée : ${failure.message}"); notify("Installation échouée", project.name) }
            finally { if (synchronization == currentCoroutineContext()[Job]) update { it.copy(busy = false) } }
        }
    }
    private suspend fun install(project: Project, destination: Path, localArchive: Path?, ticket: Long) {
        require(!store.directory.resolve("profile-activation.json").exists()) { "Restaurez d’abord l’activation interrompue depuis les paramètres" }
        require(state.value.settings.appliedProfile == HubProfile.PLAY) { "Revenez au profil Jouer avant de modifier ses installations" }
        require(!state.value.settings.legacyProtection) { "Les anciennes installations locales restent protégées. Vérifiez leur origine dans les paramètres." }
        val target = destination.toAbsolutePath().normalize()
        val s = state.value.settings
        val protected = s.development.projects.values.map { it.directory } + listOf(s.paths.kotlinSdk, s.paths.development, s.gameDirectory)
        protected.filter { it.isNotBlank() }.map { Path(it).toAbsolutePath().normalize() }.forEach { path ->
            require(!target.startsWith(path) && !path.startsWith(target)) { "L’installation doit être séparée du jeu, des sources et des dossiers de développement" }
        }
        ProjectRules.validate(project, remote = localArchive == null)
        identifyGame()
        val reason = ProjectRules.incompatibility(project, state.value.gameHash, state.value.settings.installed)
        require(reason == null) { reason.orEmpty() }
        val archive = localArchive ?: store.directory.resolve("downloads/${UUID.randomUUID()}.zip")
        try {
            if (localArchive == null) {
                if (!policy.accepts(ticket)) return
                log("Téléchargement : ${project.name}")
                source.download(project.url, project.size, project.sha256, archive)
                if (!policy.accepts(ticket)) return
            }
            perform(InstallRequest("install", project, destination.toString(), state.value.settings.gameDirectory,
                store.directory.resolve("operation-result.json").toString(), archive.toString(), state.value.gameHash, origin = if (localArchive == null) "published" else "local"))
        } finally { if (localArchive == null) archive.deleteIfExists() }
    }
    fun manage(action: String, project: Project) {
        if (state.value.busy) return
        if (store.directory.resolve("profile-activation.json").exists()) { fail("Restaurez d’abord l’activation interrompue depuis les paramètres"); return }
        if (state.value.settings.appliedProfile != HubProfile.PLAY) { fail("Revenez au profil Jouer avant de modifier ses installations"); return }
        val installed = state.value.settings.installed[project.id] ?: return
        update { it.copy(busy = true) }
        scope.launch {
            update { it.copy(busy = true) }
            try {
                if (action == "rollback") changeAutomatic(false)
                perform(InstallRequest(action, project, installed.directory, state.value.settings.gameDirectory, store.directory.resolve("operation-result.json").toString()))
            } catch (failure: Exception) { log("Opération échouée : ${failure.message}") }
            finally { update { it.copy(busy = false) } }
        }
    }
    private suspend fun perform(request: InstallRequest) {
        update { it.copy(installing = true) }
        try {
            // Once filesystem promotion begins, cancellation cannot interrupt rollback/recovery.
            val result = withContext(NonCancellable + Dispatchers.IO) { ProjectManager().execute(request) }
            val installed = state.value.settings.installed.toMutableMap()
            if (result == null) installed.remove(request.project.id) else installed[result.id] = result
            settings(state.value.settings.copy(installed = installed))
            log("Opération terminée : ${request.project.name}")
            notify("Opération terminée", request.project.name)
        } finally { update { it.copy(installing = false) } }
    }
    fun quit(relaunch: Boolean = false): Boolean {
        if (state.value.installing || operation?.isActive == true) { log("Attendez la fin de l'opération avant de quitter."); return false }
        stopNetwork()
        try { updater.installOnExit(policy.canAutoInstall, relaunch) }
        catch (failure: Exception) { log("Mise à jour du Hub impossible : ${failure.message}"); return false }
        return true
    }
    fun close() { stopNetwork(); gameMonitor?.cancel(); operation?.cancel() }

    fun fail(message: String) { log(message); update { it.copy(operationError = message) } }
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
        val development = if (key == PathSetting.KOTLIN_SDK) s.development.copy(builds = s.development.builds.mapValues { (id, result) ->
            if (s.development.projects[id]?.task?.isNotBlank() == true) result.copy(ready = false, status = "À recompiler avec le SDK choisi") else result
        }) else s.development
        settings(s.copy(paths = paths, development = development))
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        update { it.copy(busy = true, operationError = null) }
        log(label)
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { log("Opération annulée"); throw cancelled }
            catch (failure: Exception) { fail(failure.message ?: "Opération échouée"); update { it.copy(recoveryRequired = store.directory.resolve("profile-activation.json").exists()) } }
            finally { update { it.copy(busy = false, building = false, installing = false) } }
        }
    }

    fun selectProfile(profile: HubProfile, disableDeveloper: Boolean = false) {
        if (state.value.installing || operation?.isActive == true) return
        if (profile == HubProfile.DEVELOP && !state.value.settings.developerMode) return
        policy.invalidate(); stopNetwork()
        update { it.copy(busy = false) }
        settings(state.value.settings.copy(profile = profile, disableDeveloperAfterApply = disableDeveloper))
        if (state.value.gameRunning) log("Fermez ou redémarrez le jeu pour activer ${profile.label}")
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
            fail("Aucune version publiée installée pour ce projet"); return
        }
        if (origin == ModOrigin.LOCAL && id !in s.development.projects && id !in s.development.prepared) return
        settings(s.copy(development = s.development.copy(origins = s.development.origins + (id to origin))))
        log("Sélection enregistrée · appliquée au prochain lancement")
    }

    fun chooseSdkVersion(version: String) {
        if (state.value.busy || !state.value.settings.developing) return
        val s = state.value.settings
        if (version.isNotBlank() && version !in s.development.sdkVersions) return
        settings(s.copy(development = s.development.copy(sdkVersion = version,
            origins = s.development.origins + ("sdk" to ModOrigin.PUBLISHED))))
        log("SDK sélectionné · appliqué au prochain lancement")
    }

    fun loadSdkVersions() = work("Recherche des versions du SDK…") {
        val releases = source.sdkVersions(state.value.settings.selectedChannel("sdk"))
        update { it.copy(sdkReleases = releases) }
        log("${releases.size} versions SDK disponibles")
    }

    private fun developmentRoot(): Path {
        val s = state.value.settings
        val root = Path(s.paths.development).toAbsolutePath().normalize()
        require(s.paths.development.isNotBlank() && Path(s.paths.development).isAbsolute) { "Choisissez le dossier d'installation de développement" }
        val protected = s.installed.values.map { Path(it.directory) } + s.development.projects.values.map { Path(it.directory) } +
            listOfNotNull(s.gameDirectory.takeIf { it.isNotBlank() }?.let(::Path), s.paths.kotlinSdk.takeIf { it.isNotBlank() }?.let(::Path))
        protected.forEach { path ->
            val absolute = path.toAbsolutePath().normalize()
            require(!root.startsWith(absolute) && !absolute.startsWith(root)) { "Le dossier de développement doit être séparé du jeu, des sources, du SDK local et des installations habituelles" }
        }
        return root
    }

    private suspend fun prepare(project: Project, archive: Path, origin: String): InstalledProject {
        identifyGame()
        val destination = developmentRoot().resolve("packages/${project.id}-${UUID.randomUUID()}")
        update { it.copy(installing = true) }
        return try {
            withContext(NonCancellable + Dispatchers.IO) { requireNotNull(ProjectManager().execute(InstallRequest(
                "install", project, destination.toString(), state.value.settings.gameDirectory,
                store.directory.resolve("operation-result.json").toString(), archive.toString(), state.value.gameHash,
                detached = true, origin = origin))) }
        } finally { update { it.copy(installing = false) } }
    }

    fun installSdkVersion(project: Project) = work("Préparation du SDK ${project.version}…") {
        require(state.value.settings.developing && project.kind == "sdk" && project in state.value.sdkReleases)
        val archive = store.directory.resolve("downloads/${UUID.randomUUID()}.zip")
        try {
            source.download(project.url, project.size, project.sha256, archive)
            val record = prepare(project, archive, "published")
            val s = state.value.settings
            settings(s.copy(development = s.development.copy(sdkVersions = s.development.sdkVersions + (record.version to record),
                sdkVersion = record.version, origins = s.development.origins + ("sdk" to ModOrigin.PUBLISHED))))
            log("SDK ${record.version} prêt pour le développement · SDK habituel conservé")
        } finally { archive.deleteIfExists() }
    }

    fun addLocalProject(directory: Path) = work("Lecture du projet local…") {
        require(state.value.settings.developing)
        val local = withContext(Dispatchers.IO) { LocalProjects.read(directory, state.value.projects) }
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(projects = s.development.projects + (local.project.id to local),
            origins = s.development.origins + (local.project.id to ModOrigin.LOCAL),
            builds = s.development.builds + (local.project.id to BuildResult(status = if (local.task.isBlank()) "Paquet à importer" else "À compiler")))))
        log("${local.project.name} · projet local ajouté")
    }

    fun forgetLocalProject(id: String) {
        if (state.value.busy || !state.value.settings.developing) return
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(projects = s.development.projects - id,
            origins = s.development.origins - id, builds = s.development.builds - id, prepared = s.development.prepared - id)))
        log("Projet retiré du profil · sources conservées · activation au prochain lancement")
    }

    fun importDevelopment(project: Project, archive: Path) = work("Préparation du paquet local…") {
        require(state.value.settings.developing)
        val record = prepare(project, archive, "local")
        val s = state.value.settings
        settings(s.copy(development = s.development.copy(prepared = s.development.prepared + (project.id to record),
            origins = s.development.origins + (project.id to ModOrigin.LOCAL),
            builds = s.development.builds + (project.id to BuildResult("Paquet prêt à tester", true, LocalTime.now().withNano(0).toString())))))
        log("${project.name} ${project.version} prêt · installation habituelle conservée")
    }

    fun compile(id: String) = work("Compilation du projet local…") {
        require(state.value.settings.developing)
        val original = state.value.settings.development.projects[id] ?: error("Projet local absent")
        val s = state.value.settings
        fun result(value: BuildResult) {
            val now = state.value.settings
            settings(now.copy(development = now.development.copy(builds = now.development.builds + (id to value))))
        }
        result(BuildResult("Compilation en cours"))
        update { it.copy(building = true) }
        try {
            val local = withContext(Dispatchers.IO) { LocalProjects.read(Path(original.directory), state.value.projects) }
            require(local.project.id == id) { "L'identité du projet source a changé" }
            val sdk = s.paths.kotlinSdk
            if (local.project.kind == "native-mod") withContext(Dispatchers.IO) { LocalProjects.kotlinSdk(sdk, local.project) }
            val fingerprint = withContext(Dispatchers.IO) { LocalProjects.fingerprint(local, sdk) }
            val (project, archive) = LocalProjects.build(local, sdk, ::log)
            require(withContext(Dispatchers.IO) { LocalProjects.fingerprint(local, sdk) } == fingerprint) { "Les sources ou le SDK ont changé pendant la compilation. Recompilez." }
            result(BuildResult("Préparation en cours"))
            val record = prepare(project, archive, "local")
            val now = state.value.settings
            settings(now.copy(development = now.development.copy(projects = now.development.projects + (id to local),
                prepared = now.development.prepared + (id to record))))
            result(BuildResult("Prêt à tester", true, LocalTime.now().withNano(0).toString(), sdk, fingerprint))
            log("${project.name} · compilation réussie et paquet prêt à tester")
        } catch (failure: Exception) {
            result(BuildResult(if (failure is CancellationException) "Compilation annulée" else "Compilation ou préparation échouée", error = failure.message.orEmpty()))
            throw failure
        }
    }

    fun applySelectedProfile() = work("Activation du profil ${state.value.settings.profile.label}…") { activateSelected() }

    private suspend fun activateSelected() {
        val snapshot = state.value.settings
        val game = Path(snapshot.gameDirectory)
        if (snapshot.installed.isEmpty() && snapshot.activeDevelopment.isEmpty() && !snapshot.developing) {
            settings(snapshot.copy(appliedProfile = HubProfile.PLAY, developerMode = if (snapshot.disableDeveloperAfterApply) false else snapshot.developerMode, disableDeveloperAfterApply = false))
            policy.change(developerMode = state.value.settings.developerMode || snapshot.legacyProtection)
            return
        }
        require(snapshot.gameDirectory.isNotBlank()) { "Choisissez le dossier du jeu dans les paramètres" }
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
                    require(withContext(Dispatchers.IO) { LocalProjects.fingerprint(current, snapshot.paths.kotlinSdk) } == build.fingerprint) { "${local.project.name} : sources ou SDK modifiés, recompilez avant de lancer" }
                    if (local.project.kind == "native-mod") {
                        val version = withContext(Dispatchers.IO) { LocalProjects.kotlinSdk(snapshot.paths.kotlinSdk, local.project) }
                        require(resolved["sdk"]?.version == version) { "Le kit de compilation $version et le SDK du jeu ${resolved["sdk"]?.version ?: "absent"} doivent correspondre pour tester ce résultat" }
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
        log(if (snapshot.developing) "Profil Développer actif · SDK et mods de test activés" else "Profil Jouer restauré · SDK et mods habituels actifs")
    }

    fun launchGame(restart: Boolean = false) = work(if (restart) "Redémarrage de NIMBY Rails…" else "Préparation de NIMBY Rails…") {
        val game = Path(state.value.settings.gameDirectory)
        require(Host.game(game).isRegularFile()) { "${Host.gameName} introuvable" }
        if (state.value.settings.developing) require(state.value.settings.development.sharedDataAcknowledged) { "Vérifiez les données de jeu partagées avant le premier essai" }
        val running = withContext(Dispatchers.IO) { windows.gameRunning(game) }
        if (running && !restart) error("Le jeu est déjà ouvert. Utilisez Redémarrer NIMBY Rails.")
        if (running) {
            withContext(Dispatchers.IO) { windows.requestGameClose(game) }
            log("En attente de fermeture du jeu · terminez la sauvegarde ou fermez-le manuellement")
            val closed = withContext(Dispatchers.IO) {
                withTimeoutOrNull(120_000) { while (windows.gameRunning(game)) delay(1000); true } ?: false
            }
            require(closed) { "Le jeu est toujours ouvert. Aucun profil n’a été modifié ; fermez le jeu puis réessayez." }
        }
        activateSelected()
        withContext(Dispatchers.IO) { windows.launchGame(game) }
        update { it.copy(gameRunning = true) }
        log("NIMBY Rails lancé · profil ${state.value.settings.appliedProfile.label}")
    }

    fun launchTool(id: String) = work("Lancement de l’utilitaire…") {
        val resolved = ProfileRules.resolve(state.value.settings)
        ProfileRules.validate(resolved, state.value.gameHash)
        if (state.value.settings.profile != state.value.settings.appliedProfile) activateSelected()
        val s = state.value.settings
        val record = (if (s.appliedProfile == HubProfile.DEVELOP) s.activeDevelopment else s.installed)[id] ?: error("Utilitaire non préparé")
        val selected = resolved[id] ?: error("Utilitaire non sélectionné")
        require(record.version == selected.version && record.installedUtc == selected.installedUtc && record.origin == selected.origin) { "Redémarrez le jeu pour appliquer la sélection d’utilitaires" }
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
        log("Protection de l'ancien profil levée sur demande")
    }

    fun recoverProfile() = work("Restauration de l’activation interrompue…") {
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
        log("Activation précédente restaurée")
    }
}
