package com.dungeonwithin.savemanager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Thrown when the Shizuku binder is unreachable (not installed / not started). */
class ShizukuNotBoundException : IOException("Shizuku is not running")

/**
 * [ShellExecutor] that runs each script in [ShellUserService], bound through
 * Shizuku with the shell identity. (`Shizuku.newProcess` is private since
 * 13.1.x; UserService is the supported path.)
 *
 * Must be called off the main thread: binding and transact block.
 */
class ShizukuShellExecutor(context: Context) : ShellExecutor {
    private val appContext = context.applicationContext

    override fun run(script: String, timeoutSeconds: Long): ShellExecutor.Result {
        if (!Shizuku.pingBinder()) throw ShizukuNotBoundException()
        val service = ShellServiceHolder.acquire(appContext)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString(script)
            data.writeLong(timeoutSeconds)
            service.transact(ShellUserService.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            return ShellExecutor.Result(
                reply.readInt(),
                reply.readString() ?: "",
                reply.readString() ?: "",
            )
        } catch (e: RemoteException) {
            throw IOException("Privileged shell call failed", e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/** Binds [ShellUserService] once and hands out the live Binder. */
internal object ShellServiceHolder {
    private const val BIND_TIMEOUT_SECONDS = 20L

    /** Bump when [ShellUserService] changes so Shizuku retires old instances. */
    private const val SERVICE_VERSION = 1

    private val lock = Any()
    private val bindLock = Any()
    private var binder: IBinder? = null

    fun acquire(context: Context): IBinder {
        // Binding is serialized; the transact callback only needs `lock`.
        synchronized(bindLock) {
            synchronized(lock) {
                binder?.let { return it }
            }
            // One retry: the first bind can lose to Shizuku still starting
            // its side or a slow first spawn of the :shell process.
            var lastError: IOException? = null
            repeat(2) {
                try {
                    return bindOnce(context)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("Privileged shell service unavailable")
        }
    }

    @Throws(IOException::class)
    private fun bindOnce(context: Context): IBinder {
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(lock) {
                    binder = service
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) {
                    binder = null
                }
            }
        }
        val args = UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shell")
            .version(SERVICE_VERSION)
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: IllegalStateException) {
            throw IOException("Shizuku binder unavailable", e)
        }
        // Kept bound for the app lifetime; Shizuku owns the remote process.
        // NOTE: unbind takes (args, connection, remove), not just the
        // connection — remove=true also retires a late-starting instance
        // via our destroy transact.
        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            throw IOException(
                "Timed out starting privileged shell service. Open Shizuku, " +
                    "make sure it is started and this app is allowed, then retry.",
            )
        }
        synchronized(lock) {
            return binder ?: throw IOException("Privileged shell service unavailable")
        }
    }
}
