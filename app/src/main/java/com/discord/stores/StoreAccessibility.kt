package com.discord.stores

import android.content.Context
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.accessibility.AccessibilityFeatureFlags
import com.discord.utilities.accessibility.AccessibilityMonitor
import com.discord.utilities.accessibility.AccessibilityState
import com.discord.utilities.persister.Persister
import java.util.EnumSet
import rx.Observable

class StoreAccessibility(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    private val accessibilityMonitor: AccessibilityMonitor = AccessibilityMonitor.INSTANCE,
) : StoreV2() {
    private var accessibilityFeatures = EnumSet.of(AccessibilityFeatureFlags.NONE)
    private var accessibilityFeaturesSnapshot = EnumSet.of(AccessibilityFeatureFlags.NONE)
    private var reducedMotionEnabled = false
    private var syncProfileThemeWithUserTheme = false
    private var isDetectionAllowed = false
    private var fontScaledUp = false
    private var fontScaledDown = false
    private val reducedMotionCache = Persister("CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED", false)
    private val syncProfileThemeCache =
        Persister("CACHE_KEY_ACCESSIBILITY_SYNC_PROFILE_THEME_WITH_USER_THEME", false)

    fun getAccessibilityFeatures(): EnumSet<AccessibilityFeatureFlags> = accessibilityFeaturesSnapshot
    fun isReducedMotionEnabled() = reducedMotionEnabled
    fun isSyncProfileThemeWithUserThemeEnabled() = syncProfileThemeWithUserTheme
    fun isScreenreaderEnabled() = accessibilityFeaturesSnapshot.contains(AccessibilityFeatureFlags.SCREENREADER)

    override fun init(context: Context) {
        reducedMotionEnabled = reducedMotionCache.get()
        syncProfileThemeWithUserTheme = syncProfileThemeCache.get()
        accessibilityMonitor.observeAccessibilityState().subscribe { state ->
            dispatcher.schedule { updateMonitoredAccessibilityState(state) }
        }
    }

    internal fun initForTesting() {
        reducedMotionEnabled = reducedMotionCache.get()
        syncProfileThemeWithUserTheme = syncProfileThemeCache.get()
        dispatcher.schedule { markChanged() }
    }

    fun observeAccessibilityFeatures(): Observable<EnumSet<AccessibilityFeatureFlags>> =
        observationDeck.connectRx(this, generator = ::getAccessibilityFeatures)
    fun observeScreenreaderEnabled(): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::isScreenreaderEnabled)
    fun observeReducedMotionEnabled(): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::isReducedMotionEnabled)
    fun observeSyncProfileThemeWithUserTheme(): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::isSyncProfileThemeWithUserThemeEnabled)

    fun setReducedMotionEnabled(enabled: Boolean) = dispatcher.schedule {
        reducedMotionEnabled = enabled
        markChanged()
    }

    fun setSyncProfileThemeWithUserTheme(enabled: Boolean) = dispatcher.schedule {
        syncProfileThemeWithUserTheme = enabled
        syncProfileThemeCache.set(enabled, persist = true)
        markChanged()
    }

    @StoreThread
    fun updateDetectionAllowed(allowed: Boolean) {
        StoreThreadScheduler.assertCurrentThread()
        isDetectionAllowed = allowed
        markChanged()
    }

    @StoreThread
    fun updateFontScale(scale: Int) {
        StoreThreadScheduler.assertCurrentThread()
        fontScaledUp = scale != -1 && scale > 100
        fontScaledDown = scale != -1 && scale < 100
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        reducedMotionEnabled = false
        isDetectionAllowed = false
        fontScaledUp = false
        fontScaledDown = false
        markChanged()
    }

    @StoreThread
    private fun updateMonitoredAccessibilityState(state: AccessibilityState) {
        StoreThreadScheduler.assertCurrentThread()
        accessibilityFeatures = EnumSet.copyOf(state.features)
        markChanged()
    }

    override fun snapshotData() {
        super.snapshotData()
        accessibilityFeaturesSnapshot = EnumSet.copyOf(accessibilityFeatures).apply {
            if (!isDetectionAllowed) remove(AccessibilityFeatureFlags.SCREENREADER)
            if (reducedMotionEnabled) add(AccessibilityFeatureFlags.REDUCED_MOTION_FROM_USER_SETTINGS)
            else remove(AccessibilityFeatureFlags.REDUCED_MOTION_FROM_USER_SETTINGS)
            if (fontScaledUp) add(AccessibilityFeatureFlags.CHAT_FONT_SCALE_INCREASED)
            if (fontScaledDown) add(AccessibilityFeatureFlags.CHAT_FONT_SCALE_DECREASED)
        }
        reducedMotionCache.set(reducedMotionEnabled, persist = true)
    }
}
