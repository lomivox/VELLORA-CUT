package com.vellora.cut.autogen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.compose.foundation.text.selection.SelectionContainer
import com.vellora.cut.autogen.network.AppIdentity
import com.vellora.cut.autogen.network.ChannelAuth
import com.vellora.cut.autogen.network.YouTubeAccounts
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.autogen.network.YtAccount
import com.vellora.cut.ui.theme.BackgroundDark
import com.vellora.cut.ui.theme.CyanPrimary
import com.vellora.cut.ui.theme.SurfaceDark
import com.vellora.cut.ui.theme.SurfaceVariant
import com.vellora.cut.ui.theme.TextPrimary
import com.vellora.cut.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val Red = Color(0xFFFF6B6B)

/**
 * Returns a function that opens Google's account chooser. When a Gmail is picked it is added to the list,
 * becomes the selected upload account, and its YouTube channel name is read in the background.
 * [onChanged] is called after every change so the screen can redraw its list.
 */
@Composable
private fun rememberAddAccount(onError: (String?) -> Unit, onChanged: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val acc = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val email = acc.email
            if (email == null) {
                onError("Is account ka email nahi mila")
            } else {
                YouTubeAccounts.addOrUpdate(context, email, acc.displayName.orEmpty(), select = true)
                onError(null)
                onChanged()
                scope.launch {
                    YouTubeAuthManager.refreshChannelTitle(context, email)
                    onChanged()
                }
            }
        } catch (e: ApiException) {
            // 12501 = the person closed the chooser: not an error worth showing.
            if (e.statusCode != 12501) onError(describeSignInFailure(context, e.statusCode))
        }
    }
    return { YouTubeAuthManager.chooseAccount(context) { launcher.launch(it) } }
}

/** Returns a function that opens Google's login page in the browser, where the CHANNEL is chosen. */
@Composable
private fun rememberChannelConnect(onError: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        val intent = ChannelAuth.buildAuthIntent(context)
        if (intent == null) {
            onError("Pehle neeche \"Google Client ID\" likhein (Cloud Console ka Android OAuth client).")
        } else {
            onError(null)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                onError("Browser nahi khul saka")
            }
        }
    }
}

/** A readable reason for a failed Google sign-in, plus the exact package + SHA-1 this app presents to Google. */
private fun describeSignInFailure(context: android.content.Context, code: Int): String {
    val why = when (code) {
        10 -> "Google ko is app ka (package + SHA-1) jora Cloud Console mein nahi mila. Neeche wale dono ke sath Android OAuth client banayein."
        7 -> "Google ne network error bataya. Internet theek ho to sab se mumkin wajah yeh hai ke is app ka (package + SHA-1) Cloud Console mein registered nahi. Neeche wale dono check karein."
        12500 -> "Google ne sign-in qabool nahi ki. OAuth client aur consent screen (Test users) check karein."
        else -> "Sign-in nahi hui."
    }
    return "Sign-in fail hui (code: $code)\n$why\n" + AppIdentity.summary(context)
}

@Composable
private fun Avatar(label: String, active: Boolean, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (active) CyanPrimary else SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.trim().firstOrNull()?.uppercase() ?: "?",
            color = if (active) BackgroundDark else TextPrimary,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Settings: every connected Google account / channel, which one is active, add and remove. */
@Composable
fun YouTubeAccountsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val accounts = remember(version) { YouTubeAccounts.list(context) }
    val selected = remember(version) { YouTubeAccounts.selectedEmail(context) }
    val add = rememberAddAccount(onError = { error = it }, onChanged = { version++ })
    val addChannel = rememberChannelConnect(onError = { error = it })
    var clientId by remember { mutableStateOf(ChannelAuth.clientId(context).orEmpty()) }

    LaunchedEffect(Unit) {
        ChannelAuth.takeLastError(context)?.let { error = it }
        YouTubeAccounts.migrateLegacy(context)
        version++
        // Fill in channel names that are still missing (account added before names were read).
        YouTubeAccounts.list(context).filter { it.channelTitle.isBlank() }.forEach {
            YouTubeAuthManager.refreshChannelTitle(context, it.email)
            version++
        }
    }

    Text(text = "YouTube Accounts (Upload)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Jitne chahein Gmail / channel jodein. Upload ke waqt jis channel par bhejna ho wahi chunein — " +
            "video usi channel par jayegi.",
        color = TextSecondary,
        fontSize = 12.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    if (accounts.isEmpty()) {
        Text(text = "Abhi koi account connected nahi.", color = TextSecondary, fontSize = 13.sp)
    }
    accounts.forEach { acc ->
        AccountRow(
            acc = acc,
            active = acc.email.equals(selected, ignoreCase = true),
            onUse = { YouTubeAccounts.select(context, acc.email); version++ },
            onRemove = { YouTubeAccounts.remove(context, acc.email); version++ }
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Button(
        onClick = addChannel,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
    ) {
        Text(
            text = "+ Channel chunein aur jodein",
            color = BackgroundDark, fontWeight = FontWeight.Bold
        )
    }
    Text(
        text = "Google ki apni screen khulegi jahan aap channel chunte hain (Brand channel bhi). " +
            "Dusra channel jodna ho to dobara dabayein aur wahan dusra channel chunein.",
        color = TextSecondary,
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it; ChannelAuth.setClientId(context, it) },
        label = { Text("Google Client ID (is app ka Android OAuth client)") },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = add,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
    ) {
        Text(text = "Purana tareeqa: Gmail ka default channel", color = TextPrimary, fontSize = 12.sp)
    }
    error?.let {
        Spacer(modifier = Modifier.height(6.dp))
        SelectionContainer { Text(text = "⚠ $it", color = Red, fontSize = 12.sp) }
    }
    Spacer(modifier = Modifier.height(6.dp))
    SelectionContainer {
        Text(
            text = "Is app ki pehchan (Google ko yehi jora chahiye):\n" + AppIdentity.summary(context),
            color = TextSecondary,
            fontSize = 10.sp
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Note: ek Gmail apna channel (aur agar Google ne dikhaya to Brand channel) deta hai. " +
            "Alag channel ke liye usi channel wali Gmail se connect karein.",
        color = TextSecondary,
        fontSize = 11.sp
    )
}

@Composable
private fun AccountRow(acc: YtAccount, active: Boolean, onUse: () -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark,
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) CyanPrimary else SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Avatar(acc.label, active)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = acc.label, color = TextPrimary, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = acc.gmail, color = TextSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (active) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "● Upload yahan jayega", color = CyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!active) {
                    TextButton(onClick = onUse, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(text = "Use", color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(text = "Remove", color = Red, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Upload screen: pick which connected channel this upload goes to (the choice is remembered).
 * [onSelected] gets the chosen email, or null when none is connected.
 */
@Composable
fun UploadAccountSelector(modifier: Modifier = Modifier, onSelected: (String?) -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val accounts = remember(version) { YouTubeAccounts.list(context) }
    val selected = remember(version) { YouTubeAccounts.selectedEmail(context) }
    val add = rememberAddAccount(onError = { error = it }, onChanged = { version++ })

    LaunchedEffect(Unit) { YouTubeAccounts.migrateLegacy(context); version++ }
    LaunchedEffect(selected) { onSelected(selected) }

    Column(modifier = modifier) {
        Text(text = "Upload kis channel par?", color = TextSecondary, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            accounts.forEach { acc ->
                val active = acc.email.equals(selected, ignoreCase = true)
                Surface(
                    onClick = { YouTubeAccounts.select(context, acc.email); version++ },
                    shape = RoundedCornerShape(24.dp),
                    color = if (active) CyanPrimary else SurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Avatar(acc.label, active = !active, size = 26)
                        Text(
                            text = acc.label,
                            color = if (active) BackgroundDark else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = add,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, CyanPrimary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(text = if (accounts.isEmpty()) "Connect Account" else "+ Account", color = CyanPrimary, fontSize = 12.sp)
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "⚠ $it", color = Red, fontSize = 11.sp)
        }
    }
}
