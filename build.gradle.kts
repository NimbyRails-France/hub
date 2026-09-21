import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "fr.nimby"
version = file("VERSION").readText().trim()
check(file("src/commonMain/kotlin/fr/nimby/hub/model/Projects.kt").readText().contains("const val HUB_VERSION = \"$version\"")) {
    "HUB_VERSION doit correspondre au fichier VERSION."
}
val numericVersion = providers.gradleProperty("nativePackageVersion").orElse(version.toString().substringBefore('-')).get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(numericVersion)) { "nativePackageVersion doit contenir trois nombres." }
// Preserve old captures, distributions and migration evidence when running clean.
layout.buildDirectory = layout.projectDirectory.dir(if (System.getProperty("os.name").startsWith("Windows")) "build/gradle" else "build/gradle-${System.getProperty("os.name").lowercase().replace(' ', '-')}")

kotlin {
    jvm("desktop")
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val desktopTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("org.apache.commons:commons-compress:1.27.1")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "fr.nimby.hub.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "NRFHub"
            packageVersion = numericVersion
            description = "NimbyRails France Hub"
            vendor = "NimbyRails France"
            linux { packageName = "nrf-hub"; shortcut = true; menuGroup = "Game" }
            macOS { packageVersion = numericVersion.split('.').mapIndexed { index, part -> if (index == 0) part.toInt().coerceAtLeast(1).toString() else part }.joinToString(".") }
            modules("java.net.http", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            windows { menu = true; shortcut = true; upgradeUuid = "53ca0228-8196-4bf5-b77a-49cb07f9f469" }
        }
    }
}

val prepareRuntime by tasks.registering(Sync::class) {
    dependsOn("desktopJar")
    from(tasks.named("desktopJar"))
    from(configurations.named("desktopRuntimeClasspath"))
    into(layout.buildDirectory.dir("runtime"))
}
tasks.register<Exec>("managerIntegrationTest") {
    dependsOn(prepareRuntime)
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tests/manage-tests.ps1")
    environment("NRF_JAVA", javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().executablePath.asFile)
}
tasks.named("check") { dependsOn("managerIntegrationTest") }

tasks.register("collectDependencyNotices") {
    val runtime = configurations.named("desktopRuntimeClasspath")
    val output = layout.buildDirectory.dir("dependency-notices")
    inputs.files(runtime)
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.apply { mkdirs() }
        root.resolve("ARTIFACTS.txt").writeText(runtime.get().resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .joinToString("\n") { "${it.moduleVersion.id}  ${it.file.name}" } + "\n")
        runtime.get().files.filter { it.extension == "jar" }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { entry ->
                    !entry.isDirectory && Regex("(?i).*(license|notice|copying|copyright).*" ).matches(entry.name)
                }.forEach { entry ->
                    val folder = root.resolve(jar.nameWithoutExtension).apply { mkdirs() }
                    val name = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    zip.getInputStream(entry).use { input -> folder.resolve(name).outputStream().use(input::copyTo) }
                }
            }
        }
    }
}

// jpackage's desktop hook assumes this XDG directory exists. Include its
// creation in the DEB itself, so installation also works on minimal WSLg.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    doFirst {
        require('-' !in project.version.toString() || providers.gradleProperty("nativePackageVersion").isPresent) {
            "Une prérelease exige -PnativePackageVersion=X.Y.Z unique et croissante pour l’installateur natif."
        }
    }
    if (targetFormat == TargetFormat.Deb) {
        doLast {
            val deb = destinationDir.get().asFile.listFiles()!!.single { it.extension == "deb" }
            val staging = Files.createTempDirectory("nrf-deb-").toFile()
            fun run(vararg arguments: String) {
                val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { "DEB packaging failed: $output" }
            }
            try {
                run("dpkg-deb", "--raw-extract", deb.absolutePath, staging.absolutePath)
                val preinst = staging.resolve("DEBIAN/preinst")
                preinst.writeText(preinst.readText().replace("set -e", "set -e\nmkdir -p /usr/share/desktop-directories"))
                run("dpkg-deb", "--root-owner-group", "--build", staging.absolutePath, deb.absolutePath)
            } finally { staging.deleteRecursively() }
        }
    }
}

// Produces reviewable local release assets; this task never publishes to GitHub.
tasks.register("prepareRelease") {
    group = "distribution"
    dependsOn("desktopTest", "packageDistributionForCurrentOS")
    doLast {
        val releaseVersion = project.version.toString()
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-(alpha|beta)\\.[1-9][0-9]*)?").matches(releaseVersion))
        val channel = releaseVersion.substringAfter('-', "stable").substringBefore('.')
        val os = when {
            System.getProperty("os.name").startsWith("Windows") -> "windows"
            System.getProperty("os.name").startsWith("Mac") -> "macos"
            else -> "linux"
        }
        val arch = when (System.getProperty("os.arch")) { "amd64", "x86_64" -> "x64"; "aarch64", "arm64" -> "arm64"; else -> error("Architecture inconnue") }
        val platform = "$os-$arch"
        val destination = layout.buildDirectory.dir("release/$releaseVersion/$platform").get().asFile.apply { mkdirs() }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val packages = layout.buildDirectory.dir("compose/binaries/main").get().asFile.walkTopDown()
            .filter { it.isFile && it.extension in setOf("exe", "msi", "deb", "rpm", "dmg") && it.parentFile.name == it.extension }
            .map { source -> source.copyTo(destination.resolve("NRFHub-$releaseVersion-$platform.${source.extension}"), overwrite = true) }.toList()
        require(packages.isNotEmpty()) { "Aucun installateur natif produit" }
        packages.forEach { destination.resolve("${it.name}.sha256").writeText("${hash(it)}  ${it.name}\n") }
        val extension = when (os) { "windows" -> "exe"; "macos" -> "dmg"; else -> "deb" }
        val installer = packages.single { it.extension == extension }
        val manifest = mapOf("schema" to 1, "product" to "NRFHub", "version" to releaseVersion,
            "platform" to platform, "channel" to channel, "installer" to if (os == "windows") "jpackage-exe" else extension,
            "url" to "https://github.com/NimbyRails-France/hub/releases/download/v$releaseVersion/${installer.name}",
            "size" to installer.length(), "sha256" to hash(installer))
        destination.resolve("hub-latest-$platform.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)) + "\n")
        logger.lifecycle("Release $releaseVersion / $channel / $platform prête dans $destination")
    }
}
