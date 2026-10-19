package com.aistra.hail.utils

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Drain both pipes while the process runs, including while writing its input. */
internal object ShellCommandRunner {
    fun run(
        command: String,
        stdin: OutputStream,
        stdout: InputStream,
        stderr: InputStream,
        waitFor: () -> Int,
        destroy: () -> Unit,
        timeoutMillis: Long = 30_000L
    ): Pair<Int, String?> {
        val executor = Executors.newFixedThreadPool(3) { task ->
            Thread(task, "hail-shell").apply { isDaemon = true }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1L)
        return try {
            val output = executor.submit(Callable { stdout.use { readBounded(it) } })
            val errors = executor.submit(Callable { stderr.use { readBounded(it) } })
            val process = executor.submit(Callable {
                stdin.use { it.write(command.toByteArray(Charsets.UTF_8)) }
                waitFor()
            })
            val code = process.get(remaining(), TimeUnit.NANOSECONDS)
            val text = output.get(remaining(), TimeUnit.NANOSECONDS)
            val errorText = errors.get(remaining(), TimeUnit.NANOSECONDS)
            code to text.ifBlank { errorText }
        } catch (_: TimeoutException) {
            1 to "Shell command timed out"
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            1 to e.toString()
        } finally {
            runCatching(destroy)
            executor.shutdownNow()
            // Destruction closes the process pipes and releases blocked readers/writers.
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }

    private fun readBounded(input: InputStream): String {
        val retained = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val keep = minOf(count, (MAX_OUTPUT_BYTES - retained.size()).coerceAtLeast(0))
            retained.write(buffer, 0, keep)
        }
        return retained.toString("UTF-8")
    }

    private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
}
