package com.focusflow.enforcement

import com.focusflow.data.models.CanonicalAppReference
import java.util.Locale

enum class MinecraftAttributionStatus {
    ATTRIBUTED,
    NOT_ATTRIBUTED,
    INSUFFICIENT_DATA
}


enum class MinecraftAttributionMode {
    DIRECT_RUNTIME,
    LAUNCH_SESSION
}

enum class MinecraftRuntimeVariant {
    VANILLA,
    FABRIC,
    QUILT,
    FORGE,
    NEOFORGE
}

/**
 * Session facts are supplied by launch capture. Relationship evidence alone is
 * never enough: the selected target, active session, Java runtime, and
 * Minecraft-specific launch facts are also required.
 */
data class MinecraftLaunchSessionEvidence(
    val selectedMinecraftReference: Boolean,
    val launchDefinitionArmed: Boolean,
    val activeLaunchSession: Boolean,
    val processCreatedOrExecChanged: Boolean,
    val relatedToLauncher: Boolean,
    val previouslyAssociated: Boolean = false,
    val unchangedLauncherImage: Boolean = false
) {
    val isEligible: Boolean
        get() = selectedMinecraftReference &&
            launchDefinitionArmed &&
            activeLaunchSession &&
            (processCreatedOrExecChanged || previouslyAssociated) &&
            relatedToLauncher &&
            !unchangedLauncherImage
}

data class MinecraftAttributionResult(
    val status: MinecraftAttributionStatus,
    val mode: MinecraftAttributionMode? = null,
    val runtimeVariant: MinecraftRuntimeVariant? = null,
    val evidence: List<String> = emptyList()
) {
    val isAttributed: Boolean
        get() = status == MinecraftAttributionStatus.ATTRIBUTED

    fun explanation(): String = when {
        !isAttributed -> when (status) {
            MinecraftAttributionStatus.NOT_ATTRIBUTED ->
                "No Minecraft-specific runtime evidence was found."
            MinecraftAttributionStatus.INSUFFICIENT_DATA ->
                "Runtime facts are insufficient to attribute Minecraft."
            MinecraftAttributionStatus.ATTRIBUTED -> ""
        }
        mode == MinecraftAttributionMode.DIRECT_RUNTIME ->
            "Minecraft attributed by a recognized direct runtime signature" +
                runtimeVariant?.let { " (${it.name.lowercase(Locale.ROOT)})" }.orEmpty() +
                "."
        mode == MinecraftAttributionMode.LAUNCH_SESSION ->
            "Minecraft attributed through the active selected launch session."
        else -> "Minecraft attribution is unavailable."
    }
}

/**
 * Identifies Minecraft only from Java launch semantics or the conjunction of
 * explicit Minecraft selection, a live related launch session, and structured
 * Minecraft launch facts. Desktop/package catalog correlation is deliberately
 * not an input to this authorization-facing result.
 */
object MinecraftRuntimeAttributor {
    private val vanillaMainClasses = setOf("net.minecraft.client.main.Main")
    private val fabricMainClasses = setOf(
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "net.fabricmc.loader.launch.knot.KnotClient"
    )
    private val quiltMainClasses = setOf(
        "org.quiltmc.loader.impl.launch.knot.KnotClient"
    )
    private val forgeTweakers = setOf(
        "net.minecraftforge.fml.common.launcher.FMLTweaker"
    )
    private val bootstrapLaunchers = setOf(
        "cpw.mods.bootstraplauncher.BootstrapLauncher",
        "cpw.mods.modlauncher.Launcher"
    )

    fun attribute(
        context: ProcessSelectorContext,
        launchSession: MinecraftLaunchSessionEvidence? = null
    ): MinecraftAttributionResult =
        attribute(
            process = context.process,
            runtimeMetadata = context.runtimeMetadata,
            launchSession = launchSession
        )

    fun attribute(
        process: LinuxProcessSnapshot,
        runtimeMetadata: ProcessRuntimeMetadata = ProcessRuntimeMetadata.from(process),
        launchSession: MinecraftLaunchSessionEvidence? = null
    ): MinecraftAttributionResult {
        if (runtimeMetadata.runtimeFamily != RuntimeFamily.JAVA) {
            return MinecraftAttributionResult(
                status = if (runtimeMetadata.runtimeFamily == RuntimeFamily.UNKNOWN) {
                    MinecraftAttributionStatus.INSUFFICIENT_DATA
                } else {
                    MinecraftAttributionStatus.NOT_ATTRIBUTED
                },
                evidence = listOf("The observed process is not a confirmed Java runtime.")
            )
        }

        val javaLaunch = runtimeMetadata.javaLaunch
        val directVariant = directVariant(javaLaunch)
        if (directVariant != null) {
            return MinecraftAttributionResult(
                status = MinecraftAttributionStatus.ATTRIBUTED,
                mode = MinecraftAttributionMode.DIRECT_RUNTIME,
                runtimeVariant = directVariant,
                evidence = listOf(
                    "Java runtime family is confirmed.",
                    "Java launch entry point matches a supported Minecraft runtime."
                )
            )
        }

        if (
            launchSession?.isEligible == true &&
            !javaLaunch.minecraftArguments.isAmbiguous &&
            javaLaunch.minecraftArguments.hasGameDirectoryAndSupportingFact
        ) {
            return MinecraftAttributionResult(
                status = MinecraftAttributionStatus.ATTRIBUTED,
                mode = MinecraftAttributionMode.LAUNCH_SESSION,
                evidence = listOf(
                    "The selected application is explicitly identified as Minecraft.",
                    "A launch definition and active launch session are present.",
                    "The process was created or image-changed and is related to that launcher.",
                    "Java launch facts include a game directory and an independent supporting argument."
                )
            )
        }

        val hasUnavailableArguments =
            process.fieldStatuses[LinuxProcessField.ARGV] != LinuxProcessFieldStatus.AVAILABLE
        return MinecraftAttributionResult(
            status = if (
                hasUnavailableArguments ||
                javaLaunch.state == JavaLaunchMetadataState.UNAVAILABLE ||
                javaLaunch.state == JavaLaunchMetadataState.MALFORMED_OR_AMBIGUOUS
            ) {
                MinecraftAttributionStatus.INSUFFICIENT_DATA
            } else {
                MinecraftAttributionStatus.NOT_ATTRIBUTED
            },
            evidence = listOf(
                "No supported direct Minecraft entry point was recognized.",
                if (launchSession?.isEligible == true) {
                    "The launch session lacks the required combination of structured Minecraft arguments."
                } else {
                    "No eligible selected Minecraft launch-session evidence is available."
                }
            )
        )
    }

    /**
     * Builds a serializable selector from the exact evidence that produced the
     * attribution. It never degrades to Java executable or game-directory-only
     * matching.
     */
    fun selectorExpression(
        result: MinecraftAttributionResult,
        metadata: ProcessRuntimeMetadata
    ): SelectorExpression? {
        if (!result.isAttributed) return null
        val clauses = mutableListOf<SelectorExpression>(
            SelectorExpression.Predicate(
                RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.JAVA)
            )
        )
        val javaLaunch = metadata.javaLaunch
        when (result.mode) {
            MinecraftAttributionMode.DIRECT_RUNTIME -> {
                if (result.runtimeVariant == null) return null
                val mainClass = javaLaunch.mainClass ?: return null
                clauses += SelectorExpression.Predicate(RuntimeSelector.MainClass(mainClass))
                when (result.runtimeVariant) {
                    MinecraftRuntimeVariant.FORGE,
                    MinecraftRuntimeVariant.NEOFORGE -> {
                        val target = javaLaunch.minecraftArguments.launchTarget
                            ?.takeIf {
                                it.lowercase(Locale.ROOT) in
                                    setOf("forgeclient", "neoforgeclient")
                            }
                        val tweak = javaLaunch.minecraftArguments.tweakClasses
                            .firstOrNull { it in forgeTweakers }
                        val discriminator = when {
                            target != null ->
                                RuntimeSelector.ArgumentEquals("--launchTarget", target)
                            tweak != null ->
                                RuntimeSelector.ArgumentEquals("--tweakClass", tweak)
                            else -> return null
                        }
                        clauses += SelectorExpression.Predicate(discriminator)
                    }
                    else -> Unit
                }
            }
            MinecraftAttributionMode.LAUNCH_SESSION -> {
                val arguments = javaLaunch.minecraftArguments
                val gameDirectory = arguments.gameDirectory
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                val supportingArgument = arguments.supportingArgument() ?: return null
                clauses += SelectorExpression.Predicate(
                    RuntimeSelector.ArgumentEquals(
                        name = "--gameDir",
                        expectedValue = gameDirectory,
                        valueKind = SelectorValueKind.PATH
                    )
                )
                clauses += SelectorExpression.Predicate(
                    RuntimeSelector.ArgumentEquals(
                        name = supportingArgument.first,
                        expectedValue = supportingArgument.second,
                        valueKind = if (
                            supportingArgument.first == "--assetsDir"
                        ) {
                            SelectorValueKind.PATH
                        } else {
                            SelectorValueKind.TEXT
                        }
                    )
                )
            }
            null -> return null
        }
        return SelectorExpression.All(clauses)
    }

    private fun directVariant(
        launch: JavaLaunchMetadata
    ): MinecraftRuntimeVariant? {
        val mainClass = launch.mainClass ?: return null
        val arguments = launch.minecraftArguments
        if (mainClass in vanillaMainClasses) return MinecraftRuntimeVariant.VANILLA
        if (mainClass in fabricMainClasses) return MinecraftRuntimeVariant.FABRIC
        if (mainClass in quiltMainClasses) return MinecraftRuntimeVariant.QUILT

        val launchTarget = arguments.launchTarget?.lowercase(Locale.ROOT)
        if (mainClass in bootstrapLaunchers) {
            return when (launchTarget) {
                "forgeclient" -> MinecraftRuntimeVariant.FORGE
                "neoforgeclient" -> MinecraftRuntimeVariant.NEOFORGE
                else -> null
            }
        }
        if (
            mainClass == "net.minecraft.launchwrapper.Launch" &&
            arguments.tweakClasses.any { it in forgeTweakers }
        ) {
            return MinecraftRuntimeVariant.FORGE
        }
        return null
    }
}

/**
 * The app name is only a launch-session selection hint. Process attribution
 * still requires Java and Minecraft-specific runtime evidence.
 */
object MinecraftReferenceClassifier {
    private val acceptedNames = setOf(
        "minecraft",
        "minecraftlauncher",
        "minecraftjavaedition",
        "minecraftjavaeditionlauncher",
        "netminecraftlauncher",
        "minecraftlauncherdesktop",
        "commojangminecraft"
    )

    fun isMinecraft(
        reference: CanonicalAppReference,
        fallbackDisplayName: String? = null
    ): Boolean {
        val knownNames = listOfNotNull(reference.displayName, fallbackDisplayName)
        if (knownNames.any { normalize(it) in acceptedNames }) return true
        return reference.stableAppId
            ?.let(::normalize)
            ?.let { it in acceptedNames }
            ?: false
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}