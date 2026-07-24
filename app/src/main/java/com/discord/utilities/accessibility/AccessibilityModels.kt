package com.discord.utilities.accessibility

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.EnumSet
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

enum class AccessibilityFeatureFlags(val value: Long) {
    NONE(0),
    SCREENREADER(1),
    REDUCED_MOTION(2),
    REDUCED_TRANSPARENCY(4),
    HIGH_CONTRAST(8),
    BOLD_TEXT(16),
    GRAYSCALE(32),
    INVERT_COLORS(64),
    PREFERS_COLOR_SCHEME_LIGHT(128),
    PREFERS_COLOR_SCHEME_DARK(256),
    CHAT_FONT_SCALE_INCREASED(512),
    CHAT_FONT_SCALE_DECREASED(1024),
    ZOOM_LEVEL_INCREASED(2048),
    ZOOM_LEVEL_DECREASED(4096),
    MESSAGE_GROUP_SPACING_INCREASED(8192),
    MESSAGE_GROUP_SPACING_DECREASED(16384),
    DARK_SIDEBAR(32768),
    REDUCED_MOTION_FROM_USER_SETTINGS(65536),
    SATURATION_LEVEL_DECREASED(131072),
    FORCED_COLORS(262144),
    FORCED_COLORS_FROM_USER_SETTINGS(524288),
}

data class AccessibilityState(
    val features: EnumSet<AccessibilityFeatureFlags> = EnumSet.of(AccessibilityFeatureFlags.NONE),
)

class AccessibilityMonitor {
    private var state = AccessibilityState()
    private val subject = SerializedSubject(BehaviorSubject.create(state))

    fun bindContext(context: Context) {
        val features = EnumSet.of(AccessibilityFeatureFlags.NONE)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager?.isEnabled == true && manager.getEnabledAccessibilityServiceList(1).isNotEmpty()) {
            features += AccessibilityFeatureFlags.SCREENREADER
        }
        if (Settings.Global.getString(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE) in setOf("0", "0.0")) {
            features += AccessibilityFeatureFlags.REDUCED_MOTION
        }
        when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> features += AccessibilityFeatureFlags.PREFERS_COLOR_SCHEME_DARK
            Configuration.UI_MODE_NIGHT_NO -> features += AccessibilityFeatureFlags.PREFERS_COLOR_SCHEME_LIGHT
        }
        update(AccessibilityState(features))
    }

    fun observeAccessibilityState(): Observable<AccessibilityState> = subject.distinctUntilChanged()

    internal fun update(newState: AccessibilityState) {
        state = newState
        subject.onNext(newState)
    }

    companion object {
        @JvmField val INSTANCE = AccessibilityMonitor()
        fun initialize(context: Context) = INSTANCE.bindContext(context)
    }
}
