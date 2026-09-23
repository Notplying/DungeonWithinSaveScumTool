package com.dungeonwithin.savemanager

/**
 * Backup/restore flow, ported from `do_backup` / `do_restore` in the
 * Windows app's `save_manager.py`.
 *
 * Ordering guarantees (covered by `SaveCommandsTest`, mirroring
 * `TestRestoreFlow`):
 * - Restore checks the backup exists **before** closing the game.
 * - Restore closes the game, then copies, then relaunches — in that order.
 * - A failed relaunch still reports success: the save itself was restored.
 */
class SaveRepository(private val shell: ShellExecutor) {

    sealed interface Outcome {
        val message: String
        data class Ok(override val message: String) : Outcome
        data class Err(override val message: String) : Outcome
    }

    fun doBackup(): Outcome {
        if (!fileExists(SavePaths.GAME_SAVE)) {
            return Outcome.Err("Game save not found on device:\n${SavePaths.GAME_SAVE}")
        }
        val proc = shell.run(SavePaths.buildBackupShell())
        if (proc.exitCode != 0) {
            return Outcome.Err("Backup failed: ${errorDetail(proc)}")
        }
        val verify = shell.run("ls -l ${SavePaths.BACKUP_SAVE}")
        if (verify.exitCode != 0 || !verify.stdout.contains("save.es3")) {
            return Outcome.Err("Copy ran but backup file not found in Download.")
        }
        return Outcome.Ok(
            "Backup OK:\n${SavePaths.GAME_SAVE}\n  -> ${SavePaths.BACKUP_SAVE}\n${verify.stdout.trim()}",
        )
    }

    fun doRestore(): Outcome {
        // 1. Never close the player's game when there is nothing to restore.
        val hasPrimary = fileExists(SavePaths.BACKUP_SAVE)
        val hasAlt = fileExists(SavePaths.BACKUP_SAVE_ALT)
        if (!hasPrimary && !hasAlt) {
            return Outcome.Err(
                "No backup found on device.\n" +
                    "Looked for:\n${SavePaths.BACKUP_SAVE}\n${SavePaths.BACKUP_SAVE_ALT}\n" +
                    "Run Back Up first.",
            )
        }
        // 2. Close the game so it can't overwrite the save while we replace it.
        val stop = shell.run(SavePaths.buildForceStopShell())
        if (stop.exitCode != 0) {
            return Outcome.Err("Could not close the game (${SavePaths.PACKAGE}): ${errorDetail(stop)}")
        }
        // 3. Restore the backup over the live save.
        val copy = shell.run(SavePaths.buildRestoreShellWithFallback())
        val combined = copy.stdout + copy.stderr
        if (copy.exitCode != 0 || combined.contains("NO_BACKUP_FOUND")) {
            return Outcome.Err(
                "The backup disappeared mid-restore.\n" +
                    "Looked for:\n${SavePaths.BACKUP_SAVE}\n${SavePaths.BACKUP_SAVE_ALT}\n" +
                    "Run Back Up first, then launch the game manually.",
            )
        }
        val verify = shell.run("ls -l ${SavePaths.GAME_SAVE}")
        val detail = verify.stdout.trim()
        // 4. Relaunch the game.
        val launch = shell.run(SavePaths.buildLaunchShell())
        if (launch.exitCode != 0) {
            return Outcome.Ok(
                "Save restored, but the game could not be relaunched automatically:\n" +
                    "${errorDetail(launch)}\nLaunch ${SavePaths.PACKAGE} manually.\n$detail",
            )
        }
        val relaunched = shell.run("pidof ${SavePaths.PACKAGE}").stdout.isNotBlank()
        val status = if (relaunched) {
            "Game relaunched."
        } else {
            "Relaunch sent - confirm the game is on screen."
        }
        // The fallback script prefers the primary path; name the source used.
        val source = if (hasPrimary) SavePaths.BACKUP_SAVE else SavePaths.BACKUP_SAVE_ALT
        return Outcome.Ok(
            "Restore OK:\n$source\n  -> ${SavePaths.GAME_SAVE}\n$detail\n$status",
        )
    }

    private fun fileExists(devicePath: String): Boolean {
        val proc = shell.run("test -f $devicePath && echo EXISTS")
        return proc.stdout.contains("EXISTS")
    }

    private fun errorDetail(proc: ShellExecutor.Result): String =
        proc.stderr.ifBlank { proc.stdout }.ifBlank { "unknown error" }.trim()
}
