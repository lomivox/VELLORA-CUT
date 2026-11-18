package com.vellora.cut.autogen.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Plays a YouTube video inside the app (full-screen dialog with the YouTube player embedded in a WebView),
 * so tapping a video no longer throws the person out to the browser. The phone's back button closes it.
 * Private/locked videos (and videos whose owner disabled embedding) cannot play here — the button below the
 * player then opens the video on YouTube instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppVideoPlayer(
    videoId: String,
    title: String,
    onOpenOnYouTube: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val webView = remember(videoId) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(android.graphics.Color.BLACK)
            val html = "<html><body style='margin:0;background:#000'>" +
                "<iframe width='100%' height='100%' style='border:0' " +
                "src='https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0' " +
                "allow='autoplay; encrypted-media' allowfullscreen></iframe></body></html>"
            // The YouTube base URL gives the embedded player the page origin it requires.
            loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(YtBg)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(YtCard)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", color = YtText, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    color = YtText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                onClick = onOpenOnYouTube,
                shape = RoundedCornerShape(20.dp),
                color = YtCard,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Nahi chal rahi? YouTube par kholein",
                    color = YtMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
