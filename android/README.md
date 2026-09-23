# Dungeon Save Manager (Android, no root, no PC)

Same backup/restore as the Windows app, but it all happens on the device: a
little save-logo button floats over the game, tap it to back up or restore.
Needs the [Shizuku app](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
since normal apps can't read another app's save folder (Android 11+ thing).

Looks like the rest of your phone too, it follows the system Material You
colors, light and dark.

Needs Shizuku installed and started (open Shizuku, do the wireless debugging
pairing, no PC needed on Android 11+). Shizuku has to be re-started after
every reboot, the app will nag you about it if you forget.

Install:
- Grab DungeonWithinSaveManager.apk from the Releases page on the right side
  of this repo (every v1.0-style tag publishes one automatically)
- Tap it on the device to install, or adb install it
- Open Dungeon Save Manager and follow the checklist: Shizuku, overlay
  permission ("display over other apps"), done

Usage:
- Hit "Start floating button", go back to the game, the logo sits on top and
  you can drag it wherever
- Tap it for Back Up (copies the live save to Download/save.es3, overwrites)
  or Restore (closes the game, swaps the backup in, reopens the game)
- No backup = the game is left alone, nothing gets closed
- Hold the logo for a bit to dismiss it, or use "Stop floating button" in the app
- "Allow unrestricted battery" keeps the system from killing the button, worth doing

Same rules as the Windows app under the hood (same paths, close before
copying, relaunch after). For nerds the code is under android/, builds with
./gradlew assembleDebug on JDK 17, tests with ./gradlew :app:testDebugUnitTest.
