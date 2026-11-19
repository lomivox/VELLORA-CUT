package com.vellora.cut.autogen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vellora.cut.BuildConfig
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

    /** True when the server (Cloudflare Pages backend) holds the keys, so nothing is typed in Settings. */
    val usesWorker: Boolean get() = BuildConfig.WORKER_URL.isNotBlank()

    /** All saved accounts, in the order they should be tried. */
    var accounts: List<CloudflareAccount>
        get() {
            // Server mode: one virtual account; the server itself rotates its real accounts when a quota runs out.
            if (usesWorker) return listOf(CloudflareAccount(WORKER_ACCOUNT_ID, "server"))
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
        get() = if (usesWorker) WORKER_YT_KEY else prefs.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_API_KEY, value).apply()

    /** Your channel's name — set once here, woven naturally into every
     * generated description's call-to-action line (e.g. "Subscribe to
     * <name> for more"). Blank means the CTA stays generic. */
    var channelName: String
        get() = prefs.getString(KEY_CHANNEL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHANNEL_NAME, value).apply()

    /** Gemini API keys, in the order they should be tried (first one first). Several keys can be
     * pooled so one key's quota/error never stops the work. Stored encrypted like everything else here. */
    var geminiKeys: List<String>
        get() {
            val raw = prefs.getString(KEY_GEMINI_KEYS_JSON, null) ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            value.map { it.trim() }.filter { it.isNotBlank() }.forEach { array.put(it) }
            prefs.edit().putString(KEY_GEMINI_KEYS_JSON, array.toString()).apply()
        }

    /** Free-text rules for this channel's titles/descriptions (e.g. the fixed title format). Added to every AI prompt. */
    var channelStyle: String
        get() = prefs.getString(KEY_CHANNEL_STYLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHANNEL_STYLE, value).apply()

    /** Comma-separated tags that are added to EVERY video's tag list (after the AI's own tags). */
    var fixedTags: String
        get() = prefs.getString(KEY_FIXED_TAGS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FIXED_TAGS, value).apply()

    /** Default for the auto-upload toggle shown in Shorts Metadata — off
     * by default so nothing goes to YouTube without an explicit choice. */
    var autoUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()

    /** Before upload, rename the video file after its title (like renaming it in the Gallery). On by default. */
    var renameVideoToTitle: Boolean
        get() = prefs.getBoolean(KEY_RENAME_TO_TITLE, true)
        set(value) = prefs.edit().putBoolean(KEY_RENAME_TO_TITLE, value).apply()

    fun hasCredentials(): Boolean = accounts.isNotEmpty()

    companion object {
        private const val KEY_ACCOUNTS_JSON = "cf_accounts_json"
        private const val KEY_LEGACY_ACCOUNT_ID = "cf_account_id"
        private const val KEY_LEGACY_API_TOKEN = "cf_api_token"
        private const val KEY_MODEL = "cf_image_model"
        private const val KEY_YOUTUBE_API_KEY = "youtube_data_api_key"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_GEMINI_KEYS_JSON = "gemini_keys_json"
        private const val KEY_CHANNEL_STYLE = "channel_style_rules"
        private const val KEY_FIXED_TAGS = "channel_fixed_tags"
        private const val KEY_AUTO_UPLOAD = "youtube_auto_upload_enabled"
        private const val KEY_RENAME_TO_TITLE = "rename_video_to_title"
        /** Stand-ins used in server mode (see [usesWorker]); the real keys never reach the phone. */
        const val WORKER_ACCOUNT_ID = "__server__"
        const val WORKER_YT_KEY = "__server__"
        const val DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell"
    }
}
