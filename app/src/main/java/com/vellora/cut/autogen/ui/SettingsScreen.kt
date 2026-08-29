package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.data.SecureCredentialStore
import com.vellora.cut.ui.theme.*

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SecureCredentialStore(context) }

    var accountId by remember { mutableStateOf(store.accountId) }
    var apiToken by remember { mutableStateOf(store.apiToken) }
    var showToken by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

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
            Text(text = "Cloudflare AI Settings", color = CyanPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "یہ credentials صرف اس فون پر، encrypted طور پر محفوظ ہوتی ہیں",
                color = TextSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = "Account ID", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = accountId,
                onValueChange = { accountId = it.trim(); savedMessage = null },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor = CyanPrimary
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(text = "API Token", color = TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it.trim(); savedMessage = null },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(text = if (showToken) "Hide" else "Show", color = CyanPrimary, fontSize = 11.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor = CyanPrimary
                )
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    store.accountId = accountId
                    store.apiToken = apiToken
                    savedMessage = "محفوظ ہو گیا"
                },
                enabled = accountId.isNotBlank() && apiToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(text = "Save", color = BackgroundDark)
            }

            savedMessage?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "✅ ", fontSize = 13.sp)
                    Text(text = it, color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}
