package com.discord.stores.authentication

import android.content.SharedPreferences
import com.discord.models.authentication.AuthState
import com.google.gson.Gson

class AuthStateCache(
    private val prefs: SharedPreferences,
    private val gson: Gson = Gson(),
) {
    fun getCachedAuthState(): AuthState? {
        prefs.getString(Keys.AUTH_STATE, null)?.let { json ->
            runCatching { gson.fromJson(json, AuthState::class.java) }.getOrNull()?.let { return it }
        }
        return prefs.getString(Keys.TOKEN, null)?.let { AuthState(it, emptySet()) }
    }

    fun cacheAuthState(authState: AuthState?) {
        prefs.edit().apply {
            if (authState == null) {
                remove(Keys.AUTH_STATE)
                remove(Keys.TOKEN)
            } else {
                putString(Keys.AUTH_STATE, gson.toJson(authState))
                putString(Keys.TOKEN, authState.token)
            }
        }.apply()
    }

    object Keys {
        const val AUTH_STATE = "STORE_AUTH_STATE"
        const val TOKEN = "STORE_AUTHED_TOKEN"
    }
}
