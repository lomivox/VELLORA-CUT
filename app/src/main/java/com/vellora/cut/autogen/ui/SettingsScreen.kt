package com.vellora.cut.autogen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.autogen.network.YouTubeAuthManager
import com.vellora.cut.ui.theme.*
import java.util.UUID

/**
 * One row's editable text — each field is its own proper Compose `State`
 * (via `by mutableStateOf`), NOT a plain `var` on a data class. This is the
 * fix for the earlier bug: mutating a plain `var` inside a list item does
 * not reliably notify Compose to redraw that text field, so pasted/typed
 * text could silently fail to show up (and then "0 accounts" on Save,
 * since the state genuinely never changed). A delegated `MutableState`
 * notifies immediately and correctly, every time.
 */
private class AccountRowState(
    val key: String = UUID.randomUUID().toString(),
    initialAccountId: String = "",
    initialApiToken: String = ""
) {
    var accountId by mutableStateOf(initialAccountId)
    var apiToken by mutableStateOf(initialApiToken)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SecureCredentialStore(context) }

    val rows = remember {
        val saved = store.accounts
        mutableStateListOf<AccountRowState>().apply {
            if (saved.isEmpty()) {
                add(AccountRowState())
            } else {
                saved.forEach { add(AccountRowState(initialAccountId = it.accountId, initialApiToken = it.apiToken)) }
            }
        }
    }
    var accountsSavedMessage by remember { mutableStateOf<String?>(null) }
    var youtubeSavedMessage by remember { mutableStateOf<String?>(null) }
    var youtubeApiKey by remember { mutableStateOf(store.youtubeApiKey) }

    var signedInAccountEmail by remember {
        mutableStateOf(YouTubeAuthManager.getCurrentAccount(context)?.email)
    }
    var signInError by remember { mutableStateOf<String?>(null) }
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromTask(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            signedInAccountEmail = account.email
            signInError = null
        } catch (e: com.google.android.gms.common.api.ApiException) {
            signInError = "Sign-in fail hui (code: ${e.statusCode})"
        }
    }

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) {
                Text(text = "← Back", color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Cloudflare AI Accounts", color = CyanPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "یہ credentials صرف اس فون پر، encrypted طور پر محفوظ ہوتی ہیں۔ " +
                    "ایک سے زیادہ accounts add کریں — روزانہ کوٹہ ختم ہونے پر اگلا account خود استعمال ہوگا۔",
                color = TextSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                rows.forEachIndexed { index, row ->
                    key(row.key) {
                        AccountCard(
                            index = index,
                            row = row,
                            onDelete = if (rows.size > 1) {
                                { rows.removeAt(index); accountsSavedMessage = null }
                            } else null,
                            onAnyChange = { accountsSavedMessage = null }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedButton(
                    onClick = { rows.add(AccountRowState()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "+ Add Account (${rows.size} so far)", color = CyanPrimary)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val validAccounts = rows
                            .filter { it.accountId.isNotBlank() && it.apiToken.isNotBlank() }
                            .map { CloudflareAccount(it.accountId, it.apiToken) }
                        store.accounts = validAccounts
                        accountsSavedMessage = "${validAccounts.size} account(s) محفوظ ہو گئے"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Save Cloudflare Accounts", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
                accountsSavedMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✅ ", fontSize = 13.sp)
                        Text(text = it, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "YouTube Data API Key (اختیاری)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Shorts Metadata tool کے لیے — اس topic پر پہلے سے چل رہی videos کے real keywords " +
                        "دیکھ کر behtar research کرنے کے لیے۔ خالی چھوڑ دیں تو YouTube کے مفت autocomplete سے کام چلے گا۔",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = youtubeApiKey,
                    onValueChange = { youtubeApiKey = it; youtubeSavedMessage = null },
                    label = { Text("YouTube API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        store.youtubeApiKey = youtubeApiKey
                        youtubeSavedMessage = if (youtubeApiKey.isBlank()) "YouTube key khali save hui (autocomplete use hoga)" else "YouTube API key محفوظ ہو گئی"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Save YouTube Key", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
                youtubeSavedMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✅ ", fontSize = 13.sp)
                        Text(text = it, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "YouTube Account (Auto-Upload)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "جس Gmail پر آپ کا YouTube channel ہے اُسی سے sign-in کریں — auto-upload ہمیشہ اسی ایک fix اکاؤنٹ میں ہوگا۔",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (signedInAccountEmail != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "✅ Connected: $signedInAccountEmail", color = TextPrimary, fontSize = 13.sp)
                        TextButton(onClick = {
                            YouTubeAuthManager.signOut(context) { signedInAccountEmail = null }
                        }) {
                            Text(text = "Disconnect", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = { signInLauncher.launch(YouTubeAuthManager.signInIntent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(text = "Connect YouTube Account", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                }
                signInError?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "⚠ $it", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun AccountCard(
    index: Int,
    row: AccountRowState,
    onDelete: (() -> Unit)?,
    onAnyChange: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Account #${index + 1}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(text = "✕ Remove", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = row.accountId,
            onValueChange = { row.accountId = it.trim(); onAnyChange() },
            label = { Text("Account ID", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = row.apiToken,
            onValueChange = { row.apiToken = it.trim(); onAnyChange() },
            label = { Text("API Token", fontSize = 11.sp) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(text = if (showToken) "Hide" else "Show", color = CyanPrimary, fontSize = 11.sp)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = CyanPrimary,
    unfocusedBorderColor = TextSecondary,
    cursorColor = CyanPrimary
)
