package com.vellora.cut.autogen.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * On the app's main (first) page, the phone's Back button closes the app — but only on the second press
 * within 2 seconds, so one accidental tap does not throw the person out in the middle of something.
 * (Uploads keep running in the background after the app is closed.)
 */
@Composable
fun ExitOnDoubleBack(enabled: Boolean) {
    val context = LocalContext.current
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = enabled) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000L) {
            context.findActivity()?.finish()
        } else {
            lastBack = now
            Toast.makeText(context, "Band karne ke liye dobara Back dabayein", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
