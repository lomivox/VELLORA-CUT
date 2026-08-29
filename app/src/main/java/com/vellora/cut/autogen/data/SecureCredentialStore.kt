package com.vellora.cut.autogen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Cloudflare Account ID + API Token using Android Keystore-backed
 * encryption (EncryptedSharedPreferences). Never store these as plain text
 * and never log or transmit them anywhere except the Cloudflare API call.
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

    var accountId: String
        get() = prefs.getString(KEY_ACCOUNT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()

    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_TOKEN, value).apply()

    var imageModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun hasCredentials(): Boolean = accountId.isNotBlank() && apiToken.isNotBlank()

    companion object {
        private const val KEY_ACCOUNT_ID = "cf_account_id"
        private const val KEY_API_TOKEN = "cf_api_token"
        private const val KEY_MODEL = "cf_image_model"
        const val DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell"
    }
}
