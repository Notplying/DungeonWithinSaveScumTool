package com.dungeonwithin.savemanager

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.TimeUnit

/**
 * Privileged shell runner, executed by Shizuku in a remote process with the
 * shell (ADB) identity — the on-device equivalent of `adb shell`.
 *
 * Shizuku instantiates this class off-process (default or `(Context)`
 * constructor); clients never construct it directly, they talk to it via
 * [ShizukuShellExecutor] using the transact codes below.
 */
class ShellUserService : Binder {

    // v13 probes a (Context) constructor first; it is unused here.
    constructor() : super()

    @Suppress("unused")
    constructor(context: Context) : super()

    companion object {
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION // 1

        /** Shizuku calls this to retire an outdated service instance. */
        private const val TRANSACTION_DESTROY = 16777115

        private const val MAX_TIMEOUT_SECONDS = 120L
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_EXEC -> {
                val script = data.readString() ?: ""
                val timeout = data.readLong().coerceIn(1L, MAX_TIMEOUT_SECONDS)
                val (exit, out, err) = execShell(script, timeout)
                reply?.writeNoException()
                reply?.writeInt(exit)
                reply?.writeString(out)
                reply?.writeString(err)
                return true
            }
            TRANSACTION_DESTROY -> {
                reply?.writeNoException()
                Thread {
                    Thread.sleep(300)
                    System.exit(0)
                }.start()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun execShell(script: String, timeoutSeconds: Long): Triple<Int, String, String> {
        val process = try {
            ProcessBuilder("sh", "-c", script).start()
        } catch (e: Exception) {
            return Triple(1, "", e.message ?: e.toString())
        }
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
                return Triple(1, stdout.toString(), "timed out")
            }
            outReader.join(2_000)
            errReader.join(2_000)
            return Triple(process.exitValue(), stdout.toString(), stderr.toString())
        } finally {
            process.destroy()
        }
    }
}
