package com.focusflow.enforcement

import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.CanonicalAppReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxMinecraftAttributionTest {
    @Test
    fun `runtime family and execution environment are derived independently`() {
        val flatpakJava = snapshot(
            executablePath = "/usr/lib/jvm/java/bin/java",
            argv = listOf("/usr/lib/jvm/java/bin/java", "-version"),
            cgroupPath = "/user.slice/app-flatpak-org.example.Game.scope"
        )
        val metadata = ProcessRuntimeMetadata.from(flatpakJava)

        assertEquals(RuntimeFamily.JAVA, metadata.runtimeFamily)
        assertEquals(ExecutionEnvironment.FLATPAK, metadata.executionEnvironment)

        val containerPython = snapshot(
            executablePath = "/usr/bin/python3.12",
            argv = listOf("/usr/bin/python3.12", "-m", "http.server"),
            cgroupPath = "/system.slice/docker-abc123.scope"
        )
        val pythonMetadata = ProcessRuntimeMetadata.from(containerPython)
        assertEquals(RuntimeFamily.PYTHON, pythonMetadata.runtimeFamily)
        assertEquals(ExecutionEnvironment.CONTAINER, pythonMetadata.executionEnvironment)

        val flatpakWrapper = snapshot(
            executablePath = "/usr/bin/flatpak",
            argv = listOf("/usr/bin/flatpak", "run", "org.example.Game"),
            cgroupPath = "/user.slice"
        )
        val wrapperMetadata = ProcessRuntimeMetadata.from(flatpakWrapper)
        assertEquals(RuntimeFamily.OTHER, wrapperMetadata.runtimeFamily)
        assertEquals(ExecutionEnvironment.FLATPAK, wrapperMetadata.executionEnvironment)

        val snapProcess = snapshot(
            executablePath = "/usr/bin/firefox",
            argv = listOf("/usr/bin/firefox"),
            cgroupPath = "/user.slice/snap.firefox.firefox.scope"
        )
        assertEquals(
            ExecutionEnvironment.SNAP,
            ProcessRuntimeMetadata.from(snapProcess).executionEnvironment
        )

        val appImageProcess = snapshot(
            executablePath = "/tmp/.mount_focusflow/usr/bin/java",
            argv = listOf("/tmp/.mount_focusflow/usr/bin/java", "-version"),
            cgroupPath = "/user.slice"
        )
        assertEquals(
            ExecutionEnvironment.APPIMAGE,
            ProcessRuntimeMetadata.from(appImageProcess).executionEnvironment
        )
    }

    @Test
    fun `Java launch parsing separates launcher syntax from safe application facts`() {
        val classLaunch = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-Xmx2G",
                "--class-path=/opt/minecraft/libraries/*",
                "net.minecraft.client.main.Main",
                "--gameDir",
                "/home/user/instances/Survival",
                "--assetsDir=/home/user/assets",
                "--version=1.20.6",
                "--accessToken",
                "secret-token-value"
            )
        )
        val metadata = ProcessRuntimeMetadata.from(classLaunch)

        assertEquals("net.minecraft.client.main.Main", metadata.javaLaunch.mainClass)
        assertEquals(JavaLaunchKind.CLASS, metadata.javaLaunch.launchKind)
        assertEquals(
            "/home/user/instances/Survival",
            metadata.javaLaunch.minecraftArguments.gameDirectory
        )
        assertEquals(
            "/home/user/assets",
            metadata.javaLaunch.minecraftArguments.assetsDirectory
        )
        assertEquals("1.20.6", metadata.javaLaunch.minecraftArguments.version)
        assertFalse(metadata.toString().contains("secret-token-value"))
        assertFalse(metadata.toString().contains("/home/user/instances/Survival"))

        val jarLaunch = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-jar",
                "/opt/launcher/runtime.jar",
                "--gameDir=/home/user/game",
                "--version",
                "1.21"
            )
        )
        val jarMetadata = ProcessRuntimeMetadata.from(jarLaunch)
        assertNull(jarMetadata.javaLaunch.mainClass)
        assertEquals(JavaLaunchKind.JAR, jarMetadata.javaLaunch.launchKind)
        assertEquals(
            "/home/user/game",
            jarMetadata.javaLaunch.minecraftArguments.gameDirectory
        )
        assertFalse(jarMetadata.toString().contains("runtime.jar"))

        val moduleLaunch = snapshot(
            argv = listOf("/usr/bin/java", "-m", "game.module/org.example.Main", "--version=1")
        )
        val moduleMetadata = ProcessRuntimeMetadata.from(moduleLaunch)
        assertEquals("org.example.Main", moduleMetadata.javaLaunch.mainClass)
        assertEquals(JavaLaunchKind.MODULE, moduleMetadata.javaLaunch.launchKind)
    }

    @Test
    fun `Java Minecraft selectors treat option value and equals forms equivalently`() {
        val separated = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "net.minecraft.client.main.Main",
                "--gameDir",
                "/home/user/game"
            )
        )
        val equals = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-classpath",
                "libraries/*",
                "net.minecraft.client.main.Main",
                "--gameDir=/home/user/game"
            )
        )
        val selector = RuntimeSelector.ArgumentEquals(
            name = "--gameDir",
            expectedValue = "/home/user/game",
            valueKind = SelectorValueKind.PATH
        )

        listOf(separated, equals).forEach { process ->
            assertEquals(
                "/home/user/game",
                ProcessRuntimeMetadata.from(process).javaLaunch
                    .minecraftArguments.gameDirectory
            )
            assertEquals(
                SelectorMatchStatus.MATCH,
                LinuxProcessSelectorMatcher.evaluate(
                    SelectorExpression.Predicate(selector),
                    ProcessSelectorContext(process)
                ).status
            )
        }
    }

    @Test
    fun `direct Minecraft attribution recognizes vanilla and supported loaders`() {
        val variants = listOf(
            listOf("/usr/bin/java", "-cp", "libraries/*", "net.minecraft.client.main.Main") to
                MinecraftRuntimeVariant.VANILLA,
            listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "net.fabricmc.loader.impl.launch.knot.KnotClient"
            ) to MinecraftRuntimeVariant.FABRIC,
            listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "org.quiltmc.loader.impl.launch.knot.KnotClient"
            ) to MinecraftRuntimeVariant.QUILT,
            listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "cpw.mods.bootstraplauncher.BootstrapLauncher",
                "--launchTarget=ForgeClient"
            ) to MinecraftRuntimeVariant.FORGE,
            listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "cpw.mods.modlauncher.Launcher",
                "--launchTarget",
                "neoforgeclient"
            ) to MinecraftRuntimeVariant.NEOFORGE,
            listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "net.minecraft.launchwrapper.Launch",
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker"
            ) to MinecraftRuntimeVariant.FORGE
        )

        variants.forEach { (argv, variant) ->
            val result = MinecraftRuntimeAttributor.attribute(snapshot(argv = argv))
            assertEquals(MinecraftAttributionStatus.ATTRIBUTED, result.status, argv.toString())
            assertEquals(MinecraftAttributionMode.DIRECT_RUNTIME, result.mode)
            assertEquals(variant, result.runtimeVariant)
            assertTrue(result.evidence.isNotEmpty())
            val expression = MinecraftRuntimeAttributor.selectorExpression(
                result,
                ProcessRuntimeMetadata.from(snapshot(argv = argv))
            )
            assertNotNull(expression)
            assertEquals(
                SelectorMatchStatus.MATCH,
                LinuxProcessSelectorMatcher.evaluate(
                    expression,
                    ProcessSelectorContext(snapshot(argv = argv))
                ).status
            )
        }
    }

    @Test
    fun `game directory alone and verified catalog correlation do not identify Minecraft`() {
        val process = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-cp",
                "tools/*",
                "org.example.Renderer",
                "--gameDir=/home/user/.minecraft"
            )
        )
        val catalogCorrelatedContext = ProcessSelectorContext(
            process = process,
            runtimeMetadata = ProcessRuntimeMetadata.from(process),
            applicationCorrelation = ProcessApplicationCorrelation(
                desktopId = "minecraft-launcher.desktop",
                packageId = "com.mojang.Minecraft",
                status = ProcessCorrelationStatus.VERIFIED
            )
        )

        val result = MinecraftRuntimeAttributor.attribute(catalogCorrelatedContext)

        assertEquals(MinecraftAttributionStatus.NOT_ATTRIBUTED, result.status)
        assertNull(result.mode)
        assertFalse(result.isAttributed)
    }

    @Test
    fun `unrelated Java remains unauthorized by a Minecraft runtime selector`() {
        val minecraft = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-cp",
                "libraries/*",
                "net.minecraft.client.main.Main"
            )
        )
        val minecraftMetadata = ProcessRuntimeMetadata.from(minecraft)
        val directAttribution = MinecraftRuntimeAttributor.attribute(
            minecraft,
            minecraftMetadata
        )
        val selector = MinecraftRuntimeAttributor.selectorExpression(
            directAttribution,
            minecraftMetadata
        )
        assertNotNull(selector)
        val candidate = ApplicationRuntimeCandidate(
            applicationReferenceId = "minecraft-reference",
            runtimeDefinitionId = "minecraft-runtime",
            expression = selector
        )
        val unrelatedJava = snapshot(
            pid = 401,
            argv = listOf(
                "/usr/bin/java",
                "-cp",
                "tools/*",
                "org.example.Worker"
            )
        )

        val decision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(unrelatedJava),
            listOf(candidate)
        )

        assertEquals(AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE, decision.outcome)
        assertEquals(
            ProcessAttributionStatus.NOT_MATCHED,
            decision.attribution.status
        )
    }

    @Test
    fun `launch session mode requires selected active related Java and multiple facts`() {
        val process = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-jar",
                "/opt/launcher/runtime.jar",
                "--gameDir",
                "/home/user/.minecraft",
                "--assetsDir=/home/user/.minecraft/assets",
                "--version=1.21"
            )
        )
        val eligibleSession = MinecraftLaunchSessionEvidence(
            selectedMinecraftReference = true,
            launchDefinitionArmed = true,
            activeLaunchSession = true,
            processCreatedOrExecChanged = true,
            relatedToLauncher = true
        )
        val result = MinecraftRuntimeAttributor.attribute(
            process,
            ProcessRuntimeMetadata.from(process),
            eligibleSession
        )

        assertEquals(MinecraftAttributionStatus.ATTRIBUTED, result.status)
        assertEquals(MinecraftAttributionMode.LAUNCH_SESSION, result.mode)
        assertEquals(
            listOf(
                "The selected application is explicitly identified as Minecraft.",
                "A launch definition and active launch session are present.",
                "The process was created or image-changed and is related to that launcher.",
                "Java launch facts include a game directory and an independent supporting argument."
            ),
            result.evidence
        )

        val weakArguments = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-jar",
                "/opt/launcher/runtime.jar",
                "--gameDir=/home/user/.minecraft"
            )
        )
        assertEquals(
            MinecraftAttributionStatus.NOT_ATTRIBUTED,
            MinecraftRuntimeAttributor.attribute(
                weakArguments,
                ProcessRuntimeMetadata.from(weakArguments),
                eligibleSession
            ).status
        )
        assertEquals(
            MinecraftAttributionStatus.NOT_ATTRIBUTED,
            MinecraftRuntimeAttributor.attribute(
                process,
                ProcessRuntimeMetadata.from(process),
                eligibleSession.copy(relatedToLauncher = false)
            ).status
        )
        assertEquals(
            MinecraftAttributionStatus.NOT_ATTRIBUTED,
            MinecraftRuntimeAttributor.attribute(
                process,
                ProcessRuntimeMetadata.from(process),
                eligibleSession.copy(selectedMinecraftReference = false)
            ).status
        )
        listOf(
            eligibleSession.copy(launchDefinitionArmed = false),
            eligibleSession.copy(activeLaunchSession = false),
            eligibleSession.copy(processCreatedOrExecChanged = false),
            eligibleSession.copy(unchangedLauncherImage = true)
        ).forEach { insufficientSession ->
            assertEquals(
                MinecraftAttributionStatus.NOT_ATTRIBUTED,
                MinecraftRuntimeAttributor.attribute(
                    process,
                    ProcessRuntimeMetadata.from(process),
                    insufficientSession
                ).status
            )
        }

        val selector = MinecraftRuntimeAttributor.selectorExpression(
            result,
            ProcessRuntimeMetadata.from(process)
        )
        assertNotNull(selector)
        assertEquals(
            SelectorMatchStatus.MATCH,
            LinuxProcessSelectorMatcher.evaluate(
                selector,
                ProcessSelectorContext(process)
            ).status
        )
        val otherGameDirectory = snapshot(
            argv = process.argv.map {
                if (it == "/home/user/.minecraft") "/tmp/another-game" else it
            }
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            LinuxProcessSelectorMatcher.evaluate(
                selector,
                ProcessSelectorContext(otherGameDirectory)
            ).status
        )
    }

    @Test
    fun `unknown Java application options cannot turn their values into Minecraft evidence`() {
        val process = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "-jar",
                "/opt/launcher/runtime.jar",
                "--forwardedOption",
                "--gameDir",
                "/home/user/.minecraft",
                "--assetsDir",
                "/home/user/.minecraft/assets"
            )
        )
        val metadata = ProcessRuntimeMetadata.from(process)
        val session = MinecraftLaunchSessionEvidence(
            selectedMinecraftReference = true,
            launchDefinitionArmed = true,
            activeLaunchSession = true,
            processCreatedOrExecChanged = true,
            relatedToLauncher = true
        )

        assertTrue(metadata.javaLaunch.minecraftArguments.isAmbiguous)
        assertEquals(
            MinecraftAttributionStatus.NOT_ATTRIBUTED,
            MinecraftRuntimeAttributor.attribute(process, metadata, session).status
        )
    }

    @Test
    fun `only explicit Minecraft references enable session-mode selection`() {
        val minecraft = reference("Minecraft Launcher", "net.minecraft.launcher")
        val unrelated = reference("Java IDE", "org.example.ide")

        assertTrue(MinecraftReferenceClassifier.isMinecraft(minecraft))
        assertFalse(MinecraftReferenceClassifier.isMinecraft(unrelated))
        assertTrue(
            MinecraftReferenceClassifier.isMinecraft(
                reference("Unknown app", "net.minecraft.launcher").copy(displayName = null)
            )
        )
        assertTrue(
            MinecraftReferenceClassifier.isMinecraft(
                unrelated.copy(displayName = null),
                fallbackDisplayName = "Minecraft"
            )
        )
    }

    private fun reference(name: String, stableId: String) = CanonicalAppReference(
        referenceId = "reference-$stableId",
        stableAppId = stableId,
        displayName = name,
        source = AppReferenceSource.CATALOG_NATIVE,
        resolutionStatus = AppResolutionStatus.RESOLVED
    )

    private fun snapshot(
        pid: Long = 400L,
        executablePath: String = "/usr/bin/java",
        argv: List<String> = listOf(executablePath, "-version"),
        cgroupPath: String? = "/user.slice"
    ): LinuxProcessSnapshot {
        val fields = LinuxProcessField.values().associateWith {
            LinuxProcessFieldStatus.AVAILABLE
        }
        return LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = 100L,
            uid = 1000L,
            parentPid = 1L,
            processGroupId = pid,
            sessionId = pid,
            comm = executablePath.substringAfterLast('/'),
            executablePath = executablePath,
            executableBasename = executablePath.substringAfterLast('/'),
            argv = argv,
            workingDirectory = "/home/user",
            cgroupPath = cgroupPath,
            ioState = LinuxProcessIoState.AVAILABLE,
            fieldStatuses = fields
        )
    }
}
