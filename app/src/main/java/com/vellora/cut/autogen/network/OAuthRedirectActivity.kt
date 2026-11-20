package com.vellora.cut.autogen.network

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import com.vellora.cut.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Google's login page sends the person back here (redirect "<package>:/oauth2redirect").
 * Finishes the channel login, then returns to the app.
 */
class OAuthRedirectActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply {
            text = "Connecting the channel…"
            textSize = 18f
            setPadding(48, 160, 48, 48)
        }
        setContentView(label)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        scope.launch {
            val result = ChannelAuth.completeSignIn(applicationContext, data)
            val message = when (result) {
                is ChannelAuth.ConnectResult.Ok -> "Channel connected: ${result.label}"
                is ChannelAuth.ConnectResult.Fail -> {
                    ChannelAuth.setLastError(applicationContext, result.message)
                    result.message
                }
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@OAuthRedirectActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
