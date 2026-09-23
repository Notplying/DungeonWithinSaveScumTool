package com.dungeonwithin.savemanager

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Single entry point for running an op: builds the Shizuku-backed
 * repository, executes, and maps platform failures to outcomes.
 * Shared by [MainActivity] and [OverlayService].
 */
object SaveOperations {
    fun execute(op: SaveOp, context: Context): SaveRepository.Outcome {
        return try {
            if (!Shizuku.pingBinder()) {
                return SaveRepository.Outcome.Err("Shizuku is not running. Start it, then retry.")
            }
            if (!isAuthorized()) {
                // Binding anyway would just hang until the bind timeout, so
                // fail fast with the actual fix.
                return SaveRepository.Outcome.Err(
                    "Shizuku hasn't allowed this app. Open Shizuku → authorized apps " +
                        "(or approve its popup), allow Dungeon Save Manager, then retry.",
                )
            }
            op.execute(SaveRepository(ShizukuShellExecutor(context)))
        } catch (_: SecurityException) {
            SaveRepository.Outcome.Err(
                "Shizuku permission denied. Grant it in the Save Manager app, then retry.",
            )
        } catch (_: ShizukuNotBoundException) {
            SaveRepository.Outcome.Err("Shizuku is not running. Start it, then retry.")
        } catch (e: Exception) {
            SaveRepository.Outcome.Err("Error: ${e.message ?: e}")
        }
    }

    private fun isAuthorized(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
}
