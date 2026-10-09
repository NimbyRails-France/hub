package fr.nimby.hub

import fr.nimby.hub.i18n.*

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.*
import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import fr.nimby.hub.ui.*
import fr.nimby.hub.network.ResilientReleases
import kotlinx.coroutines.*
import java.awt.*
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    I18n.configure("auto", java.util.Locale.getDefault().toLanguageTag())
    if (args.firstOrNull() == "--package-smoke-test") {
        check(System.getProperty("os.name").startsWith("Windows"))
        // Load the actual Windows Skiko DLL without starting the UI or the game.
        org.jetbrains.skia.Surface.makeRasterN32Premul(2, 2).use { surface ->
            check(surface.width == 2)
        }
        check(object {}.javaClass.getResource("/windows-diagnostics.ps1") != null)
        check(hubLogo.width > 0 && hubLogo.height > 0)
        println("PASS: packaged Windows JVM, application classes and native dependencies")
        return
    }
    if (args.firstOrNull() == "--network-test") {
        runBlocking {
            val channel = args.getOrNull(1) ?: "stable"
            require(channel in listOf("stable", "beta", "alpha")) { tr("Canal inconnu") }
            val source = ResilientReleases(report = ::println)
            val projects = DistributionLocation.projects.filter { it != "hub" }
                .sortedWith(compareBy<String> { it != "sdk" }.thenBy { it })
            val catalogue = source.catalogue(projects.associateWith { channel })
            println(tr("Catalogue NRF : {0} projets valides, {1} indisponibles", catalogue.projects.size, catalogue.errors.size))
            catalogue.errors.forEach { (id, error) -> println("$id : $error") }
            projects.forEach { println(tr("Release officielle : {0}", source.project(it, channel).let { p -> "${p.id} ${p.version}" })) }
            println(tr("Manifeste du Hub : {0}", source.hub(channel).version))
        }
        return
    }
    if (args.firstOrNull() == "--manage") {
        val journal = HubLog(DiagnosticPaths.hub(), "manager.log")
        try {
            val request = hubJson.decodeFromString<InstallRequest>(Path(args[1]).jsonText())
            val platform = args.getOrNull(2)?.takeIf { Host.windows }?.let { Windows(Path(it)) { text -> journal.append(text) } }
                ?: desktopPlatform { journal.append(it) }
            journal.append(tr("{0} : {1} {2} · jeu={3} · destination={4}", request.action, request.project.id, request.project.version, request.gameDirectory, request.destination))
            ProjectManager(platform) { journal.append(it); println(it) }.execute(request)
            journal.append(tr("Opération terminée"))
            println(tr("Opération terminée"))
        } catch (failure: Exception) { journal.append(tr("Opération échouée"), failure); System.err.println(tr("{0}\nJournal : {1}", failure.message, journal.file)); exitProcess(1) }
        return
    }
    val dataIndex = args.indexOf("--data-dir")
    val data = if (dataIndex >= 0) Path(args[dataIndex + 1]) else defaultDataDirectory()
    data.createDirectories()
    val single = SingleInstance(data)
    if (!single.acquired) { single.activateExisting(); single.close(); return }
    val applicationLog = HubLog(DiagnosticPaths.hub())
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        applicationLog.append(tr("Exception non interceptée · {0}", thread.name), failure)
        previousHandler?.uncaughtException(thread, failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, failure ->
        applicationLog.append(tr("Erreur de tâche du Hub"), failure)
    })
    var tray: TrayIcon? = null
    val controller = try { HubController(SettingsStore(data), scope, notify = { title, text -> tray?.displayMessage(title, text, TrayIcon.MessageType.INFO) }, journal = applicationLog) }
    catch (failure: Exception) {
        applicationLog.append(tr("Impossible de lire le profil"), failure)
        JOptionPane.showMessageDialog(null, tr("Impossible de lire le profil : {0}\nVos données sont conservées.", failure.message), "NRF Hub", JOptionPane.ERROR_MESSAGE)
        single.close(); return
    }
    var showExisting: () -> Unit = {}
    try { single.start { SwingUtilities.invokeLater { showExisting() } } }
    catch (failure: Exception) { JOptionPane.showMessageDialog(null, failure.message); single.close(); return }
    application {
        val logo = remember { BitmapPainter(hubLogo.toComposeImageBitmap()) }
        val state by controller.state.collectAsState()
        var visible by remember { mutableStateOf(true) }
        var creatingProject by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(width = state.settings.windowWidth.dp, height = state.settings.windowHeight.dp)
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        fun show() { visible = true; windowState.isMinimized = false }
        fun toggleFullscreen() {
            if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
            else { previousPlacement = windowState.placement; windowState.placement = WindowPlacement.Fullscreen }
        }
        fun quit(relaunch: Boolean = false) {
            // Finish settings writes before starting the installer, so a save
            // failure cannot leave the old Hub running during replacement.
            try {
                controller.saveWindow(windowState.size.width.value.toInt(), windowState.size.height.value.toInt())
            } catch (failure: Exception) {
                controller.fail(tr("Impossible d’enregistrer les réglages du Hub : {0}", failure.message))
                return
            }
            if (controller.quit(relaunch)) {
                exitApplication()
            }
        }
        DisposableEffect(Unit) {
            showExisting = ::show
            controller.start()
            onDispose {
                controller.close(); scope.cancel(); single.close()
            }
        }
        // Recreate only tray labels; keep networking and operations alive.
        DisposableEffect(I18n.language) {
            if (SystemTray.isSupported()) {
                val menu = PopupMenu()
                fun action(label: String, block: () -> Unit) { menu.add(MenuItem(label).apply { addActionListener { block() } }) }
                action(tr("Afficher le Hub"), ::show)
                action(tr("Actualiser"), controller::refresh)
                action(tr("Quitter")) { quit() }
                tray = TrayIcon(hubLogo, "NimbyRails France Hub", menu).apply {
                    isImageAutoSize = true
                    addActionListener { show() }
                    SystemTray.getSystemTray().add(this)
                }
            }
            onDispose {
                tray?.let { SystemTray.getSystemTray().remove(it) }; tray = null
            }
        }
        LaunchedEffect(state.settings.developerMode, I18n.language) { tray?.toolTip = if (state.settings.developerMode) tr("NRF Hub · mode développeur") else "NimbyRails France Hub" }
        Window(
            onCloseRequest = { if (tray != null) visible = false else quit() },
            state = windowState, visible = visible, title = "NimbyRails France Hub",
            icon = logo,
            onPreviewKeyEvent = { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.F11 -> { toggleFullscreen(); true }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape && windowState.placement == WindowPlacement.Fullscreen -> { toggleFullscreen(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = Dimension(1080, 700)
            LaunchedEffect(visible) { if (visible) { window.toFront(); window.requestFocus() } }
            MenuBar { Menu(tr("Fenêtre")) {
                Item(tr("Réduire"), onClick = { windowState.isMinimized = true })
                Item(tr("Plein écran / Fenêtre"), onClick = ::toggleFullscreen)
                Item(tr("Quitter le Hub"), onClick = { quit() })
            } }
            fun choose(title: String, directory: Boolean, start: String = state.settings.root): Path? {
                val chooser = JFileChooser(start.ifBlank { data.toString() }).apply {
                    locale = java.util.Locale.forLanguageTag(I18n.language)
                    updateUI()
                    dialogTitle = title
                    approveButtonText = tr("Ouvrir")
                    fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                }
                return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath().toAbsolutePath().normalize() else null
            }
            fun confirm(message: String) = JOptionPane.showOptionDialog(window, message, "NRF Hub", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("Oui"), tr("Non")), tr("Non")) == 0
            fun install(project: Project, archive: Path? = null) {
                val destination = state.settings.installed[project.id]?.directory?.let(::Path)
                    ?: state.settings.path(when (project.kind) { "sdk" -> PathSetting.SDK; "tco" -> PathSetting.TOOLS; else -> PathSetting.MODS })
                        .takeIf { it.isNotBlank() }?.let { Path(it).resolve(project.id) }
                    ?: choose(tr("Choisir le dossier parent du projet"), true)?.resolve(project.id) ?: return
                val current = state.settings.installed[project.id]
                val downgrade = if (current != null && Versions.compare(project.version, current.version) < 0) tr("\nCette version est plus ancienne que {0}.", current.version) else ""
                if (confirm(tr("{0} {1} ({2})\nInstaller dans {3} ?{4}\n\nFermez le jeu et le TCO avant l'installation.", project.name, project.version, Versions.channel(project.version), destination, downgrade))) controller.installProject(project, destination, archive)
            }
            // The project form owns creation errors so the user's input stays visible.
            HubScreen(if (creatingProject) state.copy(operationError = null) else state, HubActions(
                language = controller::changeLanguage,
                chooseGame = { choose(tr("Dossier contenant {0}", Host.gameName), true, state.settings.gameDirectory)?.let { controller.setGame(it.toString()) } },
                checkGame = controller::checkGame,
                chooseRoot = { choose(tr("Bibliothèque de projets"), true)?.let { controller.setRoot(it.toString()) } },
                developerMode = controller::changeDeveloperMode,
                automatic = controller::changeAutomatic,
                refresh = controller::refresh,
                install = { install(it) },
                open = { record -> runCatching {
                    Desktop.getDesktop().open(Path(record.directory).toFile())
                }.onFailure { JOptionPane.showMessageDialog(window, it.message) } },
                remove = { if (confirm(tr("Désinstaller {0} ?", it.name))) controller.manage("remove", it) },
                rollback = { if (confirm(tr("Restaurer la version précédente de {0} et suspendre les mises à jour automatiques ?", it.name))) controller.manage("rollback", it) },
                importLocal = {
                    runCatching {
                        require(state.settings.developing) { tr("Sélectionnez Développer pour importer un paquet local") }
                        val manifest = choose(tr("Choisir le manifeste project.json"), false, state.settings.paths.localMods)
                        if (manifest != null) {
                            val project = hubJson.decodeFromString<Project>(manifest.jsonText())
                            ProjectRules.validate(project, remote = false)
                            val archive = choose(tr("Choisir l'archive ZIP correspondante"), false, manifest.parent.toString())
                            if (archive != null) controller.importDevelopment(project, archive)
                        }
                    }.onFailure { JOptionPane.showMessageDialog(window, it.message, tr("Paquet local invalide"), JOptionPane.ERROR_MESSAGE) }
                },
                restart = { quit(relaunch = true) },
                channel = controller::changeChannel,
                notes = { project -> Desktop.getDesktop().browse(java.net.URI(project.releaseUrl)) },
                choosePath = { key -> choose(key.label, !key.file, state.settings.path(key))?.let { controller.setPath(key, it.toString()) } },
                profile = { controller.selectProfile(it) },
                addLocal = { kind ->
                    val start = when (kind) {
                        "sdk" -> state.settings.development.projects["sdk"]?.directory ?: state.settings.paths.localMods
                        "tco" -> state.settings.paths.localTools
                        else -> state.settings.paths.localMods
                    }
                    choose(tr("Ajouter un projet local"), true, start)?.let { controller.addLocalProject(it, kind) }
                },
                createProject = { controller.clearError(); creatingProject = true },
                origin = controller::chooseOrigin,
                compile = controller::compile,
                openIdea = { local -> runCatching {
                    val executable = state.settings.paths.idea.takeIf { it.isNotBlank() }?.let(::Path)
                        ?: choose(tr("Choisir idea64.exe"), false)?.also { controller.setPath(PathSetting.IDEA, it.toString()) }
                    if (executable != null) {
                        require(executable.isRegularFile()) { tr("Exécutable IntelliJ introuvable") }
                        ProcessBuilder(executable.toString(), local.directory).start()
                    }
                }.onFailure { controller.fail(it.message ?: tr("Impossible d’ouvrir IntelliJ")) } },
                forgetLocal = { id -> if (confirm(tr("Retirer ce projet du profil ? Ses sources seront conservées."))) controller.forgetLocalProject(id) },
                launchGame = { restart ->
                    val acknowledged = !state.settings.developing || state.settings.development.sharedDataAcknowledged ||
                        confirm(tr("Les sauvegardes et réglages globaux de NIMBY Rails restent partagés.\nUtilisez une copie de votre partie pour les essais.\n\nContinuer avec le profil Développer ?")).also { if (it) controller.acknowledgeSharedData() }
                    if (acknowledged && (!restart || confirm(tr("Sauvegardez votre partie avant de continuer.\n\nLe Hub demandera au jeu de se fermer normalement, appliquera le profil choisi puis relancera NIMBY Rails.\nAucun arrêt forcé ne sera effectué.\n\nRedémarrer le jeu ?")))) controller.launchGame(restart)
                },
                launchTool = controller::launchTool,
                applyProfile = controller::applySelectedProfile,
                loadSdkVersions = controller::loadSdkVersions,
                installSdk = controller::installSdkVersion,
                sdkVersion = controller::chooseSdkVersion,
                loadKotlinKits = controller::loadKotlinKits,
                downloadKotlinKit = controller::downloadKotlinKit,
                clearError = controller::clearError,
                releaseLegacy = { if (confirm(tr("Autoriser à nouveau les mises à jour des anciennes installations ?\nVérifiez d’abord que vos versions locales ont été conservées séparément."))) controller.releaseLegacyProtection() },
                recoverProfile = controller::recoverProfile,
                openLogs = { runCatching {
                    controller.logDirectory.createDirectories()
                    Desktop.getDesktop().open(controller.logDirectory.toFile())
                }.onFailure { controller.fail(tr("Impossible d'ouvrir les journaux : {0}", it.message)) } },
                exportLogs = {
                    val chooser = JFileChooser().apply {
                        locale = java.util.Locale.forLanguageTag(I18n.language)
                        updateUI()
                        approveButtonText = tr("Enregistrer")
                        dialogTitle = tr("Exporter les logs NRF (chemins locaux possibles, aucune sauvegarde du jeu)")
                        selectedFile = java.io.File("NRF-diagnostics-${java.time.LocalDateTime.now().toString().replace(':', '-')}.zip")
                    }
                    if (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) controller.exportLogs(chooser.selectedFile.toPath())
                },
                repairSdk = { if (confirm(tr("Fermez le jeu. Le Hub va vérifier puis sauvegarder le chargeur actuel et restaurer la SDL d’origine. Vous devrez ensuite réappliquer votre profil ou réinstaller le SDK. Continuer ?"))) controller.repairSdk() },
            ), logo = logo)
            if (creatingProject) NewProjectDialog(state,
                onDismiss = { if (!state.busy) { creatingProject = false; controller.clearError() } },
                onCreate = { request -> controller.createProject(request) { creatingProject = false } })
        }
    }
}
