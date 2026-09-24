package com.dungeonwithin.savemanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Foreground service hosting the draggable floating save HUD button.
 *
 * Tap opens an elegant gaming HUD with Back Up / Restore / Stop controls,
 * real-time progress feedback, and results. Touch-hold (~600ms without dragging)
 * also stops the service. Screen boundaries are clamped during drag, and
 * button position is persisted across restarts.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_button"
        private const val NOTIFICATION_ID = 1
        private const val RESULTS_CHANNEL_ID = "backup_results"
        private const val RESULT_NOTIFICATION_ID = 2
        private const val PREFS = "overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var fabParams: WindowManager.LayoutParams
    private lateinit var themedContext: Context

    private var fabView: View? = null
    private var fabStatusDot: View? = null
    private var panelView: View? = null

    private var panelBackupBtn: MaterialButton? = null
    private var panelRestoreBtn: MaterialButton? = null
    private var panelProgress: LinearProgressIndicator? = null
    private var panelResultText: TextView? = null
    private var panelResultIcon: ImageView? = null

    private var isWorking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        themedContext = ContextThemeWrapper(this, R.style.Theme_SaveManager_Overlay)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showFab()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        fabView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        fabView = null
        fabStatusDot = null
        panelView = null
        clearPanelReferences()
        super.onDestroy()
    }

    private fun clearPanelReferences() {
        panelBackupBtn = null
        panelRestoreBtn = null
        panelProgress = null
        panelResultText = null
        panelResultIcon = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating save button", NotificationManager.IMPORTANCE_MIN),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Save Manager Overlay Active")
            .setContentText("Tap to open controller settings")
            .setSmallIcon(R.drawable.ic_backup)
            .setContentIntent(openApp)
            .build()
    }

    private fun showFab() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fabSize = dp(56)
        val (screenWidth, screenHeight) = getScreenBounds()

        val defaultX = dp(16)
        val defaultY = dp(160)
        val savedX = prefs.getInt(KEY_X, defaultX).coerceIn(0, (screenWidth - fabSize).coerceAtLeast(0))
        val savedY = prefs.getInt(KEY_Y, defaultY).coerceIn(0, (screenHeight - fabSize).coerceAtLeast(0))

        fabParams = overlayParams().apply {
            width = fabSize
            height = fabSize
            x = savedX
            y = savedY
        }

        val inflater = LayoutInflater.from(themedContext)
        val root = inflater.inflate(R.layout.overlay_fab, null)
        fabStatusDot = root.findViewById(R.id.overlay_fab_status_dot)
        updateFabStatusDot(StatusState.OK)

        root.setOnTouchListener(DragListener { togglePanel() })
        fabView = root

        try {
            windowManager.addView(root, fabParams)
        } catch (e: Exception) {
            Log.e("OverlayService", "Could not show floating button", e)
            fabView = null
            toast("Couldn't show the floating button: ${e.message}")
            stopSelf()
        }
    }

    private fun updateFabStatusDot(state: StatusState) {
        val dot = fabStatusDot ?: return
        val colorRes = when (state) {
            StatusState.OK -> R.color.status_ok
            StatusState.WARN -> R.color.status_warn
            StatusState.ERROR -> R.color.status_error
        }
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(themedContext, colorRes))
            setStroke(dp(1), Color.WHITE)
        }
        dot.background = pill
    }

    private fun togglePanel() {
        try {
            if (panelView != null) hidePanel() else showPanel()
        } catch (e: Exception) {
            Log.e("OverlayService", "Panel failed", e)
            toast("Panel failed: ${e.message}")
        }
    }

    private fun showPanel() {
        val (screenWidth, screenHeight) = getScreenBounds()
        val panelWidth = dp(280)
        val fabSize = dp(56)

        // Calculate dynamic smart positioning so panel stays within screen bounds
        val panelX = if (fabParams.x + fabSize / 2 > screenWidth / 2) {
            // FAB is on right half: open panel to the left
            (fabParams.x - panelWidth - dp(8)).coerceAtLeast(dp(12))
        } else {
            // FAB is on left half: open panel to the right
            (fabParams.x + fabSize + dp(8)).coerceAtMost(screenWidth - panelWidth - dp(12))
        }

        // Clamp Y so panel doesn't fall off top or bottom
        val panelY = fabParams.y.coerceIn(dp(32), (screenHeight - dp(320)).coerceAtLeast(dp(32)))

        val params = overlayParams().apply {
            width = panelWidth
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = panelX
            y = panelY
            windowAnimations = android.R.style.Animation_Dialog
        }

        val inflater = LayoutInflater.from(themedContext)
        val panel = inflater.inflate(R.layout.overlay_panel, null)

        val closeBtn = panel.findViewById<ImageButton>(R.id.overlay_btn_close)
        val backupBtn = panel.findViewById<MaterialButton>(R.id.overlay_btn_backup)
        val restoreBtn = panel.findViewById<MaterialButton>(R.id.overlay_btn_restore)
        val stopBtn = panel.findViewById<MaterialButton>(R.id.overlay_btn_stop)
        val progress = panel.findViewById<LinearProgressIndicator>(R.id.overlay_progress)
        val resultText = panel.findViewById<TextView>(R.id.overlay_result_text)
        val resultIcon = panel.findViewById<ImageView>(R.id.overlay_result_icon)

        panelBackupBtn = backupBtn
        panelRestoreBtn = restoreBtn
        panelProgress = progress
        panelResultText = resultText
        panelResultIcon = resultIcon

        closeBtn.setOnClickListener { hidePanel() }
        backupBtn.setOnClickListener { runOp(SaveOp.BACKUP) }
        restoreBtn.setOnClickListener { runOp(SaveOp.RESTORE) }
        stopBtn.setOnClickListener {
            toast("Floating button stopped")
            stopSelf()
        }

        updatePanelWorkingState(isWorking)

        try {
            windowManager.addView(panel, params)
            panelView = panel
        } catch (e: Exception) {
            panelView = null
            clearPanelReferences()
            throw e
        }
    }

    private fun hidePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        clearPanelReferences()
    }

    private fun updatePanelWorkingState(working: Boolean) {
        panelProgress?.visibleOrGone(working)
        panelBackupBtn?.isEnabled = !working
        panelRestoreBtn?.isEnabled = !working
        updateFabStatusDot(if (working) StatusState.WARN else StatusState.OK)
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun runOp(op: SaveOp) {
        if (isWorking) return
        isWorking = true
        updatePanelWorkingState(true)

        panelResultText?.text = "Executing ${op.label}…"
        panelResultText?.setTextColor(ContextCompat.getColor(themedContext, R.color.overlay_text_secondary))
        panelResultIcon?.setImageResource(R.drawable.ic_info)
        panelResultIcon?.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(themedContext, R.color.brand_primary))

        toast("Working on ${op.label}…")

        Thread {
            val outcome = SaveOperations.execute(op, this@OverlayService)
            val ok = outcome is SaveRepository.Outcome.Ok
            val short = if (ok) {
                outcome.message.substringBefore("\n")
            } else {
                outcome.message
            }
            val title = if (ok) "${op.label} OK" else "${op.label} failed"

            mainHandler.post {
                isWorking = false
                updatePanelWorkingState(false)
                toast(short)

                panelResultText?.let { view ->
                    view.text = outcome.message
                    view.setTextColor(
                        ContextCompat.getColor(
                            themedContext,
                            if (ok) R.color.status_ok_text else R.color.status_error_text,
                        ),
                    )
                }

                panelResultIcon?.let { icon ->
                    icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
                    icon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            themedContext,
                            if (ok) R.color.status_ok else R.color.status_error,
                        ),
                    )
                }

                notifyResult(title, outcome.message)
            }
        }.start()
    }

    /**
     * Heads-up notification with the full result.
     */
    private fun notifyResult(title: String, message: String) {
        if (!AppSettings.areResultNotificationsEnabled(this)) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(RESULTS_CHANNEL_ID, "Backup results", NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, RESULTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message.substringBefore("\n"))
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_backup)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, fabParams.x)
            .putInt(KEY_Y, fabParams.y)
            .apply()
    }

    private data class ScreenSize(val width: Int, val height: Int)

    private fun getScreenBounds(): ScreenSize {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars(),
            )
            val bounds = windowMetrics.bounds
            val width = bounds.width() - insets.left - insets.right
            val height = bounds.height() - insets.top - insets.bottom
            ScreenSize(width.coerceAtLeast(320), height.coerceAtLeast(480))
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            ScreenSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    /**
     * Drag to move; plain tap toggles the action panel; press-and-hold
     * (~600ms, no drag) stops the service. Clamps movement within screen boundaries.
     */
    private inner class DragListener(private val onTap: () -> Unit) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var dragging = false
        private val slop: Float
            get() = 16f * resources.displayMetrics.density

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val fabSize = dp(56)
            val (screenWidth, screenHeight) = getScreenBounds()
            val maxX = (screenWidth - fabSize).coerceAtLeast(0)
            val maxY = (screenHeight - fabSize).coerceAtLeast(0)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = fabParams.x
                    startY = fabParams.y
                    downTime = event.eventTime
                    dragging = false
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                        if (panelView != null) hidePanel()
                    }
                    if (dragging) {
                        fabParams.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        fabParams.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        windowManager.updateViewLayout(view, fabParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (dragging) {
                        savePosition()
                    } else if (event.eventTime - downTime > 600) {
                        savePosition()
                        toast("Floating button stopped")
                        stopSelf()
                    } else {
                        onTap()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    return true
                }
            }
            return false
        }
    }
}
