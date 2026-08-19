package com.discord.utilities.feature

import android.content.SharedPreferences
import com.discord.utilities.cache.SharedPreferencesProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmListCustomizationTest {
    @After
    fun tearDown() = SharedPreferencesProvider.setInstanceForTesting(null)

    @Test
    fun `defaults reproduce the reference row and additions stay off`() {
        SharedPreferencesProvider.setInstanceForTesting(InMemoryPreferences())

        assertTrue(DmListCustomization.visible(DmListCustomization.Component.STATUS_DOT))
        assertTrue(DmListCustomization.visible(DmListCustomization.Component.STATUS))
        assertTrue(DmListCustomization.visible(DmListCustomization.Component.GUILD_TAG))
        assertFalse(DmListCustomization.visible(DmListCustomization.Component.MESSAGE))
        assertFalse(DmListCustomization.visible(DmListCustomization.Component.TIMESTAMP))
    }

    @Test
    fun `visibility round-trips through preferences`() {
        val preferences = InMemoryPreferences()
        SharedPreferencesProvider.setInstanceForTesting(preferences)

        DmListCustomization.setVisible(DmListCustomization.Component.TIMESTAMP, true)

        assertTrue(DmListCustomization.visible(DmListCustomization.Component.TIMESTAMP))
        assertTrue("dm_list_customization_timestamp" in preferences.values)
    }

    @Test
    fun `preview replaces status only for a loaded one-to-one dm`() {
        val predicate = DmListCustomization::previewReplacesStatus
        assertTrue(predicate(messageEnabled = true, isDirectMessage = true, preview = "hello"))
        assertFalse(predicate(messageEnabled = false, isDirectMessage = true, preview = "hello"))
        assertFalse(predicate(messageEnabled = true, isDirectMessage = false, preview = "hello"))
        assertFalse(predicate(messageEnabled = true, isDirectMessage = true, preview = null))
        assertFalse(predicate(messageEnabled = true, isDirectMessage = true, preview = "  "))
    }

    private class InMemoryPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any>()

        override fun getAll() = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun edit() = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { if (value == null) values.remove(key) else values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }
}
