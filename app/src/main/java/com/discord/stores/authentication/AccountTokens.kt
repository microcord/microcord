package com.discord.stores.authentication

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Tokens for every account signed in on this device, keyed by user id.
 *
 * Kept apart from [AuthStateCache], which holds only the active session and lives in the preference
 * file that logout wipes. This is the reference's `TokenManager.tokens` map: the account list itself
 * ([com.discord.stores.StoreMultiAccount]) deliberately stores no credentials, so a switch reads the
 * target's token from here.
 *
 * Stored in the clear, as the active token already is — and as the reference does on Android, where
 * `STORAGE_SECURE_KEYS` is empty and the map goes to plain MMKV.
 */
class AccountTokens(
    private val prefs: SharedPreferences,
    private val gson: Gson = Gson(),
) {
    @Synchronized
    fun get(userId: Long): String? = read()[userId.toString()]?.takeUnless(String::isBlank)

    @Synchronized
    fun put(userId: Long, token: String) = write(read() + (userId.toString() to token))

    @Synchronized
    fun remove(userId: Long) = write(read() - userId.toString())

    @Synchronized
    fun userIds(): Set<Long> = read().keys.mapNotNull(String::toLongOrNull).toSet()

    @Synchronized
    fun clear() = write(emptyMap())

    private fun read(): Map<String, String> = prefs.getString(KEY_TOKENS, null)
        ?.let { runCatching { gson.fromJson<Map<String, String>>(it, TYPE) }.getOrNull() }
        .orEmpty()

    private fun write(tokens: Map<String, String>) {
        prefs.edit().putString(KEY_TOKENS, gson.toJson(tokens)).apply()
    }

    companion object {
        const val KEY_TOKENS = "STORE_MULTI_ACCOUNT_TOKENS"
        private val TYPE = object : TypeToken<Map<String, String>>() {}.type
    }
}
