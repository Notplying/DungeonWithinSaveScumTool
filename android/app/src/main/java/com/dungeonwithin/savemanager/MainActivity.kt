package com.dungeonwithin.savemanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import rikka.shizuku.Shizuku

/**
 * Setup + manual controls. Guides the user through the three gates
 * (Shizuku installed → running → authorized, plus overlay permission),
 * then offers the same Back Up / Restore the floating button provides.
 *
 * All shell work runs off the main thread via [SaveRepository].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var shizukuButton: MaterialButton
    private lateinit var overlayButton: MaterialButton
    private lateinit var backupButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private var working = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { refreshStatus() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        column.addView(TextView(this).apply {
            text = "Dungeon Save Manager"
            textSize = 22f
        })
        column.addView(TextView(this).apply {
            text = "DungeonWithin · save.es3 · on-device backup"
            textSize = 13f
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, dp(8))
        }
        column.addView(statusView)

        shizukuButton = MaterialButton(this).apply { setOnClickListener { onShizukuButton() } }
        overlayButton = MaterialButton(this).apply { setOnClickListener { onOverlayButton() } }
        backupButton = MaterialButton(this).apply {
            text = "Back Up Now"
            setOnClickListener { runOp(SaveOp.BACKUP) }
        }
        restoreButton = MaterialButton(this).apply {
            text = "Restore"
            setOnClickListener { runOp(SaveOp.RESTORE) }
        }
        batteryButton = MaterialButton(this).apply {
            setOnClickListener { BatteryHelper.requestUnrestricted(this@MainActivity) }
        }
        stopButton = MaterialButton(this).apply {
            text = "Stop floating button"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                toast("Floating button stopped.")
            }
        }
        column.addView(shizukuButton)
        column.addView(overlayButton)
        column.addView(batteryButton)
        column.addView(backupButton)
        column.addView(restoreButton)
        column.addView(stopButton)
        column.addView(TextView(this).apply {
            text = "Restore closes the game, replaces its save with your backup, " +
                "then relaunches it. Backups live in Download/save.es3 (overwritten each time)."
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(column) })

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun onShizukuButton() {
        val pm = packageManager
        when {
            !ShizukuHelper.isManagerInstalled(pm) -> ShizukuHelper.openStoreListing(this)
            !ShizukuHelper.isBinderAlive() -> {
                toast("Opening Shizuku — press Start there, then come back.")
                ShizukuHelper.openManager(this)
            }
            ShizukuHelper.isPermanentlyDenied() -> {
                toast("Permission blocked — allow this app inside Shizuku.")
                ShizukuHelper.openManager(this)
            }
            else -> try {
                Shizuku.requestPermission(ShizukuHelper.PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                toast("Permission request failed: ${e.message}")
            }
        }
    }

    private fun onOverlayButton() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
                toast("Allow \"Display over other apps\", then come back.")
            } catch (e: Exception) {
                toast("Could not open overlay settings: ${e.message}")
            }
            return
        }
        OverlayService.start(this)
        toast("Floating button started — look for SAVE on screen.")
    }

    private fun runOp(op: SaveOp) {
        if (working) return
        working = true
        refreshButtons()
        statusView.text = "Working…"
        Thread {
            val outcome = SaveOperations.execute(op, this@MainActivity)
            val summary = if (outcome is SaveRepository.Outcome.Ok) {
                "${op.label} OK"
            } else {
                "${op.label} failed"
            }
            runOnUiThread {
                working = false
                statusView.text = outcome.message
                statusView.gravity = Gravity.START
                toast("$summary — see details above.")
                refreshStatus()
            }
        }.start()
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val pm = packageManager
        val installed = ShizukuHelper.isManagerInstalled(pm)
        val alive = installed && ShizukuHelper.isBinderAlive()
        val ready = alive && ShizukuHelper.isReady()

        statusView.text = when {
            !installed -> "1/3 Shizuku is not installed.\nInstall “Shizuku” by RikkaApps, then start it " +
                "via Wireless debugging pairing (Android 11+: no PC needed)."
            !alive -> "2/3 Shizuku is installed but not running.\nOpen Shizuku and Start it " +
                "(Wireless debugging), then return here. It must be restarted after every reboot."
            !ready && ShizukuHelper.isPermanentlyDenied() ->
                "3/3 Permission blocked.\nYou chose “don't ask again”: open Shizuku and allow " +
                    "this app under authorized apps."
            !ready -> "3/3 Shizuku is running.\nGrant permission so the manager can copy save files."
            Settings.canDrawOverlays(this) ->
                "Ready. Shizuku connected, overlay allowed — start the floating button."
            else -> "Ready. Shizuku connected — allow the overlay to start the floating button."
        }
        shizukuButton.text = when {
            !installed -> "Install Shizuku"
            !alive -> "Open Shizuku"
            ShizukuHelper.isPermanentlyDenied() -> "Open Shizuku settings"
            !ready -> "Grant Shizuku permission"
            else -> "Shizuku ready"
        }
        shizukuButton.isEnabled = !ready && !working
        overlayButton.text = if (Settings.canDrawOverlays(this)) {
            "Start floating button"
        } else {
            "Allow overlay permission"
        }
        val unrestricted = BatteryHelper.isUnrestricted(this)
        batteryButton.text = if (unrestricted) {
            "Battery optimization off"
        } else {
            "Allow unrestricted battery"
        }
        batteryButton.isEnabled = !unrestricted && !working
        refreshButtons()
    }

    private fun refreshButtons() {
        if (!::backupButton.isInitialized) return
        val ready = ShizukuHelper.isReady()
        backupButton.isEnabled = ready && !working
        restoreButton.isEnabled = ready && !working
        overlayButton.isEnabled = !working
        batteryButton.isEnabled = !BatteryHelper.isUnrestricted(this) && !working
    }
}
