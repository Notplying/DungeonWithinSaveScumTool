package com.dungeonwithin.savemanager

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Shared view helpers for [MainActivity] and [OverlayService]. */
fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun View.visibleOrGone(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Dynamic theme color, or [fallback] when the attribute can't resolve. */
fun Context.attrColor(attr: Int, fallback: Int): Int {
    val out = TypedValue()
    return if (theme.resolveAttribute(attr, out, true)) out.data else fallback
}

enum class StatusState {
    OK,
    WARN,
    ERROR
}

/** Updates a TextView status pill with dynamic semantic colors and text. */
fun TextView.setStatusPill(label: String, state: StatusState) {
    text = label
    val ctx = context
    val (textColor, bgColor, strokeColor) = when (state) {
        StatusState.OK -> Triple(
            ContextCompat.getColor(ctx, R.color.status_ok_text),
            ContextCompat.getColor(ctx, R.color.status_ok_container),
            ContextCompat.getColor(ctx, R.color.status_ok),
        )
        StatusState.WARN -> Triple(
            ContextCompat.getColor(ctx, R.color.status_warn_text),
            ContextCompat.getColor(ctx, R.color.status_warn_container),
            ContextCompat.getColor(ctx, R.color.status_warn),
        )
        StatusState.ERROR -> Triple(
            ContextCompat.getColor(ctx, R.color.status_error_text),
            ContextCompat.getColor(ctx, R.color.status_error_container),
            ContextCompat.getColor(ctx, R.color.status_error),
        )
    }
    setTextColor(textColor)
    val pillDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(100).toFloat()
        setColor(bgColor)
        setStroke(ctx.dp(1), strokeColor)
    }
    background = pillDrawable
}
