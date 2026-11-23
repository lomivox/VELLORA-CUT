package com.vellora.cut.autogen.ui

import android.content.Context

/** Small app-wide choices made from the account menu. */
object YtiqPrefs {
    private const val FILE = "ytiq_prefs"
    private const val KEY_LANGUAGE = "language"

    /** "English", "Urdu" or "Roman Urdu": used for titles/descriptions/tags and Coach answers. */
    fun language(context: Context): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "English") ?: "English"

    fun setLanguage(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, value).apply()
    }
}
