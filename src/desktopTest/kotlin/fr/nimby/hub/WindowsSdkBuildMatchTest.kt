package fr.nimby.hub

import fr.nimby.hub.platform.windows.WindowsSdkBuildMatch
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class WindowsSdkBuildMatchTest {
    @Test fun rebuiltAlphaMustNotUseOlderRuntimeWithTheSameVersion() {
        val root = Files.createTempDirectory("nrf-sdk-build-match-")
        val kit = root.resolve("kit").createDirectory()
        val runtime = root.resolve("runtime").createDirectory()
        kit.resolve("bin").createDirectory().resolve("NimbyRailsFranceSDK.dll").writeText("new exports")
        kit.resolve("sdk.json").writeText("""{"sdkVersion":"0.8.0-alpha.1"}""")
        val dll = runtime.resolve("loader").createDirectory().resolve("NimbyRailsFranceSDK.dll")
        dll.writeText("old exports")
        val failure = assertFailsWith<IllegalArgumentException> { WindowsSdkBuildMatch.requireSameBuild(kit, runtime) }
        assertContains(failure.message.orEmpty(), "builds différents")
        dll.writeText("new exports")
        WindowsSdkBuildMatch.requireSameBuild(kit, runtime)
    }

    @Test fun missingRuntimeDoesNotAuthorizeActivation() {
        val root = Files.createTempDirectory("nrf-sdk-build-missing-")
        val kit = root.resolve("kit").createDirectory()
        val runtime = root.resolve("runtime").createDirectory()
        kit.resolve("bin").createDirectory().resolve("NimbyRailsFranceSDK.dll").writeText("SDK")
        assertFailsWith<IllegalArgumentException> { WindowsSdkBuildMatch.requireSameBuild(kit, runtime) }
    }
}
