package com.focusflow.enforcement

import java.nio.file.Path
import java.util.Locale

enum class JavaLaunchMetadataState {
    KNOWN_MAIN_CLASS,
    JAR_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS,
    MODULE_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS,
    UNAVAILABLE,
    MALFORMED_OR_AMBIGUOUS
}

enum class JavaLaunchKind {
    CLASS,
    JAR,
    MODULE,
    UNKNOWN
}

/**
 * Safe, structured Minecraft launch facts derived from Java application argv.
 * Values are available to in-memory matchers but are intentionally omitted from
 * diagnostics because paths and profile metadata can be sensitive.
 */
data class MinecraftJavaLaunchArguments(
    val gameDirectory: String? = null,
    val assetsDirectory: String? = null,
    val version: String? = null,
    val versionType: String? = null,
    val assetIndex: String? = null,
    val launchTarget: String? = null,
    val tweakClasses: Set<String> = emptySet(),
    val isAmbiguous: Boolean = false
) {
    val hasGameDirectoryAndSupportingFact: Boolean
        get() = gameDirectory != null && supportingArgument() != null

    fun supportingArgument(): Pair<String, String>? =
        sequenceOf(
            "--assetsDir" to assetsDirectory,
            "--version" to version,
            "--assetIndex" to assetIndex,
            "--versionType" to versionType,
            "--launchTarget" to launchTarget
        ).firstNotNullOfOrNull { (name, value) ->
            value?.let { name to it }
        } ?: tweakClasses.firstOrNull()?.let { "--tweakClass" to it }

    override fun toString(): String =
        "MinecraftJavaLaunchArguments(" +
            "gameDirectoryPresent=${gameDirectory != null}, " +
            "assetsDirectoryPresent=${assetsDirectory != null}, " +
            "versionPresent=${version != null}, " +
            "versionTypePresent=${versionType != null}, " +
            "assetIndexPresent=${assetIndex != null}, " +
            "launchTargetPresent=${launchTarget != null}, " +
            "tweakClassCount=${tweakClasses.size}, isAmbiguous=$isAmbiguous)"
}

/**
 * Semantic Java launch facts derived from argv. Raw argv is deliberately not
 * retained in this layer or in its diagnostics.
 */
data class JavaLaunchMetadata(
    val mainClass: String? = null,
    val state: JavaLaunchMetadataState = JavaLaunchMetadataState.UNAVAILABLE,
    val launchKind: JavaLaunchKind = JavaLaunchKind.UNKNOWN,
    val minecraftArguments: MinecraftJavaLaunchArguments = MinecraftJavaLaunchArguments()
) {
    override fun toString(): String =
        "JavaLaunchMetadata(mainClass=$mainClass, state=$state, " +
            "launchKind=$launchKind, minecraftArguments=$minecraftArguments)"
}

/**
 * Derived interpretation of one raw process snapshot. Catalog identities are
 * kept in ProcessApplicationCorrelation, not folded into runtime metadata.
 */
data class ProcessRuntimeMetadata(
    val runtimeFamily: RuntimeFamily,
    val executionEnvironment: ExecutionEnvironment?,
    val javaLaunch: JavaLaunchMetadata
) {
    companion object {
        fun from(
            process: LinuxProcessSnapshot,
            executionEnvironment: ExecutionEnvironment? = null
        ): ProcessRuntimeMetadata {
            val executablePathAvailable =
                process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] ==
                    LinuxProcessFieldStatus.AVAILABLE
            val executableName = process.executableBasename
                ?.takeIf { executablePathAvailable }
                ?: process.executablePath
                    ?.takeIf { executablePathAvailable }
                    ?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
                ?: process.comm
                    ?.takeIf {
                        process.fieldStatuses[LinuxProcessField.COMM] ==
                            LinuxProcessFieldStatus.AVAILABLE
                    }
            val family = detectRuntimeFamily(executableName)
            val argvAvailable =
                process.fieldStatuses[LinuxProcessField.ARGV] == LinuxProcessFieldStatus.AVAILABLE
            val javaLaunch = if (family == RuntimeFamily.JAVA) {
                JavaLaunchArgumentInterpreter.parse(process.argv, argvAvailable)
            } else {
                JavaLaunchMetadata()
            }
            return ProcessRuntimeMetadata(
                runtimeFamily = family,
                executionEnvironment = executionEnvironment ?: detectExecutionEnvironment(process),
                javaLaunch = javaLaunch
            )
        }

        private fun detectExecutionEnvironment(
            process: LinuxProcessSnapshot
        ): ExecutionEnvironment? {
            val cgroupAvailable =
                process.fieldStatuses[LinuxProcessField.CGROUP_PATH] ==
                    LinuxProcessFieldStatus.AVAILABLE
            val cgroupSegments = if (cgroupAvailable) {
                process.cgroupPath.orEmpty()
                    .lowercase(Locale.ROOT)
                    .split('/')
                    .filter(String::isNotBlank)
            } else {
                emptyList()
            }
            when {
                cgroupSegments.any { it.startsWith("app-flatpak-") } ->
                    return ExecutionEnvironment.FLATPAK
                cgroupSegments.any { it.startsWith("snap.") || it.startsWith("snap-") } ->
                    return ExecutionEnvironment.SNAP
                cgroupSegments.any {
                    it == "docker" ||
                        it == "kubepods" ||
                        it == "lxc" ||
                        it.startsWith("docker-") ||
                        it.startsWith("libpod-") ||
                        it.startsWith("cri-containerd-") ||
                        it.startsWith("lxc.")
                } -> return ExecutionEnvironment.CONTAINER
            }

            val executablePath = process.executablePath
                ?.takeIf {
                    process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] ==
                        LinuxProcessFieldStatus.AVAILABLE
                }
                ?.lowercase(Locale.ROOT)
            if (
                executablePath?.endsWith(".appimage") == true ||
                executablePath?.contains("/.mount_") == true
            ) {
                return ExecutionEnvironment.APPIMAGE
            }

            if (
                process.fieldStatuses[LinuxProcessField.ARGV] ==
                LinuxProcessFieldStatus.AVAILABLE
            ) {
                val executablePathAvailable =
                    process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] ==
                        LinuxProcessFieldStatus.AVAILABLE
                val executableName = process.executableBasename
                    ?.takeIf { executablePathAvailable }
                    ?.lowercase(Locale.ROOT)
                    ?: process.executablePath
                        ?.takeIf { executablePathAvailable }
                        ?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
                        ?.lowercase(Locale.ROOT)
                val argvExecutable = process.argv.firstOrNull()
                    ?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
                    ?.lowercase(Locale.ROOT)
                if (
                    executableName != null &&
                    argvExecutable == executableName &&
                    process.argv.getOrNull(1) == "run"
                ) {
                    when (executableName) {
                        "flatpak" -> return ExecutionEnvironment.FLATPAK
                        "snap" -> return ExecutionEnvironment.SNAP
                    }
                }
            }
            return null
        }

        private fun detectRuntimeFamily(executableName: String?): RuntimeFamily {
            val name = executableName
                ?.substringAfterLast('/')
                ?.lowercase(Locale.ROOT)
                ?.removeSuffix(".exe")
                ?: return RuntimeFamily.UNKNOWN
            return when {
                name in setOf("java", "javaw", "kotlin", "kotlinc", "jshell") ->
                    RuntimeFamily.JAVA
                name == "python" || name.matches(Regex("python[0-9.]*")) -> RuntimeFamily.PYTHON
                name in setOf("node", "nodejs") -> RuntimeFamily.NODE
                name in setOf("bash", "sh", "zsh", "dash", "fish", "csh", "tcsh") ->
                    RuntimeFamily.SHELL
                name == "ruby" -> RuntimeFamily.RUBY
                name in setOf("dotnet", "mono") -> RuntimeFamily.DOTNET
                name.isBlank() -> RuntimeFamily.UNKNOWN
                else -> RuntimeFamily.OTHER
            }
        }
    }
}

/**
 * Interprets only known Java launcher syntax. Unknown options or incomplete
 * structures do not cause a class-looking argv token to be guessed as main.
 */
object JavaLaunchArgumentInterpreter {
    private val optionsWithNextValue = setOf(
        "-cp",
        "-classpath",
        "--class-path",
        "-p",
        "--module-path",
        "--upgrade-module-path",
        "--add-modules",
        "--limit-modules",
        "--add-exports",
        "--add-opens",
        "--add-reads",
        "--patch-module",
        "--enable-native-access",
        "--source"
    )

    private val booleanJvmOptions = setOf(
        "-ea",
        "-enableassertions",
        "-da",
        "-disableassertions",
        "-esa",
        "-enablesystemassertions",
        "-dsa",
        "-disablesystemassertions",
        "-server",
        "-client",
        "-showversion",
        "-version",
        "-help",
        "--help",
        "-?",
        "--version",
        "--dry-run",
        "--disable-@files",
        "--enable-preview"
    )

    fun parse(argv: List<String>, argvAvailable: Boolean): JavaLaunchMetadata {
        if (!argvAvailable || argv.isEmpty()) return JavaLaunchMetadata()

        // Linux cmdline includes argv[0], which is the Java launcher executable.
        var index = 1
        while (index < argv.size) {
            val token = argv[index]
            when {
                token == "-jar" -> {
                    if (argv.getOrNull(index + 1).isNullOrBlank()) return malformed()
                    return JavaLaunchMetadata(
                        state = JavaLaunchMetadataState.JAR_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS,
                        launchKind = JavaLaunchKind.JAR,
                        minecraftArguments = parseMinecraftArguments(argv, index + 2)
                    )
                }
                token == "-m" || token == "--module" -> {
                    val moduleTarget = argv.getOrNull(index + 1)
                        ?.takeIf { it.isNotBlank() }
                        ?: return malformed()
                    return moduleLaunch(moduleTarget, argv, index + 2)
                }
                token.startsWith("--module=") -> {
                    return moduleLaunch(token.substringAfter('='), argv, index + 1)
                }
                token in optionsWithNextValue -> {
                    if (argv.getOrNull(index + 1).isNullOrBlank()) return malformed()
                    index += 2
                }
                optionsWithNextValue.any { option -> token.startsWith("$option=") } -> {
                    if (token.substringAfter('=', "").isBlank()) return malformed()
                    index++
                }
                token.startsWith("-D") ||
                    token.startsWith("-X") ||
                    token.startsWith("-XX:") ||
                    token.startsWith("-agentlib:") ||
                    token.startsWith("-agentpath:") ||
                    token.startsWith("-javaagent:") ||
                    token.startsWith("-splash:") ||
                    token in booleanJvmOptions -> index++
                token.startsWith("-") -> return malformed()
                else -> {
                    return if (isJavaClassName(token)) {
                        JavaLaunchMetadata(
                            mainClass = token,
                            state = JavaLaunchMetadataState.KNOWN_MAIN_CLASS,
                            launchKind = JavaLaunchKind.CLASS,
                            minecraftArguments = parseMinecraftArguments(argv, index + 1)
                        )
                    } else {
                        malformed()
                    }
                }
            }
        }
        return JavaLaunchMetadata()
    }

    private fun moduleLaunch(
        target: String,
        argv: List<String>,
        applicationArgumentsStart: Int
    ): JavaLaunchMetadata {
        val mainClass = target.substringAfter('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        val facts = parseMinecraftArguments(argv, applicationArgumentsStart)
        if (mainClass == null) {
            return JavaLaunchMetadata(
                state = JavaLaunchMetadataState.MODULE_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS,
                launchKind = JavaLaunchKind.MODULE,
                minecraftArguments = facts
            )
        }
        return if (isJavaClassName(mainClass)) {
            JavaLaunchMetadata(
                mainClass = mainClass,
                state = JavaLaunchMetadataState.KNOWN_MAIN_CLASS,
                launchKind = JavaLaunchKind.MODULE,
                minecraftArguments = facts
            )
        } else {
            malformed()
        }
    }

    private fun parseMinecraftArguments(
        argv: List<String>,
        startIndex: Int
    ): MinecraftJavaLaunchArguments {
        val values = linkedMapOf<String, String>()
        val tweaks = linkedSetOf<String>()
        val conflicting = linkedSetOf<String>()
        var malformedOption = false
        var index = startIndex
        while (index < argv.size) {
            val token = argv[index]
            val equalsIndex = token.indexOf('=')
            val name = if (equalsIndex > 0) token.substring(0, equalsIndex) else token
            if (name in minecraftValueOptionsNotRetained) {
                if (equalsIndex > 0) {
                    index++
                } else if (argv.getOrNull(index + 1).isNullOrBlank()) {
                    malformedOption = true
                    index++
                } else {
                    index += 2
                }
                continue
            }
            if (name in minecraftBooleanOptions) {
                index++
                continue
            }
            if (name !in minecraftArgumentNames) {
                val next = argv.getOrNull(index + 1)
                if (
                    equalsIndex < 0 &&
                    token.startsWith("-") &&
                    next != null &&
                    next in minecraftArgumentNames
                ) {
                    // An unknown option may consume the next token. Do not
                    // reinterpret that token as Minecraft evidence.
                    malformedOption = true
                }
                index += if (
                    equalsIndex < 0 &&
                    token.startsWith("-") &&
                    !next.isNullOrBlank() &&
                    !next.startsWith("-")
                ) {
                    2
                } else {
                    1
                }
                continue
            }
            val value = if (equalsIndex > 0) {
                token.substring(equalsIndex + 1)
            } else {
                argv.getOrNull(index + 1).also { index++ }.orEmpty()
            }
            if (value.isBlank() || value.length > MAX_STRUCTURED_ARGUMENT_LENGTH ||
                value.contains('\u0000')
            ) {
                malformedOption = true
                index++
                continue
            }
            if (name == "--tweakClass") {
                tweaks += value
            } else {
                val oldValue = values.putIfAbsent(name, value)
                if (oldValue != null && oldValue != value) conflicting += name
            }
            index++
        }

        fun value(name: String): String? =
            values[name]?.takeUnless { name in conflicting || malformedOption }

        return MinecraftJavaLaunchArguments(
            gameDirectory = value("--gameDir"),
            assetsDirectory = value("--assetsDir"),
            version = value("--version"),
            versionType = value("--versionType"),
            assetIndex = value("--assetIndex"),
            launchTarget = value("--launchTarget"),
            tweakClasses = tweaks,
            isAmbiguous = malformedOption || conflicting.isNotEmpty()
        )
    }

    private fun isJavaClassName(value: String): Boolean {
        if (value.isBlank()) return false
        val classSegments = value.split('.')
        if (classSegments.any { it.isBlank() }) return false
        return classSegments.all { classSegment ->
            classSegment.split('$').all { identifier ->
                identifier.isNotEmpty() &&
                    Character.isJavaIdentifierStart(identifier.first()) &&
                    identifier.drop(1).all(Character::isJavaIdentifierPart)
            }
        }
    }

    private fun malformed() =
        JavaLaunchMetadata(state = JavaLaunchMetadataState.MALFORMED_OR_AMBIGUOUS)

    private val minecraftArgumentNames = setOf(
        "--gameDir",
        "--assetsDir",
        "--version",
        "--versionType",
        "--assetIndex",
        "--launchTarget",
        "--tweakClass"
    )

    private val minecraftValueOptionsNotRetained = setOf(
        "--username",
        "--uuid",
        "--accessToken",
        "--userType",
        "--userProperties",
        "--clientId",
        "--xuid",
        "--quickPlaySingleplayer",
        "--quickPlayMultiplayer",
        "--quickPlayRealms",
        "--width",
        "--height",
        "--server",
        "--port",
        "--proxyHost",
        "--proxyPort",
        "--proxyUser",
        "--proxyPass"
    )

    private val minecraftBooleanOptions = setOf(
        "--demo",
        "--fullscreen",
        "--checkGlErrors"
    )

    private const val MAX_STRUCTURED_ARGUMENT_LENGTH = 4_096
}