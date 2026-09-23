package com.dungeonwithin.savemanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Foreground service hosting the draggable floating save-logo button.
 *
 * Tap opens a small panel with Back Up / Restore / Hide. Touch-hold
 * (long-press without dragging) stops the service. Button position is
 * persisted across restarts.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_button"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var fabParams: WindowManager.LayoutParams
    private var fab: ImageButton? = null
    private var panel: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showFab()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        fab?.let { runCatching { windowManager.removeView(it) } }
        panel?.let { runCatching { windowManager.removeView(it) } }
        fab = null
        panel = null
        super.onDestroy()
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
            .setContentTitle("Save Manager")
            .setContentText("Floating save button is active")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(openApp)
            .build()
    }

    private fun showFab() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // The logo artwork is self-contained (own tile + colors): no theme
        // tint or background, so rendering can't fail on theme lookups.
        val fabSize = dp(56)
        fabParams = overlayParams().apply {
            width = fabSize
            height = fabSize
            x = prefs.getInt(KEY_X, dp(16))
            y = prefs.getInt(KEY_Y, dp(160))
        }
        fab = ImageButton(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Save manager floating button. Tap for backup and restore."
            setOnTouchListener(DragListener { togglePanel() })
        }
        try {
            windowManager.addView(fab, fabParams)
        } catch (e: Exception) {
            // e.g. overlay permission revoked mid-run: say so, don't just vanish.
            fab = null
            toast("Couldn't show the floating button: ${e.message}")
            stopSelf()
        }
    }

    private fun togglePanel() {
        try {
            if (panel != null) hidePanel() else showPanel()
        } catch (e: Exception) {
            // Never take the service down with a dead panel. Logged always;
            // toasted when the system lets toasts through.
            Log.e("OverlayService", "Panel failed", e)
            toast("Panel failed: ${e.message}")
        }
    }

    private fun showPanel() {
        val params = overlayParams().apply {
            x = fabParams.x
            y = fabParams.y + dp(64)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        // Framework buttons on purpose: Material buttons resolve theme
        // attributes that a Service context can't always provide, which
        // crashed the panel on tap.
        layout.addView(Button(this).apply {
            text = "Back Up"
            setOnClickListener { runOp(SaveOp.BACKUP) }
        })
        layout.addView(Button(this).apply {
            text = "Restore"
            setOnClickListener { runOp(SaveOp.RESTORE) }
        })
        layout.addView(Button(this).apply {
            text = "Hide"
            setOnClickListener { hidePanel() }
        })
        panel = layout
        try {
            windowManager.addView(layout, params)
        } catch (e: Exception) {
            panel = null
            throw e
        }
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
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
        toast("Working…")
        Thread {
            val outcome = SaveOperations.execute(op, this@OverlayService)
            // Success fits one line; failures carry guidance ("Looked for: …",
            // "Launch … manually") that the overlay user must see in full.
            val text = if (outcome is SaveRepository.Outcome.Ok) {
                outcome.message.substringBefore("\n")
            } else {
                outcome.message
            }
            mainHandler.post { toast(text) }
        }.start()
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, fabParams.x)
            .putInt(KEY_Y, fabParams.y)
            .apply()
    }

    /**
     * Drag to move; plain tap toggles the action panel; press-and-hold
     * (~600ms, no drag) stops the service.
     */
    private inner class DragListener(private val onTap: () -> Unit) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var dragging = false
        private val slop: Float
            get() = 24f * resources.displayMetrics.density

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = fabParams.x
                    startY = fabParams.y
                    downTime = event.eventTime
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        fabParams.x = startX + dx.toInt()
                        fabParams.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, fabParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
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
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}
