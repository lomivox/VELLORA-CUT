package com.vellora.cut.autogen.ui

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
import com.vellora.cut.autogen.data.CloudflareAccount
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.update.UpdateCard
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

/** One Gemini API key row — own State so typing/pasting always redraws. */
private class GeminiKeyRowState(
    val key: String = UUID.randomUUID().toString(),
    initialValue: String = ""
) {
    var value by mutableStateOf(initialValue)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SecureCredentialStore(context) }
    val workerMode = store.usesWorker

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
    var channelName by remember { mutableStateOf(store.channelName) }
    var channelSavedMessage by remember { mutableStateOf<String?>(null) }
    val geminiRows = remember {
        mutableStateListOf<GeminiKeyRowState>().apply {
            val saved = store.geminiKeys
            if (saved.isEmpty()) add(GeminiKeyRowState()) else saved.forEach { add(GeminiKeyRowState(initialValue = it)) }
        }
    }
    var geminiSavedMessage by remember { mutableStateOf<String?>(null) }
    var channelStyle by remember { mutableStateOf(store.channelStyle) }
    var fixedTags by remember { mutableStateOf(store.fixedTags) }
    var styleSavedMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = if (com.vellora.cut.BuildConfig.IS_YTIQ) 8.dp else 20.dp)
        ) {
            // YTIQ uses the phone's own back button; the "← Back" line stays only in VELLORA CUT.
            if (!com.vellora.cut.BuildConfig.IS_YTIQ) {
                TextButton(onClick = onBack) {
                    Text(text = "← Back", color = TextSecondary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(text = if (workerMode) "AI Settings" else "Cloudflare AI Accounts", color = CyanPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (workerMode) "Keys are managed on the server." else "Stored encrypted on this phone. " +
                    "Add several accounts — the next is used when a quota ends.",
                color = TextSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                UpdateCard()
                Spacer(modifier = Modifier.height(24.dp))

                if (!workerMode) {
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
                        accountsSavedMessage = "${validAccounts.size} account(s) saved"
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

                }
                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "Gemini API Keys (used first)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used first for Shorts Metadata (better results). " +
                        "Add several keys; each is tried twice. " +
                        "Falls back to Cloudflare if none work.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                geminiRows.forEachIndexed { index, row ->
                    key(row.key) {
                        var showKey by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = row.value,
                            onValueChange = { row.value = it.trim(); geminiSavedMessage = null },
                            label = { Text("Gemini Key #${index + 1}", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { showKey = !showKey }) {
                                        Text(text = if (showKey) "Hide" else "Show", color = CyanPrimary, fontSize = 11.sp)
                                    }
                                    if (geminiRows.size > 1) {
                                        TextButton(onClick = { geminiRows.removeAt(index); geminiSavedMessage = null }) {
                                            Text(text = "✕", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { geminiRows.add(GeminiKeyRowState()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "+ Add Gemini Key (${geminiRows.size} so far)", color = CyanPrimary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val valid = geminiRows.map { it.value.trim() }.filter { it.isNotBlank() }.distinct()
                        store.geminiKeys = valid
                        geminiSavedMessage = "${valid.size} Gemini key(s) saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Save Gemini Keys", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
                geminiSavedMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✅ ", fontSize = 13.sp)
                        Text(text = it, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                if (!workerMode) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "YouTube Data API Key (optional)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used for keyword research. " +
                        "Optional — empty uses free autocomplete.",
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
                        youtubeSavedMessage = if (youtubeApiKey.isBlank()) "YouTube key saved empty (autocomplete will be used)" else "YouTube API key saved"
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

                }
                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "Channel Name", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Added to every description as \"Subscribe to <name>\"." +
                        "",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = channelName,
                    onValueChange = { channelName = it; channelSavedMessage = null },
                    label = { Text("Channel Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        store.channelName = channelName
                        channelSavedMessage = "Channel name saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Save Channel Name", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
                channelSavedMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✅ ", fontSize = 13.sp)
                        Text(text = it, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Text(text = "Channel Style (template)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Rules applied to every video, e.g. titles like \"Surah ... | Beautiful Recitation\"; " +
                        "no academy or course mentions.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = channelStyle,
                    onValueChange = { channelStyle = it; styleSavedMessage = null },
                    label = { Text("Style rules") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = fixedTags,
                    onValueChange = { fixedTags = it; styleSavedMessage = null },
                    label = { Text("Fixed tags (comma-separated)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        store.channelStyle = channelStyle
                        store.fixedTags = fixedTags
                        styleSavedMessage = "Channel Style saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text(text = "Save Channel Style", color = BackgroundDark, fontWeight = FontWeight.Bold)
                }
                styleSavedMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✅ ", fontSize = 13.sp)
                        Text(text = it, color = TextSecondary, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                YouTubeAccountsSection()

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
