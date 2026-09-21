package fr.nimby.hub

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import fr.nimby.hub.install.*
import fr.nimby.hub.model.*
import fr.nimby.hub.platform.*
import fr.nimby.hub.storage.*
import fr.nimby.hub.ui.*
import fr.nimby.hub.network.GitHub
import kotlinx.coroutines.*
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--network-test") {
        runBlocking {
            val source = GitHub()
            val catalogue = source.catalogue()
            println("Découverte GitHub : ${catalogue.projects.size} projets valides, ${catalogue.errors.size} indisponibles")
            catalogue.errors.forEach { (id, error) -> println("$id : $error") }
            listOf("sdk", "tco").forEach { println("Release officielle : ${source.project(it).let { p -> "${p.id} ${p.version}" }}") }
            println("Manifeste du Hub : ${source.hub().version}")
        }
        return
    }
    if (args.firstOrNull() == "--manage") {
        try {
            val request = hubJson.decodeFromString<InstallRequest>(Path(args[1]).jsonText())
            val platform = args.getOrNull(2)?.takeIf { Host.windows }?.let { Windows(Path(it)) } ?: desktopPlatform()
            ProjectManager(platform, ::println).execute(request)
            println("Opération terminée")
        } catch (failure: Exception) { System.err.println(failure.message); exitProcess(1) }
        return
    }
    val dataIndex = args.indexOf("--data-dir")
    val data = if (dataIndex >= 0) Path(args[dataIndex + 1]) else defaultDataDirectory()
    data.createDirectories()
    val single = SingleInstance(data)
    if (!single.acquired) { single.activateExisting(); single.close(); return }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var tray: TrayIcon? = null
    val controller = try { HubController(SettingsStore(data), scope, notify = { title, text -> tray?.displayMessage(title, text, TrayIcon.MessageType.INFO) }) }
    catch (failure: Exception) {
        JOptionPane.showMessageDialog(null, "Impossible de lire le profil : ${failure.message}\nVos données sont conservées.", "NRF Hub", JOptionPane.ERROR_MESSAGE)
        single.close(); return
    }
    var showExisting: () -> Unit = {}
    try { single.start { SwingUtilities.invokeLater { showExisting() } } }
    catch (failure: Exception) { JOptionPane.showMessageDialog(null, failure.message); single.close(); return }
    application {
        val state by controller.state.collectAsState()
        var visible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(width = state.settings.windowWidth.dp, height = state.settings.windowHeight.dp)
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        fun show() { visible = true; windowState.isMinimized = false }
        fun toggleFullscreen() {
            if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
            else { previousPlacement = windowState.placement; windowState.placement = WindowPlacement.Fullscreen }
        }
        fun quit(relaunch: Boolean = false) {
            if (controller.quit(relaunch)) {
                controller.saveWindow(windowState.size.width.value.toInt(), windowState.size.height.value.toInt())
                exitApplication()
            }
        }
        DisposableEffect(Unit) {
            showExisting = ::show
            if (SystemTray.isSupported()) {
                val menu = PopupMenu()
                fun action(label: String, block: () -> Unit) { menu.add(MenuItem(label).apply { addActionListener { block() } }) }
                action("Afficher le Hub", ::show)
                action("Actualiser", controller::refresh)
                action("Quitter") { quit() }
                tray = TrayIcon(hubIcon(), "NimbyRails France Hub", menu).apply {
                    isImageAutoSize = true
                    addActionListener { show() }
                    SystemTray.getSystemTray().add(this)
                }
            }
            controller.start()
            onDispose {
                controller.close(); scope.cancel(); tray?.let { SystemTray.getSystemTray().remove(it) }; single.close()
            }
        }
        LaunchedEffect(state.settings.developerMode) { tray?.toolTip = if (state.settings.developerMode) "NRF Hub · mode développeur" else "NimbyRails France Hub" }
        Window(
            onCloseRequest = { if (tray != null) visible = false else quit() },
            state = windowState, visible = visible, title = "NimbyRails France Hub",
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
            MenuBar { Menu("Fenêtre") {
                Item("Réduire", onClick = { windowState.isMinimized = true })
                Item("Plein écran / Fenêtre", onClick = ::toggleFullscreen)
                Item("Quitter le Hub", onClick = { quit() })
            } }
            fun choose(title: String, directory: Boolean, start: String = state.settings.root): Path? {
                val chooser = JFileChooser(start.ifBlank { data.toString() }).apply {
                    dialogTitle = title
                    fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                }
                return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath().toAbsolutePath().normalize() else null
            }
            fun confirm(message: String) = JOptionPane.showConfirmDialog(window, message, "NRF Hub", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
            fun install(project: Project, archive: Path? = null) {
                val destination = state.settings.installed[project.id]?.directory?.let(::Path)
                    ?: state.settings.path(when (project.kind) { "sdk" -> PathSetting.SDK; "tco" -> PathSetting.TOOLS; else -> PathSetting.MODS })
                        .takeIf { it.isNotBlank() }?.let { Path(it).resolve(project.id) }
                    ?: choose("Choisir le dossier parent du projet", true)?.resolve(project.id) ?: return
                val current = state.settings.installed[project.id]
                val downgrade = if (current != null && Versions.compare(project.version, current.version) < 0) "\nCette version est plus ancienne que ${current.version}." else ""
                if (confirm("${project.name} ${project.version} (${Versions.channel(project.version)})\nInstaller dans $destination ?$downgrade\n\nFermez le jeu et le TCO avant l'installation.")) controller.installProject(project, destination, archive)
            }
            HubScreen(state, HubActions(
                chooseGame = { choose("Dossier contenant ${Host.gameName}", true, state.settings.gameDirectory)?.let { controller.setGame(it.toString()) } },
                chooseRoot = { choose("Bibliothèque de projets", true)?.let { controller.setRoot(it.toString()) } },
                developerMode = controller::changeDeveloperMode,
                automatic = controller::changeAutomatic,
                refresh = controller::refresh,
                install = { install(it) },
                open = { record -> runCatching {
                    Desktop.getDesktop().open(Path(record.directory).toFile())
                }.onFailure { JOptionPane.showMessageDialog(window, it.message) } },
                remove = { if (confirm("Désinstaller ${it.name} ?")) controller.manage("remove", it) },
                rollback = { if (confirm("Restaurer la version précédente de ${it.name} et suspendre les mises à jour automatiques ?")) controller.manage("rollback", it) },
                importLocal = {
                    runCatching {
                        require(state.settings.developing) { "Sélectionnez Développer pour importer un paquet local" }
                        val manifest = choose("Choisir le manifeste project.json", false, state.settings.paths.localMods)
                        if (manifest != null) {
                            val project = hubJson.decodeFromString<Project>(manifest.jsonText())
                            ProjectRules.validate(project, remote = false)
                            val archive = choose("Choisir l'archive ZIP correspondante", false, manifest.parent.toString())
                            if (archive != null) controller.importDevelopment(project, archive)
                        }
                    }.onFailure { JOptionPane.showMessageDialog(window, it.message, "Paquet local invalide", JOptionPane.ERROR_MESSAGE) }
                },
                restart = { quit(relaunch = true) },
                channel = controller::changeChannel,
                notes = { project -> Desktop.getDesktop().browse(java.net.URI(project.releaseUrl)) },
                choosePath = { key -> choose(key.label, !key.file, state.settings.path(key))?.let { controller.setPath(key, it.toString()) } },
                profile = { controller.selectProfile(it) },
                addLocal = { kind ->
                    val start = if (kind == "tco") state.settings.paths.localTools else state.settings.paths.localMods
                    choose("Ajouter un projet local", true, start)?.let(controller::addLocalProject)
                },
                origin = controller::chooseOrigin,
                compile = controller::compile,
                openIdea = { local -> runCatching {
                    val executable = state.settings.paths.idea.takeIf { it.isNotBlank() }?.let(::Path)
                        ?: choose("Choisir idea64.exe", false)?.also { controller.setPath(PathSetting.IDEA, it.toString()) }
                    if (executable != null) {
                        require(executable.isRegularFile()) { "Exécutable IntelliJ introuvable" }
                        ProcessBuilder(executable.toString(), local.directory).start()
                    }
                }.onFailure { controller.fail(it.message ?: "Impossible d’ouvrir IntelliJ") } },
                forgetLocal = { id -> if (confirm("Retirer ce projet du profil ? Ses sources seront conservées.")) controller.forgetLocalProject(id) },
                launchGame = { restart ->
                    val acknowledged = !state.settings.developing || state.settings.development.sharedDataAcknowledged ||
                        confirm("Les sauvegardes et réglages globaux de NIMBY Rails restent partagés.\nUtilisez une copie de votre partie pour les essais.\n\nContinuer avec le profil Développer ?").also { if (it) controller.acknowledgeSharedData() }
                    if (acknowledged && (!restart || confirm("Sauvegardez votre partie avant de continuer.\n\nLe Hub demandera au jeu de se fermer normalement, appliquera le profil choisi puis relancera NIMBY Rails.\nAucun arrêt forcé ne sera effectué.\n\nRedémarrer le jeu ?"))) controller.launchGame(restart)
                },
                launchTool = controller::launchTool,
                applyProfile = controller::applySelectedProfile,
                loadSdkVersions = controller::loadSdkVersions,
                installSdk = controller::installSdkVersion,
                sdkVersion = controller::chooseSdkVersion,
                clearError = controller::clearError,
                releaseLegacy = { if (confirm("Autoriser à nouveau les mises à jour des anciennes installations ?\nVérifiez d’abord que vos versions locales ont été conservées séparément.")) controller.releaseLegacyProtection() },
                recoverProfile = controller::recoverProfile,
            ))
        }
    }
}

private fun hubIcon(): BufferedImage = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).also { image ->
    image.createGraphics().apply {
        color = Color(0x183746); fillRoundRect(0, 0, 64, 64, 12, 12)
        color = Color(0x75DFC5); stroke = BasicStroke(5f)
        drawLine(22, 12, 22, 52); drawLine(42, 12, 42, 52)
        listOf(20, 32, 44).forEach { drawLine(17, it, 47, it) }; dispose()
    }
}
