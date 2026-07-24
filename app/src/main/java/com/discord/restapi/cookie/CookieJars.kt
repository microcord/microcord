package com.discord.restapi.cookie

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import okhttp3.CookieJar

object CookieJars {
    fun persistent(context: Context): CookieJar = PersistentCookieJar(
        SetCookieCache(),
        SharedPrefsCookiePersistor(context.applicationContext),
    )
}
