package com.dungeonwithin.savemanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Shizuku install / binder / permission state checks plus intents that
 * guide the user to fix each state (install, start, authorize).
 */
object ShizukuHelper {
    /** Current + legacy Shizuku manager packages. */
    val MANAGER_PACKAGES = listOf("rikka.shizuku", "moe.shizuku.privileged.api")

    const val PERMISSION_REQUEST_CODE = 1001

    fun isManagerInstalled(pm: PackageManager): Boolean =
        MANAGER_PACKAGES.any { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    fun installedManagerPackage(pm: PackageManager): String? =
        MANAGER_PACKAGES.firstOrNull { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    fun isBinderAlive(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

    fun hasPermission(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** True when the user ticked "deny and don't ask again". */
    fun isPermanentlyDenied(): Boolean {
        if (!isBinderAlive() || hasPermission()) return false
        return try {
            Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Exception) {
            false
        }
    }

    /** Ready to run shell commands: binder alive, API new enough, authorized. */
    fun isReady(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            !Shizuku.isPreV11() && hasPermission()
        } catch (_: Exception) {
            false
        }
    }

    fun openManager(context: Context) {
        val pm = context.packageManager
        val pkg = installedManagerPackage(pm)
        if (pkg != null) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(launch)
                    return
                } catch (_: ActivityNotFoundException) {
                    // Fall through to the store listing.
                }
            }
        }
        openStoreListing(context)
    }

    fun openStoreListing(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=rikka.shizuku"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
