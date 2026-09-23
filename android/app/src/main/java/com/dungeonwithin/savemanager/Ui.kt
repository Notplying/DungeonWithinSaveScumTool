package com.dungeonwithin.savemanager

import android.content.Context
import android.widget.Toast

/** Shared view helpers for [MainActivity] and [OverlayService]. */
fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
