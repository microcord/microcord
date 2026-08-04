package com.discord.restapi.cookie

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import okhttp3.CookieJar

/**
 * The process-wide cookie jar.
 *
 * One instance, shared. Separate `PersistentCookieJar`s each load their own in-memory cache from
 * the same preference store, so a cookie written through one is not sent by another until the
 * process restarts — which loses `__Secure-recent_mfa` and re-prompts for verification.
 */
object CookieJars {

    @Volatile
    private var shared: PersistentCookieJar? = null

    @Synchronized
    fun persistent(context: Context): CookieJar = shared ?: PersistentCookieJar(
        SetCookieCache(),
        SharedPrefsCookiePersistor(context.applicationContext),
    ).also { shared = it }

    /** The store is its own preference file, so clearing the app's preferences misses it. */
    @Synchronized
    fun clear() {
        shared?.clear()
    }
}
