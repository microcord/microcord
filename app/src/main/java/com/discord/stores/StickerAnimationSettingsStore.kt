package com.discord.stores

import android.content.Context
import com.discord.utilities.stickers.StickerUtils
import rx.Observable
import rx.subjects.BehaviorSubject

object StickerAnimationSettingsStore {
    private const val PREFERENCES = "discord_user_settings"
    private const val ANIMATION_KEY = "CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1"
    private const val REDUCED_MOTION_KEY = "CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED"
    private var initialized = false
    private val animation = BehaviorSubject.create(StickerUtils.ALWAYS_ANIMATE)
    private val reducedMotion = BehaviorSubject.create(false)

    @Synchronized
    private fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        animation.onNext(preferences.getInt(ANIMATION_KEY, StickerUtils.ALWAYS_ANIMATE))
        reducedMotion.onNext(preferences.getBoolean(REDUCED_MOTION_KEY, false))
        initialized = true
    }

    fun observe(context: Context): Observable<Int> {
        initialize(context)
        return Observable.combineLatest(animation, reducedMotion) { setting, reduced ->
            StickerUtils.effectiveAnimationSetting(setting, reduced)
        }.distinctUntilChanged()
    }

    fun setAnimationSetting(context: Context, setting: Int) {
        require(setting in StickerUtils.ALWAYS_ANIMATE..StickerUtils.NEVER_ANIMATE)
        initialize(context)
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(ANIMATION_KEY, setting).apply()
        animation.onNext(setting)
    }

    fun setReducedMotion(context: Context, enabled: Boolean) {
        initialize(context)
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(REDUCED_MOTION_KEY, enabled).apply()
        reducedMotion.onNext(enabled)
    }
}
