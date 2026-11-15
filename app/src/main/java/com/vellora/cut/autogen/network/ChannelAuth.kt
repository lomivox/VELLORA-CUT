package com.vellora.cut.autogen.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Channel-aware YouTube login (browser OAuth with PKCE).
 *
 * Why this exists: the old Google Sign-In path always gets ONE default channel per Gmail and Google
 * never asks "which channel?". Here the login happens in Google's own page (prompt = select_account +
 * consent), which DOES show the channel / Brand-account chooser. The token we get belongs to exactly
 * the channel that was picked, so every channel becomes its own connected account.
 *
 * An account saved this way has a key of the form "gmail#channelId" (see [isChannelKey]); old accounts
 * keep their plain email key and keep using Google Sign-In, so nothing existing breaks.
 */
object ChannelAuth {

    const val KEY_SEPARATOR = '#'

    private const val PREFS = "yt_channel_auth"
    private const val K_VERIFIER = "pending_verifier"
    private const val K_STATE = "pending_state"
    private const val K_LAST_ERROR = "last_error"

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/youtube/v3"
    private const val SCOPES = "openid email profile " +
        "https://www.googleapis.com/auth/youtube.upload " +
        "https://www.googleapis.com/auth/youtube.readonly"

    sealed class ConnectResult {
        data class Ok(val label: String) : ConnectResult()
        data class Fail(val message: String) : ConnectResult()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** key -> (access token, expiry in epoch millis); lives only in memory. */
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun secure(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isChannelKey(key: String): Boolean = key.indexOf(KEY_SEPARATOR) > 0

    // ------------------------------------------------------------ settings

    /** The Android OAuth client ID of THIS app (built in per app id, see build.gradle.kts; nothing to type in). */
    @Suppress("UNUSED_PARAMETER")
    fun clientId(context: Context): String? =
        com.vellora.cut.BuildConfig.GOOGLE_CLIENT_ID.trim().takeIf { it.isNotEmpty() }

    fun redirectUri(context: Context): String = "${context.packageName}:/oauth2redirect"

    fun setLastError(context: Context, message: String?) {
        val e = secure(context).edit()
        if (message == null) e.remove(K_LAST_ERROR) else e.putString(K_LAST_ERROR, message)
        e.apply()
    }

    /** The reason the last browser login failed (shown once in Settings), then cleared. */
    fun takeLastError(context: Context): String? {
        val p = secure(context)
        val m = p.getString(K_LAST_ERROR, null)
        if (m != null) p.edit().remove(K_LAST_ERROR).apply()
        return m
    }

    // ------------------------------------------------------------ step 1: open Google's page

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The intent that opens Google's login + channel chooser in the browser. Null if no client ID is saved. */
    fun buildAuthIntent(context: Context): Intent? {
        val clientId = clientId(context) ?: return null
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        secure(context).edit().putString(K_VERIFIER, verifier).putString(K_STATE, state).apply()
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri(context))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent select_account")
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ------------------------------------------------------------ step 2: Google sent us back

    private fun errorText(body: String): String = try {
        val o = JSONObject(body)
        val d = o.optString("error_description")
        if (d.isNotBlank()) d else o.optString("error", "unknown")
    } catch (e: Exception) {
        "unknown"
    }

    suspend fun completeSignIn(context: Context, redirect: Uri): ConnectResult = withContext(Dispatchers.IO) {
        try {
            val p = secure(context)
            val err = redirect.getQueryParameter("error")
            if (err != null) {
                return@withContext ConnectResult.Fail(
                    if (err == "access_denied") "Aap ne login cancel kar diya" else "Google ne mana kar diya: $err"
                )
            }
            val state = redirect.getQueryParameter("state")
            val code = redirect.getQueryParameter("code")
            val verifier = p.getString(K_VERIFIER, null)
            val savedState = p.getString(K_STATE, null)
            val clientId = clientId(context)
            if (code == null || verifier == null || clientId == null || state == null || state != savedState) {
                return@withContext ConnectResult.Fail("Login ka jawab sahi nahi tha — dobara koshish karein")
            }
            p.edit().remove(K_VERIFIER).remove(K_STATE).apply()

            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri(context))
                .add("grant_type", "authorization_code")
                .build()
            val tokens = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext ConnectResult.Fail("Token nahi mila (${r.code}): ${errorText(body)}")
                }
                JSONObject(body)
            }
            val access = tokens.optString("access_token")
            val refresh = tokens.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) {
                return@withContext ConnectResult.Fail("Google ne lamba login token nahi diya — dobara koshish karein")
            }
            val expiresMs = System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L

            // The Gmail address lives inside the id_token (no extra network call needed).
            val email = try {
                val payload = tokens.optString("id_token").split('.')[1]
                val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                JSONObject(json).optString("email")
            } catch (e: Exception) {
                ""
            }
            if (email.isBlank()) return@withContext ConnectResult.Fail("Gmail ka pata nahi chala")

            // Which channel did the person pick? The token belongs to exactly that one.
            val channelReq = Request.Builder()
                .url("$API/channels?part=snippet&mine=true")
                .addHeader("Authorization", "Bearer $access")
                .build()
            val channelJson = http.newCall(channelReq).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext ConnectResult.Fail("YouTube error ${r.code}: ${errorText(body)}")
                }
                JSONObject(body)
            }
            val item = channelJson.optJSONArray("items")?.optJSONObject(0)
                ?: return@withContext ConnectResult.Fail("Is login par koi YouTube channel nahi mila")
            val channelId = item.optString("id")
            val title = item.optJSONObject("snippet")?.optString("title").orEmpty()
            if (channelId.isBlank()) return@withContext ConnectResult.Fail("Channel ki ID nahi mili")

            val key = "$email$KEY_SEPARATOR$channelId"
            p.edit().putString("rt_$key", refresh).apply()
            tokenCache[key] = access to expiresMs
            YouTubeAccounts.addOrUpdate(context, key, title, select = true)
            YouTubeAccounts.setChannelTitle(context, key, title)
            ConnectResult.Ok(title.ifBlank { email })
        } catch (e: Exception) {
            ConnectResult.Fail(e.message ?: "Login poora nahi ho saka")
        }
    }

    // ------------------------------------------------------------ later: a fresh token for a channel

    suspend fun freshToken(context: Context, key: String): YouTubeAuthManager.TokenResult =
        withContext(Dispatchers.IO) {
            try {
                tokenCache[key]?.let { (t, exp) ->
                    if (exp - 60_000L > System.currentTimeMillis()) return@withContext YouTubeAuthManager.TokenResult.Success(t)
                }
                val clientId = clientId(context)
                    ?: return@withContext YouTubeAuthManager.TokenResult.Error("Google Client ID nahi mila — Settings mein likhein")
                val refresh = secure(context).getString("rt_$key", null)
                    ?: return@withContext YouTubeAuthManager.TokenResult.Error("Is channel ka login nahi mila — Remove kar ke dobara jodein")
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("refresh_token", refresh)
                    .add("grant_type", "refresh_token")
                    .build()
                http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        val msg = if (body.contains("invalid_grant")) {
                            "Is channel ki ijazat khatam ho gayi — Remove kar ke dobara jodein"
                        } else {
                            "Token nahi mila (${r.code}): ${errorText(body)}"
                        }
                        return@withContext YouTubeAuthManager.TokenResult.Error(msg)
                    }
                    val j = JSONObject(body)
                    val access = j.optString("access_token")
                    if (access.isBlank()) return@withContext YouTubeAuthManager.TokenResult.Error("Token khali aaya")
                    tokenCache[key] = access to (System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L)
                    YouTubeAuthManager.TokenResult.Success(access)
                }
            } catch (e: java.io.IOException) {
                YouTubeAuthManager.TokenResult.Error("Internet nahi mila")
            } catch (e: Exception) {
                YouTubeAuthManager.TokenResult.Error(e.message ?: "Token nahi mil saka")
            }
        }

    /** Forget a channel's saved login (called when it is removed from the list). */
    fun forget(context: Context, key: String) {
        tokenCache.remove(key)
        try {
            secure(context).edit().remove("rt_$key").apply()
        } catch (e: Exception) {
            // nothing stored — fine
        }
    }
}
