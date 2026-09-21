package com.vellora.cut.autogen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** One Cloudflare account's credentials, pooled together with others for quota. */
data class CloudflareAccount(
    val accountId: String,
    val apiToken: String
)

/**
 * Stores multiple Cloudflare accounts' Account ID + API Token using Android
 * Keystore-backed encryption (EncryptedSharedPreferences), so their combined
 * Workers AI daily quota can be pooled. Never store these as plain text and
 * never log or transmit them anywhere except the Cloudflare API call.
 */
class SecureCredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "vellora_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** All saved accounts, in the order they should be tried. */
    var accounts: List<CloudflareAccount>
        get() {
            val raw = prefs.getString(KEY_ACCOUNTS_JSON, null) ?: return migrateLegacySingleAccount()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    CloudflareAccount(
                        accountId = obj.optString("accountId"),
                        apiToken = obj.optString("apiToken")
                    )
                }.filter { it.accountId.isNotBlank() && it.apiToken.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { account ->
                array.put(
                    JSONObject().apply {
                        put("accountId", account.accountId)
                        put("apiToken", account.apiToken)
                    }
                )
            }
            prefs.edit().putString(KEY_ACCOUNTS_JSON, array.toString()).apply()
        }

    /** One-time upgrade path from the old single-account storage format. */
    private fun migrateLegacySingleAccount(): List<CloudflareAccount> {
        val legacyId = prefs.getString(KEY_LEGACY_ACCOUNT_ID, "") ?: ""
        val legacyToken = prefs.getString(KEY_LEGACY_API_TOKEN, "") ?: ""
        if (legacyId.isBlank() || legacyToken.isBlank()) return emptyList()
        val migrated = listOf(CloudflareAccount(legacyId, legacyToken))
        accounts = migrated // persist in the new format so this runs only once
        return migrated
    }

    var imageModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** YouTube Data API v3 key (optional) — used only for real competitor
     * keyword research (search.list + videos.list on YOUR topic's already
     * top-ranking videos). Without it, keyword research falls back to
     * YouTube's free autocomplete endpoint (still real, just less rich). */
    var youtubeApiKey: String
        get() = prefs.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_API_KEY, value).apply()

    /** Your channel's name — set once here, woven naturally into every
     * generated description's call-to-action line (e.g. "Subscribe to
     * <name> for more"). Blank means the CTA stays generic. */
    var channelName: String
        get() = prefs.getString(KEY_CHANNEL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHANNEL_NAME, value).apply()

    /** Default for the auto-upload toggle shown in Shorts Metadata — off
     * by default so nothing goes to YouTube without an explicit choice. */
    var autoUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()

    fun hasCredentials(): Boolean = accounts.isNotEmpty()

    companion object {
        private const val KEY_ACCOUNTS_JSON = "cf_accounts_json"
        private const val KEY_LEGACY_ACCOUNT_ID = "cf_account_id"
        private const val KEY_LEGACY_API_TOKEN = "cf_api_token"
        private const val KEY_MODEL = "cf_image_model"
        private const val KEY_YOUTUBE_API_KEY = "youtube_data_api_key"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_AUTO_UPLOAD = "youtube_auto_upload_enabled"
        const val DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell"
    }
}
