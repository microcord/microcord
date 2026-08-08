package com.discord.utilities.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager

object SharedPreferencesProvider {
    private var sharedPreferences: SharedPreferences? = null
    private var prefsSessionDurable: SharedPreferences? = null

    fun init(context: Context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        prefsSessionDurable = context.getSharedPreferences("prefsSessionDurable", Context.MODE_PRIVATE)
    }

    fun get(): SharedPreferences =
        checkNotNull(sharedPreferences) { "You must call init() before accessing prefs" }

    fun getPrefsSessionDurable(): SharedPreferences =
        checkNotNull(prefsSessionDurable) { "You must call init() before accessing prefsSessionDurable" }

    @VisibleForTesting
    fun setInstanceForTesting(prefs: SharedPreferences?, sessionDurable: SharedPreferences? = prefs) {
        sharedPreferences = prefs
        prefsSessionDurable = sessionDurable
    }
}
