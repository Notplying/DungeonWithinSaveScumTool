package com.dungeonwithin.savemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the behavioral spec from the Windows app's `test_save_manager.py`
 * (`TestCommands` + `TestRestoreFlow`) to JVM unit tests.
 *
 * Seams under test:
 * - [SavePaths] builders: exact shell strings sent to the device.
 * - [SaveRepository] flow: ordering and pre-check guarantees, observed
 *   through a fake [ShellExecutor] (no device needed).
 */
class SaveCommandsTest {

    // ---------- SavePaths: command builders ----------

    @Test
    fun knownPaths_matchWindowsAppSpec() {
        assertEquals("com.GameCoaster.DungeonWithin", SavePaths.PACKAGE)
        assertEquals(
            "/sdcard/Android/data/com.GameCoaster.DungeonWithin/files/save.es3",
            SavePaths.GAME_SAVE,
        )
        assertEquals("/sdcard/Download/save.es3", SavePaths.BACKUP_SAVE)
        assertEquals("/sdcard/Downloads/save.es3", SavePaths.BACKUP_SAVE_ALT)
    }

    @Test
    fun backupShell_overwritesGameSaveIntoDownload() {
        val cmd = SavePaths.buildBackupShell()
        assertTrue("must ensure Download exists", cmd.contains("mkdir -p"))
        assertTrue("must overwrite", cmd.contains("cp -f"))
        assertTrue(cmd.contains(SavePaths.GAME_SAVE))
        assertTrue(cmd.contains(SavePaths.BACKUP_SAVE))
    }

    @Test
    fun restoreShell_overwritesLiveSave() {
        val cmd = SavePaths.buildRestoreShell()
        assertTrue(cmd.contains("cp -f"))
        assertTrue(cmd.contains(SavePaths.BACKUP_SAVE))
        assertTrue(cmd.contains(SavePaths.GAME_SAVE))
    }

    @Test
    fun restoreShellWithFallback_checksBothDirs() {
        val cmd = SavePaths.buildRestoreShellWithFallback()
        assertTrue(cmd.contains(SavePaths.BACKUP_SAVE))
        assertTrue(cmd.contains(SavePaths.BACKUP_SAVE_ALT))
        assertTrue(cmd.contains("NO_BACKUP_FOUND"))
    }

    @Test
    fun forceStop_targetsPackage() {
        val cmd = SavePaths.buildForceStopShell()
        assertTrue(cmd.startsWith("am force-stop"))
        assertTrue(cmd.contains(SavePaths.PACKAGE))
    }

    @Test
    fun launch_targetsPackage() {
        val cmd = SavePaths.buildLaunchShell()
        assertTrue(cmd.contains("am start"))
        assertTrue(cmd.contains(SavePaths.PACKAGE))
        assertTrue(cmd.contains("resolve-activity"))
    }

    @Test
    fun launch_doesNotUseMonkey() {
        // monkey injects random system events (incl. screen rotation);
        // the launch must be a deterministic am start.
        assertFalse(SavePaths.buildLaunchShell().contains("monkey"))
    }

    // ---------- SaveRepository: restore flow ----------

    @Test
    fun restore_closesCopiesThenRelaunches_inOrder() {
        val fake = FakeShell()
        val outcome = SaveRepository(fake).doRestore()

        assertTrue(outcome is SaveRepository.Outcome.Ok)
        val scripts = fake.scripts
        val idxStop = scripts.indexOfFirst { it.contains("force-stop") }
        val idxCopy = scripts.indexOfFirst { it.contains("cp -f") }
        val idxLaunch = scripts.indexOfFirst { it.contains("am start") }
        assertTrue("force-stop must run", idxStop >= 0)
        assertTrue("copy must run", idxCopy >= 0)
        assertTrue("relaunch must run", idxLaunch >= 0)
        assertTrue("close before copy", idxStop < idxCopy)
        assertTrue("copy before relaunch", idxCopy < idxLaunch)
        assertTrue(scripts[idxStop].contains(SavePaths.PACKAGE))
        assertTrue(scripts[idxLaunch].contains(SavePaths.PACKAGE))
        assertTrue("must report relaunch", outcome.message.contains("elaunch"))
    }

    @Test
    fun restore_withoutBackup_doesNotCloseGame() {
        val fake = FakeShell(primaryPresent = false, altPresent = false)
        val outcome = SaveRepository(fake).doRestore()

        assertTrue(outcome is SaveRepository.Outcome.Err)
        val all = fake.scripts.joinToString("\n")
        assertFalse("must not close game without backup", all.contains("force-stop"))
        assertFalse("must not relaunch without backup", all.contains("am start"))
        assertTrue(outcome.message.contains("No backup found"))
    }

    @Test
    fun restore_fromAltBackup_reportsAltSource() {
        val fake = FakeShell(primaryPresent = false, altPresent = true)
        val outcome = SaveRepository(fake).doRestore()

        assertTrue(outcome is SaveRepository.Outcome.Ok)
        assertTrue(
            "message must name the backup actually used",
            outcome.message.contains(SavePaths.BACKUP_SAVE_ALT),
        )
    }

    @Test
    fun restore_reportsWhenRelaunchFails_butKeepsSave() {
        val fake = FakeShell(launchExitCode = 1)
        val outcome = SaveRepository(fake).doRestore()

        // The save itself was still restored.
        assertTrue(outcome is SaveRepository.Outcome.Ok)
        assertTrue(outcome.message.contains("manually"))
    }

    @Test
    fun backup_withoutGameSave_failsFast() {
        val fake = FakeShell(gameSavePresent = false)
        val outcome = SaveRepository(fake).doBackup()

        assertTrue(outcome is SaveRepository.Outcome.Err)
        val all = fake.scripts.joinToString("\n")
        assertFalse("must not copy without a live save", all.contains("cp -f"))
        assertTrue(outcome.message.contains("Game save not found"))
    }

    @Test
    fun backup_happyPath_reportsBackup() {
        val fake = FakeShell()
        val outcome = SaveRepository(fake).doBackup()

        assertTrue(outcome is SaveRepository.Outcome.Ok)
        assertTrue(outcome.message.contains("Backup OK"))
    }

    // ---------- Fake ----------

    private class FakeShell(
        private val primaryPresent: Boolean = true,
        private val altPresent: Boolean = true,
        private val gameSavePresent: Boolean = true,
        private val launchExitCode: Int = 0,
    ) : ShellExecutor {
        val scripts = mutableListOf<String>()

        override fun run(script: String, timeoutSeconds: Long): ShellExecutor.Result {
            scripts.add(script)
            if (script.startsWith("test -f")) {
                val present = when {
                    script.contains(SavePaths.GAME_SAVE) -> gameSavePresent
                    script.contains(SavePaths.BACKUP_SAVE_ALT) -> altPresent
                    script.contains(SavePaths.BACKUP_SAVE) -> primaryPresent
                    else -> false
                }
                return ShellExecutor.Result(0, if (present) "EXISTS" else "", "")
            }
            if (script.startsWith("ls -l")) {
                return ShellExecutor.Result(
                    0,
                    "-rw-rw---- 1 u0_a16 ext_data_rw 20532 save.es3",
                    "",
                )
            }
            if (script.contains("am start")) {
                return ShellExecutor.Result(launchExitCode, "Starting: Intent", "")
            }
            if (script.startsWith("pidof")) {
                return ShellExecutor.Result(0, "12345", "")
            }
            // mkdir/cp/am and the fallback restore script all succeed when a
            // backup is present; the fallback reports NO_BACKUP_FOUND otherwise.
            if (script.contains("NO_BACKUP_FOUND") && !primaryPresent && !altPresent) {
                return ShellExecutor.Result(1, "NO_BACKUP_FOUND", "")
            }
            return ShellExecutor.Result(0, "", "")
        }
    }
}
