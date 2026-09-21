package com.focusflow.enforcement

import java.util.concurrent.TimeUnit

/**
 * Runs a subprocess without allowing a broken desktop tool or cancelled
 * PolicyKit prompt to stall enforcement or shutdown.
 *
 * Commands are always passed as argument lists; callers must not build shell
 * command strings here.
 */
internal object BoundedProcess {

    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
        val error: String? = null
    ) {
        val succeeded: Boolean get() = !timedOut && exitCode == 0
    }

    fun run(command: List<String>, timeoutMs: Long): Result {
        if (command.isEmpty()) return Result(-1, "", error = "empty command")

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < 64 * 1024) {
                                    if (output.isNotEmpty()) output.append('\n')
                                    output.append(line)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The process may close its pipe while being terminated.
                }
            }, "FocusFlow-ProcessOutput").also {
                it.isDaemon = true
                it.start()
            }

            val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                }
                reader.join(250)
                Result(
                    exitCode = -1,
                    output = synchronized(output) { output.toString() },
                    timedOut = true,
                    error = "timed out after ${timeoutMs}ms"
                )
            } else {
                reader.join(250)
                Result(
                    exitCode = process.exitValue(),
                    output = synchronized(output) { output.toString() }
                )
            }
        } catch (e: Exception) {
            Result(-1, "", error = e.message ?: e.javaClass.simpleName)
        }
    }
}