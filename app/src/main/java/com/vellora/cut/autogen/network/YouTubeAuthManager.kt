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
import kotlinx.coroutines.withContext

/**
 * One fixed YouTube account for the whole app — sign in once, Google Play
 * Services remembers it across restarts (same as any other app using
 * Google Sign-In). [getFreshAccessToken] always returns a currently-valid
 * token for that same account; nothing here ever asks "which account" more
 * than once.
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

    /** Blocking call (GoogleAuthUtil.getToken) — always call from a
     * background dispatcher, which this already does internally. */
    suspend fun getFreshAccessToken(context: Context, account: GoogleSignInAccount): TokenResult =
        withContext(Dispatchers.IO) {
            val androidAccount: Account = account.account
                ?: return@withContext TokenResult.Error("Account handle missing — dobara sign-in karein")
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
                    TokenResult.Error(e.message ?: "Consent screen nahi mil saka")
                }
            } catch (e: Exception) {
                TokenResult.Error(e.message ?: "Token nahi mil saka")
            }
        }
}
