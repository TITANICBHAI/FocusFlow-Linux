package com.focusflow.enforcement

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class RuntimeFamily(val wireName: String) {
    JAVA("java"),
    PYTHON("python"),
    NODE("node"),
    SHELL("shell"),
    RUBY("ruby"),
    DOTNET("dotnet"),
    OTHER("other"),
    UNKNOWN("unknown")
}

/**
 * Runtime execution environment is distinct from the language/runtime family.
 * UNKNOWN means the resolver did not establish an environment.
 */
enum class ExecutionEnvironment(val wireName: String) {
    NATIVE("native"),
    FLATPAK("flatpak"),
    SNAP("snap"),
    APPIMAGE("appimage"),
    CONTAINER("container"),
    UNKNOWN("unknown")
}

enum class SelectorValueKind(val wireId: Int) {
    TEXT(1),
    PATH(2)
}

enum class RuntimeSelectorType(val wireId: Int) {
    PROCESS_NAME(1),
    EXECUTABLE_BASENAME(2),
    EXECUTABLE_PATH(3),
    DESKTOP_ID(4),
    PACKAGE_ID(5),
    ARGUMENT_EXISTS(6),
    ARGUMENT_EQUALS(7),
    MAIN_CLASS(8),
    WORKING_DIRECTORY(9),
    RUNTIME_FAMILY(10),
    EXECUTION_ENVIRONMENT(11)
}

sealed interface RuntimeSelector {
    val type: RuntimeSelectorType

    /** Matches procfs `comm`; matching is case-insensitive by default. */
    data class ProcessName(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.PROCESS_NAME

        init {
            require(value.isNotBlank()) { "Process name selector cannot be blank" }
        }
    }

    /** Matches the executable basename; matching is case-insensitive by default. */
    data class ExecutableBasename(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.EXECUTABLE_BASENAME

        init {
            require(value.isNotBlank()) { "Executable basename selector cannot be blank" }
        }
    }

    /** Filesystem paths use exact, case-sensitive comparison. */
    data class ExecutablePath(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.EXECUTABLE_PATH

        init {
            require(value.isNotBlank()) { "Executable path selector cannot be blank" }
        }
    }

    data class DesktopId(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.DESKTOP_ID

        init {
            require(value.isNotBlank()) { "Desktop ID selector cannot be blank" }
        }
    }

    data class PackageId(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.PACKAGE_ID

        init {
            require(value.isNotBlank()) { "Package ID selector cannot be blank" }
        }
    }

    /** Matches a complete argv option token, not a substring of a command line. */
    data class ArgumentExists(val name: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.ARGUMENT_EXISTS

        init {
            require(name.isNotBlank()) { "Argument selector cannot be blank" }
        }
    }

    /** Matches structurally parsed `--key value` or `--key=value` argv forms. */
    data class ArgumentEquals(
        val name: String,
        val expectedValue: String,
        val valueKind: SelectorValueKind = SelectorValueKind.TEXT
    ) : RuntimeSelector {
        override val type = RuntimeSelectorType.ARGUMENT_EQUALS

        init {
            require(name.isNotBlank()) { "Argument selector cannot be blank" }
        }
    }

    data class MainClass(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.MAIN_CLASS

        init {
            require(value.isNotBlank()) { "Main class selector cannot be blank" }
        }
    }

    data class WorkingDirectory(val value: String) : RuntimeSelector {
        override val type = RuntimeSelectorType.WORKING_DIRECTORY

        init {
            require(value.isNotBlank()) { "Working directory selector cannot be blank" }
        }
    }

    data class RuntimeFamilyIs(val value: RuntimeFamily) : RuntimeSelector {
        override val type = RuntimeSelectorType.RUNTIME_FAMILY
    }

    data class ExecutionEnvironmentIs(val value: ExecutionEnvironment) : RuntimeSelector {
        override val type = RuntimeSelectorType.EXECUTION_ENVIRONMENT
    }
}

sealed interface SelectorExpression {
    data class All(val children: List<SelectorExpression>) : SelectorExpression
    data class Any(val children: List<SelectorExpression>) : SelectorExpression
    data class Predicate(val selector: RuntimeSelector) : SelectorExpression
}

/**
 * A runtime definition is validated before it is persisted or used for
 * authorization. Empty ALL/ANY semantics remain explicit for evaluation.
 */
data class RuntimeSelectorDefinition(
    val expression: SelectorExpression,
    val enabled: Boolean = true,
    val schemaVersion: Int = SelectorExpressionCodec.CURRENT_SCHEMA_VERSION
) {
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != SelectorExpressionCodec.CURRENT_SCHEMA_VERSION) {
            errors += "Unsupported selector schema version: $schemaVersion"
        }
        if (enabled) {
            collectEmptyExpressionErrors(expression, "root", errors)
        }
        return errors
    }

    private fun collectEmptyExpressionErrors(
        current: SelectorExpression,
        path: String,
        errors: MutableList<String>
    ) {
        when (current) {
            is SelectorExpression.All -> {
                if (current.children.isEmpty()) errors += "$path: enabled ALL cannot be empty"
                current.children.forEachIndexed { index, child ->
                    collectEmptyExpressionErrors(child, "$path.all[$index]", errors)
                }
            }
            is SelectorExpression.Any -> {
                if (current.children.isEmpty()) errors += "$path: enabled ANY cannot be empty"
                current.children.forEachIndexed { index, child ->
                    collectEmptyExpressionErrors(child, "$path.any[$index]", errors)
                }
            }
            is SelectorExpression.Predicate -> Unit
        }
    }
}

/**
 * Strict, versioned wire format for selector trees. Length-prefixed UTF-8 and
 * stable tags avoid ambiguous delimiter/default behavior without a dependency.
 */
object SelectorExpressionCodec {
    const val CURRENT_SCHEMA_VERSION = 1

    private const val MAGIC = 0x46465345 // "FFSE"
    private const val PREFIX = "focusflow-selector-v"
    private const val MAX_SERIALIZED_CHARS = 262_144
    private const val MAX_STRING_BYTES = 16_384
    private const val MAX_CHILDREN = 512
    private const val MAX_NODES = 4_096
    private const val MAX_DEPTH = 64

    fun encode(expression: SelectorExpression): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(CURRENT_SCHEMA_VERSION)
            writeExpression(output, expression, depth = 0, nodeCount = intArrayOf(0))
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        return "$PREFIX$CURRENT_SCHEMA_VERSION:$encoded"
    }

    fun decode(serialized: String): SelectorExpression {
        require(serialized.length <= MAX_SERIALIZED_CHARS) {
            "Selector expression exceeds the size limit"
        }
        val colon = serialized.indexOf(':')
        require(colon > 0 && serialized.startsWith(PREFIX)) {
            "Unsupported selector expression format"
        }
        val prefixVersion = serialized.substring(PREFIX.length, colon).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid selector schema version")
        require(prefixVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported selector schema version: $prefixVersion"
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(serialized.substring(colon + 1))
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed selector expression encoding", exception)
        }
        require(bytes.size <= MAX_SERIALIZED_CHARS) {
            "Selector expression exceeds the size limit"
        }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid selector expression header" }
            val version = input.readInt()
            require(version == CURRENT_SCHEMA_VERSION && version == prefixVersion) {
                "Unsupported selector schema version: $version"
            }
            val expression = readExpression(input, depth = 0, nodeCount = intArrayOf(0))
            require(input.available() == 0) { "Trailing selector expression data" }
            return expression
        }
    }

    private fun writeExpression(
        output: DataOutputStream,
        expression: SelectorExpression,
        depth: Int,
        nodeCount: IntArray
    ) {
        validateTreeLimits(depth, ++nodeCount[0])
        when (expression) {
            is SelectorExpression.All -> {
                output.writeByte(1)
                writeChildren(output, expression.children, depth, nodeCount)
            }
            is SelectorExpression.Any -> {
                output.writeByte(2)
                writeChildren(output, expression.children, depth, nodeCount)
            }
            is SelectorExpression.Predicate -> {
                output.writeByte(3)
                writeSelector(output, expression.selector)
            }
        }
    }

    private fun writeChildren(
        output: DataOutputStream,
        children: List<SelectorExpression>,
        depth: Int,
        nodeCount: IntArray
    ) {
        require(children.size <= MAX_CHILDREN) { "Too many selector children" }
        output.writeInt(children.size)
        children.forEach { child ->
            writeExpression(output, child, depth + 1, nodeCount)
        }
    }

    private fun readExpression(
        input: DataInputStream,
        depth: Int,
        nodeCount: IntArray
    ): SelectorExpression {
        validateTreeLimits(depth, ++nodeCount[0])
        return when (input.readUnsignedByte()) {
            1 -> SelectorExpression.All(readChildren(input, depth, nodeCount))
            2 -> SelectorExpression.Any(readChildren(input, depth, nodeCount))
            3 -> SelectorExpression.Predicate(readSelector(input))
            else -> throw IllegalArgumentException("Unknown selector expression tag")
        }
    }

    private fun readChildren(
        input: DataInputStream,
        depth: Int,
        nodeCount: IntArray
    ): List<SelectorExpression> {
        val count = input.readInt()
        require(count in 0..MAX_CHILDREN) { "Invalid selector child count: $count" }
        return List(count) { readExpression(input, depth + 1, nodeCount) }
    }

    private fun writeSelector(output: DataOutputStream, selector: RuntimeSelector) {
        output.writeByte(selector.type.wireId)
        when (selector) {
            is RuntimeSelector.ProcessName -> output.writeString(selector.value)
            is RuntimeSelector.ExecutableBasename -> output.writeString(selector.value)
            is RuntimeSelector.ExecutablePath -> output.writeString(selector.value)
            is RuntimeSelector.DesktopId -> output.writeString(selector.value)
            is RuntimeSelector.PackageId -> output.writeString(selector.value)
            is RuntimeSelector.ArgumentExists -> output.writeString(selector.name)
            is RuntimeSelector.ArgumentEquals -> {
                output.writeString(selector.name)
                output.writeString(selector.expectedValue)
                output.writeByte(selector.valueKind.wireId)
            }
            is RuntimeSelector.MainClass -> output.writeString(selector.value)
            is RuntimeSelector.WorkingDirectory -> output.writeString(selector.value)
            is RuntimeSelector.RuntimeFamilyIs -> output.writeString(selector.value.wireName)
            is RuntimeSelector.ExecutionEnvironmentIs ->
                output.writeString(selector.value.wireName)
        }
    }

    private fun readSelector(input: DataInputStream): RuntimeSelector =
        when (val selectorType = input.readUnsignedByte()) {
            1 -> RuntimeSelector.ProcessName(input.readString())
            2 -> RuntimeSelector.ExecutableBasename(input.readString())
            3 -> RuntimeSelector.ExecutablePath(input.readString())
            4 -> RuntimeSelector.DesktopId(input.readString())
            5 -> RuntimeSelector.PackageId(input.readString())
            6 -> RuntimeSelector.ArgumentExists(input.readString())
            7 -> {
                val name = input.readString()
                val expectedValue = input.readString()
                val valueKind = when (input.readUnsignedByte()) {
                    SelectorValueKind.TEXT.wireId -> SelectorValueKind.TEXT
                    SelectorValueKind.PATH.wireId -> SelectorValueKind.PATH
                    else -> throw IllegalArgumentException("Unknown selector value kind")
                }
                RuntimeSelector.ArgumentEquals(name, expectedValue, valueKind)
            }
            8 -> RuntimeSelector.MainClass(input.readString())
            9 -> RuntimeSelector.WorkingDirectory(input.readString())
            10 -> {
                val wireName = input.readString()
                RuntimeSelector.RuntimeFamilyIs(
                    RuntimeFamily.values().firstOrNull { it.wireName == wireName }
                        ?: throw IllegalArgumentException("Unknown runtime family")
                )
            }
            11 -> {
                val wireName = input.readString()
                RuntimeSelector.ExecutionEnvironmentIs(
                    ExecutionEnvironment.values().firstOrNull { it.wireName == wireName }
                        ?: throw IllegalArgumentException("Unknown execution environment")
                )
            }
            else -> throw IllegalArgumentException("Unknown selector type: $selectorType")
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Selector string exceeds the size limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid selector string length: $length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun validateTreeLimits(depth: Int, nodeCount: Int) {
        require(depth <= MAX_DEPTH) { "Selector expression is too deeply nested" }
        require(nodeCount <= MAX_NODES) { "Selector expression has too many nodes" }
    }
}