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

/**
 * Semantic Java launch facts derived from argv. Raw argv is deliberately not
 * retained in this layer or in its diagnostics.
 */
data class JavaLaunchMetadata(
    val mainClass: String? = null,
    val state: JavaLaunchMetadataState = JavaLaunchMetadataState.UNAVAILABLE
)

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
            val executableName = process.executableBasename
                ?: process.executablePath?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
                ?: process.comm
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
                executionEnvironment = executionEnvironment,
                javaLaunch = javaLaunch
            )
        }

        private fun detectRuntimeFamily(executableName: String?): RuntimeFamily {
            val name = executableName
                ?.substringAfterLast('/')
                ?.lowercase(Locale.ROOT)
                ?.removeSuffix(".exe")
                ?: return RuntimeFamily.UNKNOWN
            return when {
                name in setOf("java", "javaw", "kotlin") -> RuntimeFamily.JAVA
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
                    // Recognize jar mode without retaining its argv-sourced path.
                    return JavaLaunchMetadata(
                        state = JavaLaunchMetadataState.JAR_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS
                    )
                }
                token == "-m" || token == "--module" -> {
                    val moduleTarget = argv.getOrNull(index + 1)
                        ?.takeIf { it.isNotBlank() }
                        ?: return malformed()
                    return moduleLaunch(moduleTarget)
                }
                token.startsWith("--module=") -> {
                    return moduleLaunch(token.substringAfter('='))
                }
                token in optionsWithNextValue -> {
                    if (argv.getOrNull(index + 1) == null) return malformed()
                    index += 2
                }
                optionsWithNextValue.any { option -> token.startsWith("$option=") } -> {
                    if (token.substringAfter('=', "").isBlank()) return malformed()
                    index++
                }
                token.startsWith("-D") ||
                    token.startsWith("-X") ||
                    token.startsWith("-XX:") ||
                    token in booleanJvmOptions -> index++
                token.startsWith("-") -> return malformed()
                else -> {
                    return if (isJavaClassName(token)) {
                        JavaLaunchMetadata(
                            mainClass = token,
                            state = JavaLaunchMetadataState.KNOWN_MAIN_CLASS
                        )
                    } else {
                        malformed()
                    }
                }
            }
        }
        return JavaLaunchMetadata()
    }

    private fun moduleLaunch(target: String): JavaLaunchMetadata {
        val mainClass = target.substringAfter('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        if (mainClass == null) {
            return JavaLaunchMetadata(
                state = JavaLaunchMetadataState.MODULE_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS
            )
        }
        return if (isJavaClassName(mainClass)) {
            JavaLaunchMetadata(
                mainClass = mainClass,
                state = JavaLaunchMetadataState.KNOWN_MAIN_CLASS
            )
        } else {
            malformed()
        }
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
}