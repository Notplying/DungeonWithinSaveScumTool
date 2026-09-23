# DungeonWithinSaveScumTool
<img width="473" height="854" alt="image" src="https://github.com/user-attachments/assets/dd597962-ff88-4839-9ac3-6c08eb9f4bab" />

AI SLOP

Made for Windows running android emulators (Only tested with MumuPlayer), can be used for any android devices as long as adb is accessible.

Needs python, there's a "Install_Dependencies.bat" to install the python with just one click (Not tested since i'm not bothering to uninstall my python)

Usage:
- Click the green "code" button on the upper middle of this page.
- Click "Download ZIP"
- Extract
- Double click "Install_Dependencies.bat" (For the first time, IF you don't have python)
- **double click "Start_SaveManager.vbs"** and voila, the UI should pop-up
- Click the backup to backup, restore to automatically close the game, restore the backed up file, and re-starts the game.

## Android on-device app (non-root, no PC)

No PC? there's an app for that, same backup/restore but it all happens on
the device. Little save logo floats over the game, tap it to back up or
restore, done.

Needs the [Shizuku app](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api),
normal apps can't touch another app's save folder so Shizuku does the heavy
lifting. Open Shizuku once, do the wireless debugging pairing (no PC needed
on Android 11+), re-do it after every reboot.

- Grab DungeonWithinSaveManager.apk from the Releases page on the right
- Tap it to install
- Open it, follow the checklist, hit "Start floating button"
- Restore closes the game, swaps the backup in, reopens it. No backup =
  nothing happens, the game stays open
- Details for nerds in android/README.md
