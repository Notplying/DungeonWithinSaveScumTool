package com.dungeonwithin.savemanager

import android.content.Context

/**
 * Single entry point for running an op: builds the Shizuku-backed
 * repository, executes, and maps platform failures to outcomes.
 * Shared by [MainActivity] and [OverlayService].
 */
object SaveOperations {
    fun execute(op: SaveOp, context: Context): SaveRepository.Outcome {
        return try {
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
}
