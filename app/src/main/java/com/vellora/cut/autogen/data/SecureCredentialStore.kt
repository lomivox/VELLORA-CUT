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

    fun hasCredentials(): Boolean = accounts.isNotEmpty()

    companion object {
        private const val KEY_ACCOUNTS_JSON = "cf_accounts_json"
        private const val KEY_LEGACY_ACCOUNT_ID = "cf_account_id"
        private const val KEY_LEGACY_API_TOKEN = "cf_api_token"
        private const val KEY_MODEL = "cf_image_model"
        const val DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell"
    }
}
