package com.vellora.cut.autogen.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One connected Google account. [channelTitle] is the YouTube channel name read from YouTube itself (blank until fetched). */
data class YtAccount(val email: String, val displayName: String, val channelTitle: String) {
    /** The name to show in the UI: channel name if known, else the account name, else the email. */
    val label: String get() = channelTitle.ifBlank { displayName.ifBlank { email } }
}

/**
 * The list of Google accounts connected for YouTube upload, and which one is selected.
 * Only emails and names are kept here (no tokens): a fresh token is requested from Google for the chosen
 * account every time an upload starts, so nothing secret is stored.
 */
object YouTubeAccounts {
    private const val PREFS = "youtube_accounts"
    private const val KEY_LIST = "list"
    private const val KEY_SELECTED = "selected"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<YtAccount> {
        val raw = prefs(c).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                YtAccount(o.optString("email"), o.optString("name"), o.optString("channel"))
            }.filter { it.email.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, items: List<YtAccount>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("email", it.email).put("name", it.displayName).put("channel", it.channelTitle))
        }
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Adds the account (or refreshes its name) and makes it the selected one. */
    fun addOrUpdate(c: Context, email: String, displayName: String, select: Boolean = true) {
        val items = list(c).toMutableList()
        val i = items.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        if (i >= 0) items[i] = items[i].copy(displayName = displayName.ifBlank { items[i].displayName })
        else items.add(YtAccount(email, displayName, ""))
        save(c, items)
        if (select || selectedEmail(c) == null) select(c, email)
    }

    fun setChannelTitle(c: Context, email: String, title: String) {
        save(c, list(c).map { if (it.email.equals(email, ignoreCase = true)) it.copy(channelTitle = title) else it })
    }

    fun remove(c: Context, email: String) {
        val rest = list(c).filterNot { it.email.equals(email, ignoreCase = true) }
        save(c, rest)
        if (selectedEmail(c).equals(email, ignoreCase = true)) {
            prefs(c).edit().putString(KEY_SELECTED, rest.firstOrNull()?.email).apply()
        }
    }

    fun select(c: Context, email: String) {
        prefs(c).edit().putString(KEY_SELECTED, email).apply()
    }

    /** The account uploads go to (null when nothing is connected). Falls back to the first account. */
    fun selectedEmail(c: Context): String? {
        val items = list(c)
        val saved = prefs(c).getString(KEY_SELECTED, null)
        return items.firstOrNull { it.email.equals(saved, ignoreCase = true) }?.email ?: items.firstOrNull()?.email
    }

    fun selected(c: Context): YtAccount? {
        val e = selectedEmail(c) ?: return null
        return list(c).firstOrNull { it.email.equals(e, ignoreCase = true) }
    }

    /** Older versions kept ONE account inside Google Sign-In only. Brings it into the list (once). */
    fun migrateLegacy(c: Context) {
        if (list(c).isNotEmpty()) return
        val legacy = YouTubeAuthManager.getCurrentAccount(c) ?: return
        val email = legacy.email ?: return
        addOrUpdate(c, email, legacy.displayName.orEmpty(), select = true)
    }
}
