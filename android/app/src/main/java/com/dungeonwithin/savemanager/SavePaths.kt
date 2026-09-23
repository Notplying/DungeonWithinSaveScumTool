package com.dungeonwithin.savemanager

/**
 * Device paths and shell commands for the DungeonWithin save manager.
 *
 * Direct port of the `save_manager.py` spec from the Windows app:
 * the strings built here are executed on-device through Shizuku
 * (see [ShizukuShellExecutor]) instead of over ADB from a PC.
 */
object SavePaths {
    const val PACKAGE = "com.GameCoaster.DungeonWithin"
    const val GAME_SAVE = "/sdcard/Android/data/com.GameCoaster.DungeonWithin/files/save.es3"
    const val BACKUP_DIR = "/sdcard/Download"
    const val BACKUP_SAVE = "/sdcard/Download/save.es3"

    /** Some ROMs expose `/sdcard/Downloads` (plural). Used as fallback. */
    const val BACKUP_SAVE_ALT = "/sdcard/Downloads/save.es3"

    /**
     * Copies the live game save into Download. `cp -f` overwrites any
     * existing backup; `mkdir -p` covers ROMs without a Download dir.
     */
    fun buildBackupShell(): String = "mkdir -p $BACKUP_DIR && cp -f $GAME_SAVE $BACKUP_SAVE"

    /** Copies the Download backup over the live save (overwrites). */
    fun buildRestoreShell(): String = "cp -f $BACKUP_SAVE $GAME_SAVE"

    /** Restore trying singular Download/ first, then plural Downloads/. */
    fun buildRestoreShellWithFallback(): String =
        "if [ -f $BACKUP_SAVE ]; then cp -f $BACKUP_SAVE $GAME_SAVE; " +
            "elif [ -f $BACKUP_SAVE_ALT ]; then cp -f $BACKUP_SAVE_ALT $GAME_SAVE; " +
            "else echo NO_BACKUP_FOUND; exit 1; fi"

    /** Closes the game (no-op if not running). */
    fun buildForceStopShell(): String = "am force-stop $PACKAGE"

    /**
     * Cold-launches the game via its resolved launcher entry.
     *
     * Deliberately NOT `monkey`: monkey injects random system events
     * (including screen rotation) that leak into device settings. Resolving
     * the MAIN/LAUNCHER component and `am start`-ing it touches nothing else.
     */
    fun buildLaunchShell(): String =
        "cmp=$(cmd package resolve-activity --brief " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "$PACKAGE | tail -n 1); " +
            "if [ -n "${'$'}cmp" ]; then am start -n "${'$'}cmp"; else exit 1; fi"
}
