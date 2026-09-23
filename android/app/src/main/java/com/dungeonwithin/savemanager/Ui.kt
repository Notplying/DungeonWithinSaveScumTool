package com.dungeonwithin.savemanager

import android.content.Context
import android.util.TypedValue
import android.widget.Toast

/** Shared view helpers for [MainActivity] and [OverlayService]. */
fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Dynamic theme color, or [fallback] when the attribute can't resolve. */
fun Context.attrColor(attr: Int, fallback: Int): Int {
    val out = TypedValue()
    return if (theme.resolveAttribute(attr, out, true)) out.data else fallback
}
