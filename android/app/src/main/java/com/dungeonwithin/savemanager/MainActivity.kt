package com.dungeonwithin.savemanager

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import rikka.shizuku.Shizuku

/**
 * Modern Setup & Manual Controls Activity.
 *
 * Guides the player through prerequisites (Shizuku, Overlay permission, Battery optimization),
 * provides one-tap Backup and Restore actions with live feedback, and manages the floating overlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headerStatusChip: TextView

    private lateinit var backupButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var resultCard: LinearLayout
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultDetail: TextView

    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuStatusChip: TextView
    private lateinit var shizukuButton: MaterialButton

    private lateinit var overlayDesc: TextView
    private lateinit var overlayStatusChip: TextView
    private lateinit var overlayPermButton: MaterialButton

    private lateinit var batteryDesc: TextView
    private lateinit var batteryStatusChip: TextView
    private lateinit var batteryButton: MaterialButton

    private lateinit var startOverlayButton: MaterialButton
    private lateinit var stopOverlayButton: MaterialButton
    private lateinit var overlayServiceStatusText: TextView
    private lateinit var notificationsSwitch: SwitchMaterial

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
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        refreshStatus()
    }

    private fun initViews() {
        headerStatusChip = findViewById(R.id.main_header_status_chip)

        backupButton = findViewById(R.id.btn_backup)
        restoreButton = findViewById(R.id.btn_restore)
        progressBar = findViewById(R.id.main_progress)
        resultCard = findViewById(R.id.card_result)
        resultIcon = findViewById(R.id.result_icon)
        resultTitle = findViewById(R.id.result_title)
        resultDetail = findViewById(R.id.result_detail)

        shizukuDesc = findViewById(R.id.shizuku_desc)
        shizukuStatusChip = findViewById(R.id.shizuku_status_chip)
        shizukuButton = findViewById(R.id.btn_shizuku)

        overlayDesc = findViewById(R.id.overlay_perm_desc)
        overlayStatusChip = findViewById(R.id.overlay_status_chip)
        overlayPermButton = findViewById(R.id.btn_overlay_perm)

        batteryDesc = findViewById(R.id.battery_desc)
        batteryStatusChip = findViewById(R.id.battery_status_chip)
        batteryButton = findViewById(R.id.btn_battery)

        startOverlayButton = findViewById(R.id.btn_start_overlay)
        stopOverlayButton = findViewById(R.id.btn_stop_overlay)
        overlayServiceStatusText = findViewById(R.id.overlay_service_status_text)
        notificationsSwitch = findViewById(R.id.switch_notifications)
    }

    private fun setupListeners() {
        shizukuButton.setOnClickListener { onShizukuButton() }
        overlayPermButton.setOnClickListener { onOverlayPermButton() }
        batteryButton.setOnClickListener { BatteryHelper.requestUnrestricted(this) }

        backupButton.setOnClickListener { runOp(SaveOp.BACKUP) }
        restoreButton.setOnClickListener { runOp(SaveOp.RESTORE) }

        startOverlayButton.setOnClickListener { onStartOverlay() }
        stopOverlayButton.setOnClickListener { onStopOverlay() }

        notificationsSwitch.isChecked = AppSettings.areResultNotificationsEnabled(this)
        notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setResultNotificationsEnabled(this, isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun onShizukuButton() {
        when {
            !ShizukuHelper.isBinderAlive() -> {
                toast("Opening Shizuku — press Start there, then return here.")
                ShizukuHelper.openManager(this)
            }
            ShizukuHelper.isPermanentlyDenied() -> {
                toast("Permission blocked — allow Dungeon Save Manager inside Shizuku.")
                ShizukuHelper.openManager(this)
            }
            else -> try {
                Shizuku.requestPermission(ShizukuHelper.PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                toast("Permission request failed: ${e.message}")
            }
        }
    }

    private fun onOverlayPermButton() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
                toast("Enable \"Display over other apps\", then return.")
            } catch (e: Exception) {
                toast("Could not open overlay settings: ${e.message}")
            }
        }
    }

    private fun onStartOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            onOverlayPermButton()
            return
        }
        OverlayService.start(this)
        toast("Floating button started — look for the icon on screen.")
        refreshStatus()
    }

    private fun onStopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        toast("Floating button stopped.")
        refreshStatus()
    }

    private fun runOp(op: SaveOp) {
        if (working) return
        working = true
        progressBar.visibleOrGone(true)
        backupButton.isEnabled = false
        restoreButton.isEnabled = false
        startOverlayButton.isEnabled = false
        stopOverlayButton.isEnabled = false

        resultCard.visibleOrGone(true)
        resultTitle.text = "Executing ${op.label}…"
        resultTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary_dark))
        resultIcon.setImageResource(R.drawable.ic_info)
        resultIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_primary))
        resultDetail.text = "Communicating with game files via Shizuku shell…"

        Thread {
            val outcome = SaveOperations.execute(op, this@MainActivity)
            val isOk = outcome is SaveRepository.Outcome.Ok
            val summary = if (isOk) "${op.label} OK" else "${op.label} failed"

            runOnUiThread {
                working = false
                progressBar.visibleOrGone(false)

                resultTitle.text = if (isOk) "${op.label} Succeeded" else "${op.label} Failed"
                resultTitle.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (isOk) R.color.status_ok_text else R.color.status_error_text,
                    ),
                )
                resultIcon.setImageResource(if (isOk) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
                resultIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (isOk) R.color.status_ok else R.color.status_error,
                    ),
                )
                resultDetail.text = outcome.message

                toast("$summary — see details below.")
                refreshStatus()
            }
        }.start()
    }

    private fun refreshStatus() {
        val alive = ShizukuHelper.isBinderAlive()
        val ready = alive && ShizukuHelper.isReady()
        val overlayAllowed = Settings.canDrawOverlays(this)
        val unrestricted = BatteryHelper.isUnrestricted(this)
        val overlayRunning = OverlayService.isRunning

        // 1. Shizuku Row
        when {
            ready -> {
                shizukuStatusChip.setStatusPill("Connected", StatusState.OK)
                shizukuDesc.text = "Authorized and ready to manage saves"
                shizukuButton.text = "Connected"
                shizukuButton.isEnabled = false
            }
            alive && ShizukuHelper.isPermanentlyDenied() -> {
                shizukuStatusChip.setStatusPill("Denied", StatusState.ERROR)
                shizukuDesc.text = "Permission blocked: allow in Shizuku app"
                shizukuButton.text = "Settings"
                shizukuButton.isEnabled = !working
            }
            alive -> {
                shizukuStatusChip.setStatusPill("Needs Auth", StatusState.WARN)
                shizukuDesc.text = "Shizuku running: tap Grant to authorize"
                shizukuButton.text = "Grant"
                shizukuButton.isEnabled = !working
            }
            else -> {
                shizukuStatusChip.setStatusPill("Not Running", StatusState.ERROR)
                shizukuDesc.text = "Open Shizuku and start wireless debugging"
                shizukuButton.text = "Open"
                shizukuButton.isEnabled = !working
            }
        }

        // 2. Overlay Permission Row
        if (overlayAllowed) {
            overlayStatusChip.setStatusPill("Allowed", StatusState.OK)
            overlayDesc.text = "Permission granted to draw over DungeonWithin"
            overlayPermButton.text = "Allowed"
            overlayPermButton.isEnabled = false
        } else {
            overlayStatusChip.setStatusPill("Required", StatusState.WARN)
            overlayDesc.text = "Required for the in-game floating save button"
            overlayPermButton.text = "Allow"
            overlayPermButton.isEnabled = !working
        }

        // 3. Battery Optimization Row
        if (unrestricted) {
            batteryStatusChip.setStatusPill("Unrestricted", StatusState.OK)
            batteryDesc.text = "Service protected from Android battery optimizations"
            batteryButton.text = "Exempt"
            batteryButton.isEnabled = false
        } else {
            batteryStatusChip.setStatusPill("Optimized", StatusState.WARN)
            batteryDesc.text = "Recommended: disable optimization to keep overlay running"
            batteryButton.text = "Allow"
            batteryButton.isEnabled = !working
        }

        // 4. Header Status Pill
        when {
            ready && overlayRunning ->
                headerStatusChip.setStatusPill("Overlay Active", StatusState.OK)
            ready ->
                headerStatusChip.setStatusPill("Ready", StatusState.OK)
            alive ->
                headerStatusChip.setStatusPill("Auth Needed", StatusState.WARN)
            else ->
                headerStatusChip.setStatusPill("Setup Needed", StatusState.ERROR)
        }

        // 5. Actions & Overlay Buttons
        backupButton.isEnabled = ready && !working
        restoreButton.isEnabled = ready && !working

        startOverlayButton.isEnabled = !working && !overlayRunning && overlayAllowed
        startOverlayButton.text = if (overlayRunning) "Floating Overlay is Active" else "Start Floating Overlay"
        stopOverlayButton.isEnabled = !working && overlayRunning

        overlayServiceStatusText.text = if (overlayRunning) {
            "Floating HUD is active on screen · tap its button over game"
        } else {
            "Launch the draggable floating button over DungeonWithin"
        }

        // 6. Settings
        val notifOn = AppSettings.areResultNotificationsEnabled(this)
        if (notificationsSwitch.isChecked != notifOn) {
            notificationsSwitch.isChecked = notifOn
        }
    }
}
