package com.vellora.cut.autogen.network

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

/**
 * Google sign-in for YouTube upload. Any number of accounts can be connected
 * (see [YouTubeAccounts]); [getFreshAccessToken] returns a currently-valid
 * token for whichever account (email) is asked for.
 */
object YouTubeAuthManager {

    /** Upload + read-your-own-videos (for the future Analytics feedback
     * loop) — kept to the minimum this app actually needs. */
    private const val SCOPE_YOUTUBE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload"
    private const val SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_YOUTUBE_UPLOAD), Scope(SCOPE_YOUTUBE_READONLY))
            .build()

    fun getClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions())

    fun signInIntent(context: Context): Intent = getClient(context).signInIntent

    /** The already-signed-in account, if any — null means "Connect YouTube
     * Account" hasn't been done yet (or the person signed out). */
    fun getCurrentAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)?.takeIf {
            GoogleSignIn.hasPermissions(it, Scope(SCOPE_YOUTUBE_UPLOAD))
        }

    fun signOut(context: Context, onDone: () -> Unit) {
        getClient(context).signOut().addOnCompleteListener { onDone() }
    }

    sealed class TokenResult {
        data class Success(val accessToken: String) : TokenResult()
        /** The person needs to see this Intent (a system consent screen)
         * once — pass it to an ActivityResultLauncher and retry afterwards. */
        data class NeedsUserAction(val intent: Intent) : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    /**
     * Opens Google's account chooser (with "Add another account") so a DIFFERENT Gmail can be picked.
     * Signing out of the cached sign-in first is what makes the chooser appear; accounts already
     * connected keep working, because only the cached "last account" is cleared, not their permission.
     */
    fun chooseAccount(context: Context, launch: (Intent) -> Unit) {
        val client = getClient(context)
        client.signOut().addOnCompleteListener { launch(client.signInIntent) }
    }

    /** Blocking call (GoogleAuthUtil.getToken) — always call from a
     * background dispatcher, which this already does internally. */
    suspend fun getFreshAccessToken(context: Context, account: GoogleSignInAccount): TokenResult {
        val email = account.email
            ?: return TokenResult.Error("Account handle missing — sign in again")
        return getFreshAccessToken(context, email)
    }

    /** A token for any connected account, identified by its email. */
    suspend fun getFreshAccessToken(context: Context, email: String): TokenResult {
        // Channel logins ("gmail#channelId") have their own saved login; old Gmail-only accounts use Google Sign-In below.
        if (ChannelAuth.isChannelKey(email)) return ChannelAuth.freshToken(context, email)
        return getGoogleSignInToken(context, email)
    }

    private suspend fun getGoogleSignInToken(context: Context, email: String): TokenResult =
        withContext(Dispatchers.IO) {
            val androidAccount = Account(email, "com.google")
            try {
                val token = GoogleAuthUtil.getToken(
                    context,
                    androidAccount,
                    "oauth2:$SCOPE_YOUTUBE_UPLOAD $SCOPE_YOUTUBE_READONLY"
                )
                TokenResult.Success(token)
            } catch (e: UserRecoverableAuthException) {
                // First-time consent (or a revoked grant) — the system needs
                // to show its own screen once; e.intent handles that.
                val consentIntent = e.intent
                if (consentIntent != null) {
                    TokenResult.NeedsUserAction(consentIntent)
                } else {
                    TokenResult.Error(e.message ?: "Could not get the consent screen")
                }
            } catch (e: Exception) {
                TokenResult.Error(e.message ?: "Could not get a token")
            }
        }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The name of the YouTube channel this token belongs to (null if it could not be read). */
    suspend fun fetchChannelTitle(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val items = JSONObject(r.body?.string() ?: return@withContext null).optJSONArray("items")
                items?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Reads the channel name for a connected account and stores it (used right after connecting). */
    suspend fun refreshChannelTitle(context: Context, email: String) {
        val t = getFreshAccessToken(context, email)
        if (t is TokenResult.Success) {
            fetchChannelTitle(t.accessToken)?.let { YouTubeAccounts.setChannelTitle(context, email, it) }
        }
    }
}
