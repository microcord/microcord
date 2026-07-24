package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import com.discord.utilities.cache.SharedPreferencesProvider

fun interface DispatchHandler {
    fun onDispatchEnded()
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class StoreThread

open class Store {
    protected val prefs: SharedPreferences
        get() = SharedPreferencesProvider.get()

    protected val prefsSessionDurable: SharedPreferences
        get() = SharedPreferencesProvider.getPrefsSessionDurable()

    open fun init(context: Context) = Unit
}
