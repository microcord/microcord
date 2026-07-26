package com.discord.tooltips

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TooltipManagerTest {

    private object FirstTooltip : TooltipManager.Tooltip("CACHE_KEY_FIRST", "FIRST")

    private object SecondTooltip : TooltipManager.Tooltip("CACHE_KEY_SECOND", "SECOND")

    private object TransientTooltip : TooltipManager.Tooltip(tooltipName = "TRANSIENT")

    private fun manager(
        preferences: SharedPreferences = InMemorySharedPreferences(),
        shownTooltipNames: MutableSet<String> = linkedSetOf(),
        maxTooltipsPerColdStart: Int = 1,
    ) = TooltipManager(
        acknowledgedTooltipsCache = AcknowledgedTooltipsCache(preferences),
        shownTooltipNames = shownTooltipNames,
        maxTooltipsPerColdStart = maxTooltipsPerColdStart,
        floatingViewManager = FloatingViewManager(),
    )

    @Test
    fun `an unseen tooltip may be shown`() {
        assertTrue(manager().canShowTooltip(FirstTooltip))
    }

    @Test
    fun `the cold start quota blocks a second distinct tooltip`() {
        val tooltipManager = manager(shownTooltipNames = linkedSetOf(FirstTooltip.tooltipName))

        assertFalse(tooltipManager.canShowTooltip(SecondTooltip))
    }

    @Test
    fun `a tooltip already shown this cold start does not consume more quota`() {
        val tooltipManager = manager(shownTooltipNames = linkedSetOf(FirstTooltip.tooltipName))

        assertTrue(tooltipManager.canShowTooltip(FirstTooltip))
    }

    @Test
    fun `ignoring the max bypasses the cold start quota`() {
        val tooltipManager = manager(shownTooltipNames = linkedSetOf(FirstTooltip.tooltipName))

        assertTrue(tooltipManager.canShowTooltip(SecondTooltip, ignoreMaxTooltips = true))
    }

    @Test
    fun `a larger quota allows more distinct tooltips`() {
        val tooltipManager = manager(
            shownTooltipNames = linkedSetOf(FirstTooltip.tooltipName),
            maxTooltipsPerColdStart = 2,
        )

        assertTrue(tooltipManager.canShowTooltip(SecondTooltip))
    }

    @Test
    fun `a tooltip without a cache key ignores both gates`() {
        val preferences = InMemorySharedPreferences()
        val tooltipManager = manager(
            preferences = preferences,
            shownTooltipNames = linkedSetOf(FirstTooltip.tooltipName, SecondTooltip.tooltipName),
        )

        assertTrue(tooltipManager.canShowTooltip(TransientTooltip))

        tooltipManager.ackTooltip(TransientTooltip)

        assertTrue(preferences.values.isEmpty())
        assertTrue(tooltipManager.canShowTooltip(TransientTooltip))
    }

    @Test
    fun `acknowledging a tooltip persists it and stops it from showing again`() {
        val preferences = InMemorySharedPreferences()
        val tooltipManager = manager(preferences = preferences)

        tooltipManager.ackTooltip(FirstTooltip)

        assertEquals(true, preferences.values[FirstTooltip.cacheKey])
        assertFalse(tooltipManager.canShowTooltip(FirstTooltip))
    }

    @Test
    fun `an acknowledged tooltip stays hidden even when the quota is ignored`() {
        val preferences = InMemorySharedPreferences()
        val tooltipManager = manager(preferences = preferences)
        tooltipManager.ackTooltip(FirstTooltip)

        assertFalse(tooltipManager.canShowTooltip(FirstTooltip, ignoreMaxTooltips = true))
    }

    @Test
    fun `an acknowledgement survives into the next cold start`() {
        val preferences = InMemorySharedPreferences()
        manager(preferences = preferences).ackTooltip(FirstTooltip)

        // A fresh manager with a fresh cold-start set still refuses the acknowledged tooltip.
        assertFalse(manager(preferences = preferences).canShowTooltip(FirstTooltip))
    }

    @Test
    fun `acknowledging an already acknowledged tooltip does not write again`() {
        val preferences = InMemorySharedPreferences()
        val tooltipManager = manager(preferences = preferences)

        tooltipManager.ackTooltip(FirstTooltip)
        val writesAfterFirstAck = preferences.appliedEdits
        tooltipManager.ackTooltip(FirstTooltip)

        assertEquals(writesAfterFirstAck, preferences.appliedEdits)
    }

    /** Minimal in-memory [SharedPreferences]; only the boolean surface the cache uses is real. */
    private class InMemorySharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var appliedEdits = 0

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return values[key] as? MutableSet<String> ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putString(key: String, value: String?) = put(key, value)

            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

            override fun putInt(key: String, value: Int) = put(key, value)

            override fun putLong(key: String, value: Long) = put(key, value)

            override fun putFloat(key: String, value: Float) = put(key, value)

            override fun putBoolean(key: String, value: Boolean) = put(key, value)

            override fun remove(key: String): SharedPreferences.Editor {
                removals += key
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach { key -> values.remove(key) }
                values.putAll(pending)
                appliedEdits++
            }
        }
    }
}
