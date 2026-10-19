package com.aistra.hail.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShellCommandRunnerTest {
    private fun process(mode: String): Process {
        val javaName = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$javaName").absolutePath
        val fixtureClasses = File(requireNotNull(ShellOutputFixture::class.java.protectionDomain).codeSource.location.toURI())
        return ProcessBuilder(java, "-cp", fixtureClasses.path, ShellOutputFixture::class.java.name, mode).start()
    }

    private fun run(process: Process, command: String = "", timeout: Long = 10_000) =
        ShellCommandRunner.run(command, process.outputStream, process.inputStream,
            process.errorStream, process::waitFor, process::destroy, timeout)

    @Test(timeout = 15_000) fun drainsBothPipesBeforeWaitingForExit() {
        val result = run(process("both"))
        assertEquals(0, result.first)
        assertEquals(1024 * 1024, result.second!!.length)
        assertTrue(result.second!!.all { it == 'o' })
    }

    @Test(timeout = 15_000) fun returnsStderrAndNonzeroExitCode() {
        assertEquals(7 to "permission denied", run(process("error")))
    }

    @Test(timeout = 15_000) fun timeoutDestroysTheChildAndReturnsFailure() {
        val child = process("hang")
        assertEquals(1 to "Shell command timed out", run(child, timeout = 500))
        assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test(timeout = 15_000) fun writesUtf8AndClosesInputSoCommandsSeeEof() {
        assertEquals(0 to "freeze\n雹\n", run(process("input"), "freeze\n雹\n"))
    }
}
