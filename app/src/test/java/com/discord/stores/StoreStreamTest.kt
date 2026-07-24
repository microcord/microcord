package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.persister.Persister
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.Schedulers

class StoreStreamTest {
    @Before
    fun reset() {
        stream.resetForLogout()
    }

    @Test
    fun mutationsRunOnlyOnDispatcher() {
        val store = StoreStream.getStageInstances()
        assertThrows(IllegalStateException::class.java) { store.handleStageInstanceCreate(stage(1)) }
        assertEquals(emptyMap<Long, StageInstance>(), store.getStageInstances())

        stream.dispatcher.schedule { store.handleStageInstanceCreate(stage(1)) }
        assertEquals(1L, store.getStageInstanceForChannel(1)?.channelId)
    }

    @Test
    fun snapshotsStayStableAcrossDispatches() {
        val store = StoreStream.getStageInstances()
        stream.dispatcher.schedule { store.handleConnectionOpen(emptyList()) }
        val first = store.getStageInstances()

        stream.dispatcher.schedule { store.handleStageInstanceCreate(stage(2)) }

        assertEquals(emptyMap<Long, StageInstance>(), first)
        assertEquals(setOf(2L), store.getStageInstances().keys)
    }

    @Test
    fun persistedSystemSettingsRestore() {
        val store = StoreStream.getUserSettingsSystem()
        store.setTheme("pureEvil", apiSync = false)
        store.setLocale("tr", apiSync = false)
        store.setFontScale(125)

        val restored = StoreUserSettingsSystem({}, stream.dispatcher, stream.observationDeck, prefs, durablePrefs)
        restored.restore()
        assertEquals("pureEvil", restored.getTheme())
        assertEquals("tr", restored.getLocale())
        assertEquals(125, restored.getFontScale())
    }

    @Test
    fun singletonInitializesCompleteSliceOnce() {
        val again = StoreStream.initializeForTesting(directory.toFile(), memoryPreferences(), memoryPreferences(), scheduler)
        assertSame(stream, again)
        assertSame(StoreStream.getAccessibility(), StoreStream.getAccessibility())
        assertSame(StoreStream.getUserSettings(), StoreStream.getUserSettings())
        assertSame(StoreStream.getUserSettingsSystem(), StoreStream.getUserSettingsSystem())
        assertSame(StoreStream.getStageInstances(), StoreStream.getStageInstances())
        assertEquals(true, stream.observeInitialized().toBlocking().first())
    }

    @Test
    fun logoutClearsPersistedAndInMemoryStateAfterOneStableDispatch() {
        val accessibility = StoreStream.getAccessibility()
        val settings = StoreStream.getUserSettings()
        val systemSettings = StoreStream.getUserSettingsSystem()
        val stages = StoreStream.getStageInstances()
        prefs.edit().putString("private-default", "value").commit()
        durablePrefs.edit().putString("private-durable", "value").commit()
        Persister("logout-survival", arrayListOf<String>()).set(arrayListOf("persisted"), persist = true)
        Persister.init(directory.toFile())
        assertEquals(listOf("persisted"), Persister("logout-survival", arrayListOf<String>()).get())

        settings.setContactSyncUpsellShown(true)
        settings.setIsStickerSuggestionsEnabled(false)
        settings.setIsAccessibilityDetectionAllowed(true)
        systemSettings.setTheme(ModelUserSettings.THEME_PURE_EVIL, apiSync = false)
        systemSettings.setLocale("tr", apiSync = false)
        systemSettings.setFontScale(125)
        accessibility.setReducedMotionEnabled(true)
        stream.handleStageInstanceCreate(stage(5))

        val snapshots = mutableListOf<List<Any>>()
        val observer = stream.observationDeck.connect(accessibility, systemSettings, stages, updateOnConnect = false) {
            snapshots += listOf(
                accessibility.isReducedMotionEnabled(),
                systemSettings.getTheme(),
                systemSettings.getFontScale(),
                stages.getStageInstances().size,
            )
        }

        stream.resetForLogout()

        assertEquals(listOf(listOf(false, ModelUserSettings.THEME_LIGHT, -1, 0)), snapshots)
        assertEquals(emptyMap<Long, StageInstance>(), stages.getStageInstances())
        assertFalse(accessibility.isReducedMotionEnabled())
        assertNull(settings.getContactSyncUpsellShown())
        assertEquals(true, settings.getIsStickerSuggestionsEnabled())
        assertEquals(ModelCustomStatusSetting.CLEAR, settings.observeCustomStatus().toBlocking().first())
        assertEquals(ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED, settings.observeExplicitContentFilter().toBlocking().first())
        assertEquals(emptyMap<String, Any?>(), prefs.all)
        assertEquals(emptyMap<String, Any?>(), durablePrefs.all)
        Persister.init(directory.toFile())
        assertEquals(emptyList<String>(), Persister("logout-survival", arrayListOf<String>()).get())

        stream.resetForLogout()
        assertEquals(emptyMap<String, Any?>(), prefs.all)
        assertEquals(emptyMap<String, Any?>(), durablePrefs.all)
        assertEquals(emptyMap<Long, StageInstance>(), stages.getStageInstances())
        assertEquals(2, snapshots.size)
        stream.observationDeck.disconnect(observer)
    }

    companion object {
        private val directory = Files.createTempDirectory("store-stream-test")
        private val scheduler = Schedulers.from(Executor { command -> Thread(command).apply { start(); join() } })
        private val prefs = memoryPreferences()
        private val durablePrefs = memoryPreferences()
        private lateinit var stream: StoreStream

        @JvmStatic
        @BeforeClass
        fun initialize() {
            stream = StoreStream.initializeForTesting(directory.toFile(), prefs, durablePrefs, scheduler)
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }

        private fun stage(channelId: Long) = StageInstance(
            channelId,
            10,
            channelId,
            "topic",
            StageInstancePrivacyLevel.GUILD_ONLY,
            null,
        )

        private fun memoryPreferences(): SharedPreferences {
            val values = linkedMapOf<String, Any?>()
            lateinit var editor: Any
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> editor.also { values[args[0] as String] = args[1] }
                    "remove" -> editor.also { values.remove(args[0] as String) }
                    "clear" -> editor.also { values.clear() }
                    "commit" -> true
                    "apply" -> null
                    else -> editor
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args[0]] ?: args[1]
                    "contains" -> values.containsKey(args[0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
