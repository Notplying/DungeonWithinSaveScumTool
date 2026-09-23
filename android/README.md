# Dungeon Save Manager (Android, on-device, non-root)

Floating **save-logo button** that backs up and restores the DungeonWithin save
file entirely on-device — no PC, no ADB cable, no root. Privileged shell
access goes through the [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) app, which
grants ADB-level rights on the device itself.

The UI follows the system Material You theme (wallpaper dynamic color,
light/dark), so it blends in instead of looking jarring.

Behavior is a direct port of the Windows app (`save_manager.py`, repo root):
same paths, same ordering, same pre-check guarantees.

| Step | Command (run as shell via Shizuku) |
|---|---|
| Back Up | `mkdir -p /sdcard/Download && cp -f <game-save> /sdcard/Download/save.es3` |
| Restore pre-check | backup must exist **before** the game is touched |
| Restore 1. close | `am force-stop com.GameCoaster.DungeonWithin` |
| Restore 2. copy | `cp -f` from `/sdcard/Download/save.es3` (fallback `/sdcard/Downloads/save.es3`) |
| Restore 3. relaunch | resolve the MAIN/LAUNCHER component, then `am start -n` it (no `monkey`: monkey injects random system events like rotation), then verify with `pidof` |

Game save: `/sdcard/Android/data/com.GameCoaster.DungeonWithin/files/save.es3`
(regular apps can't read another app's `Android/data` since Android 11 —
that is why Shizuku is required).

## Prerequisites

1. **Shizuku app** (RikkaApps) installed on the device.
2. **Shizuku started** via on-device Wireless debugging pairing
   (Android 11+: Shizuku app → Pairing → Wireless debugging; no PC needed).
   Shizuku must be restarted after every reboot.
3. Emulator or device on Android 8.0+ (minSdk 26).

The app itself needs no network permission and works fully offline.

## Install

1. Grab `DungeonWithinSaveManager.apk` from the Releases page (every `v*`
   tag publishes one). No fresh release? The latest **Build APK** workflow
   run has the same file under Artifacts → `DungeonWithinSaveManager-apk`.
2. Install it: tap the APK on the device, or `adb install DungeonWithinSaveManager.apk`.
   Updates install over the old copy — but if you installed a build from
   before the signing fix, uninstall once first (its signature was a
   one-off), then updates work from there on.
3. Open **Dungeon Save Manager** and follow the on-screen checklist:
   install/start/authorize Shizuku, then allow “Display over other apps”.

No Android Studio or local SDK is needed — GitHub Actions compiles the APK.

## Usage

- Tap **Start floating button**, then switch to the game. The draggable
  **save-logo button** floats over it.
- Tap the logo → **Back Up** copies the live save to `Download/save.es3`
  (overwrites). Toast confirms.
- Tap the logo → **Restore** closes the game, replaces its save with the backup,
  and relaunches the game. If no backup exists, the game is left untouched.
- Press-and-hold the logo (~0.6s, without dragging) to dismiss the button.
  Button position is remembered.
- The app's main screen offers the same Back Up / Restore buttons plus the
  full status log, and re-guides you whenever Shizuku stops (e.g. reboot).
- **Allow unrestricted battery**: exempts the app from battery optimization
  so the system is less likely to kill the floating button's service.
- **Stop floating button**: dismisses the overlay from the app (same as
  press-and-hold on the logo).
- **Result notifications** switch: backup/restore outcomes also arrive as a
  heads-up notification — turn it off here to keep only the in-panel result
  line and toasts.

## If backup/restore fails

- “Shizuku hasn't allowed this app”: open Shizuku → authorized apps (or its
  permission popup) and allow Dungeon Save Manager, then retry.
- “Timed out starting privileged shell service”: Shizuku is mid-start or the
  first spawn is slow — wait a few seconds and retry once. Still failing?
  Re-start Shizuku itself.
- Nothing at all happens on tap: the device is swallowing toasts — check the
  in-panel result line, the notification, or the app's status log instead.

## Project layout

```
android/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  gradlew(.bat) + gradle/wrapper/   # pinned Gradle 8.7, no local install needed
  app/
    src/main/AndroidManifest.xml    # overlay + FGS(specialUse) + ShizukuProvider
    src/main/java/.../SavePaths.kt  # pure shell-string builders (tested)
    src/main/java/.../ShellExecutor.kt
    src/main/java/.../SaveRepository.kt      # backup/restore flow (tested)
    src/main/java/.../SaveOp.kt              # Backup vs Restore domain type
    src/main/java/.../SaveOperations.kt      # shared run entry point + error mapping
    src/main/java/.../Ui.kt                  # toast/dp helpers
    src/main/java/.../ShizukuShellExecutor.kt# client: binds the UserService over Shizuku
    src/main/java/.../ShellUserService.kt   # runs in Shizuku's shell process (sh -c)
    src/main/java/.../ShizukuHelper.kt       # binder/permission gates + Shizuku intents
    src/main/java/.../MainActivity.kt        # setup checklist + manual buttons
    src/main/java/.../OverlayService.kt      # floating button foreground service
    src/test/java/.../SaveCommandsTest.kt    # ports TestCommands + TestRestoreFlow
```

Build locally (optional): `./gradlew assembleDebug` with JDK 17 —
`app/build/outputs/apk/debug/DungeonWithinSaveManager-debug.apk`. Unit tests:
`./gradlew :app:testDebugUnitTest`. Pushing a `v*` tag publishes a release
with the renamed APK automatically.
