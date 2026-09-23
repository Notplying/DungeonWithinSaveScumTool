package com.dungeonwithin.savemanager

import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the Shizuku binder is unreachable (not installed / not started). */
class ShizukuNotBoundException : IOException("Shizuku is not running")

/**
 * [ShellExecutor] that runs each script through Shizuku's privileged
 * `sh -c`, i.e. with the shell (ADB) identity — the on-device equivalent
 * of `adb shell` from the Windows app.
 *
 * Must be called off the main thread: stream draining blocks.
 */
class ShizukuShellExecutor : ShellExecutor {
    override fun run(script: String, timeoutSeconds: Long): ShellExecutor.Result {
        if (!Shizuku.pingBinder()) throw ShizukuNotBoundException()
        val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = Thread {
            try {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            } catch (_: Exception) {
                // Process destroyed mid-read; partial output is still usable.
            }
        }
        val errReader = Thread {
            try {
                process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
            } catch (_: Exception) {
                // Same as above.
            }
        }
        outReader.start()
        errReader.start()
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                return ShellExecutor.Result(timeoutSeconds.toInt(), stdout.toString(), "timed out")
            }
            val code = process.exitValue()
            outReader.join(2_000)
            errReader.join(2_000)
            return ShellExecutor.Result(code, stdout.toString(), stderr.toString())
        } finally {
            process.destroy()
        }
    }
}
