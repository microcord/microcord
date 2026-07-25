package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserSettings
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.GatewayEventRouter
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.discord.utilities.persister.Persister
import com.discord.restapi.RestAPIBuilder
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun messageGatewayEventsReconcileSelectedChannelHistory() {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
        val parser = IncomingParser(gson)
        fun dispatch(json: String) = parser.parse(json).also { event ->
            stream.handleGatewayDispatch(event.type, requireNotNull(event.data))
        }
        dispatch("""{"op":0,"t":"CHANNEL_CREATE","d":{"id":100,"guild_id":0,"type":1,"name":"dm"}}""")
        StoreStream.selectChannel(0, 100)

        dispatch("""{"op":0,"t":"MESSAGE_CREATE","d":{"id":200,"channel_id":100,"guild_id":1,"author":{"id":7,"username":"user","discriminator":"0000"},"content":"first","type":0}}""")
        assertEquals("first", StoreStream.getMessages().getMessage(100, 200)?.content)

        dispatch("""{"op":0,"t":"MESSAGE_UPDATE","d":{"id":200,"channel_id":100,"content":"edited"}}""")
        assertEquals("edited", StoreStream.getMessages().getMessage(100, 200)?.content)

        dispatch("""{"op":0,"t":"MESSAGE_DELETE","d":{"id":200,"channel_id":100}}""")
        assertNull(StoreStream.getMessages().getMessage(100, 200))
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
    fun typedGatewayStageEventsMutateOnDispatcherAndPublishStableSnapshots() {
        val store = StoreStream.getStageInstances()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
        val parser = IncomingParser(gson)
        val snapshots = mutableListOf<Map<Long, StageInstance>>()
        val observer = stream.observationDeck.connect(store, updateOnConnect = false) { snapshots += store.getStageInstances() }

        parser.parse(stageEvent("STAGE_INSTANCE_CREATE", "first")).also { stream.handleGatewayDispatch(it.type, requireNotNull(it.data)) }
        val first = store.getStageInstances()
        parser.parse(stageEvent("STAGE_INSTANCE_UPDATE", "updated")).also { stream.handleGatewayDispatch(it.type, requireNotNull(it.data)) }
        parser.parse(stageEvent("STAGE_INSTANCE_DELETE", "updated")).also { stream.handleGatewayDispatch(it.type, requireNotNull(it.data)) }

        assertEquals("first", first[3]?.topic)
        assertEquals(listOf("first", "updated", null), snapshots.map { it[3]?.topic })
        assertEquals(emptyMap<Long, StageInstance>(), store.getStageInstances())
        stream.observationDeck.disconnect(observer)
    }

    @Test
    fun readyAndIncrementalFixturesHydrateCoreGraphInOneDispatch() {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
        val parser = IncomingParser(gson)
        val ready = parser.parse(requireNotNull(javaClass.getResource("/fixtures/gateway/ready_core.json")).readText())
        val snapshots = mutableListOf<Pair<Set<Long>, Set<Long>>>()
        val observer = stream.observationDeck.connect(
            StoreStream.getUsers(),
            StoreStream.getChannels(),
            updateOnConnect = false,
        ) {
            snapshots += StoreStream.getUsers().getUsers().keys to StoreStream.getChannels().getChannelsForGuild(300).keys
        }

        stream.handleGatewayDispatch(ready.type, requireNotNull(ready.data))
        val readyChannels = StoreStream.getChannels().observeAllChannels().toBlocking().first()

        assertEquals("ready-user", StoreStream.getUsers().getMe().username)
        assertEquals("Fixture Guild", StoreStream.getGuilds().getGuild(300)?.name)
        assertEquals(setOf(100L, 101L), readyChannels.filterValues { it.guildId == 300L }.keys)
        assertEquals(1, StoreStream.getUserRelationships().getRelationships()[7])
        assertEquals("stage", StoreStream.getStageInstances().getStageInstanceForChannel(102)?.topic)
        assertEquals(200L, StoreStream.getReadStates().getAck(100)?.messageId)
        assertTrue(StoreStream.getChannels().getInitializedForAuthedUser())
        assertEquals(1, snapshots.size)

        val incrementals = JsonParser.parseString(
            requireNotNull(javaClass.getResource("/fixtures/gateway/incremental_core.json")).readText(),
        ).asJsonArray.toList()
        incrementals.dropLast(1).forEach { event ->
            parser.parse(event.toString()).also { incoming ->
                val snapshotCount = snapshots.size
                stream.handleGatewayDispatch(incoming.type, requireNotNull(incoming.data))
                if (incoming.type == "FUTURE_EVENT") assertEquals(snapshotCount, snapshots.size)
            }
        }

        assertEquals("friend-renamed", StoreStream.getUsers().getUsers()[7]?.username)
        assertEquals(2, StoreStream.getUserRelationships().getRelationships()[8])
        assertEquals(true, StoreStream.getUserGuildSettings().getGuildSettings()[300]?.isMuted)
        assertEquals(275L, StoreStream.getReadStates().getAck(100)?.messageId)
        assertEquals("updated-stage", StoreStream.getStageInstances().getStageInstanceForChannel(102)?.topic)
        assertNull(StoreStream.getChannels().getChannel(110))
        assertNull(StoreStream.getChannels().getChannel(111))
        assertEquals("synced-thread", StoreStream.getChannels().getChannel(112)?.name)

        parser.parse(incrementals.last().toString()).also { incoming ->
            stream.handleGatewayDispatch(incoming.type, requireNotNull(incoming.data))
        }
        assertNull(StoreStream.getGuilds().getGuild(300))
        assertNull(StoreStream.getChannels().getChannel(112))
        assertNull(StoreStream.getStageInstances().getStageInstanceForChannel(102))
        stream.observationDeck.disconnect(observer)
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
        assertSame(StoreStream.getUsers(), StoreStream.getUsers())
        assertSame(StoreStream.getGuilds(), StoreStream.getGuilds())
        assertSame(StoreStream.getChannels(), StoreStream.getChannels())
        assertSame(StoreStream.getPresences(), StoreStream.getPresences())
        assertSame(StoreStream.getUserRelationships(), StoreStream.getUserRelationships())
        assertSame(StoreStream.getUserGuildSettings(), StoreStream.getUserGuildSettings())
        assertSame(StoreStream.getReadStates(), StoreStream.getReadStates())
        assertSame(StoreStream.getStageInstances(), StoreStream.getStageInstances())
        assertSame(StoreStream.getNavigation(), StoreStream.getNavigation())
        assertSame(StoreStream.getTabsNavigation(), StoreStream.getTabsNavigation())
        assertEquals(true, stream.observeInitialized().toBlocking().first())
    }

    @Test
    fun mainRoutePriorityIsDeterministic() {
        val navigation = StoreStream.getNavigation()

        navigation.updateAuthState(AuthNavigationState(false, false))
        assertEquals(MainRoute.Loading, navigation.getMainRoute())
        navigation.updateAuthState(AuthNavigationState(true, false))
        assertEquals(MainRoute.LoggedOut, navigation.getMainRoute())
        navigation.updateAuthState(
            AuthNavigationState(true, true, true, RequiredAction.REQUIRE_VERIFIED_EMAIL, StoreNavigation.AgeGate.REGISTER_AGE_GATE),
        )
        assertEquals(MainRoute.Required(RequiredAction.REQUIRE_VERIFIED_EMAIL), navigation.getMainRoute())
        navigation.updateAuthState(AuthNavigationState(true, true, true, ageGate = StoreNavigation.AgeGate.NSFW_CHANNEL_AGE_GATE))
        assertEquals(MainRoute.AgeGated(StoreNavigation.AgeGate.NSFW_CHANNEL_AGE_GATE), navigation.getMainRoute())
        navigation.updateAuthState(AuthNavigationState(true, true, true))
        assertEquals(MainRoute.LoggedIn, navigation.getMainRoute())
    }

    @Test
    fun tabsPublishStableSelectionAndHomePanelAction() {
        val tabs = StoreStream.getTabsNavigation()
        val selected = mutableListOf<com.discord.widgets.tabs.NavigationTab>()
        val actions = mutableListOf<StoreNavigation.PanelAction>()
        val selectedSubscription = tabs.observeSelectedTab().subscribe(selected::add)
        val actionSubscription = StoreStream.getNavigation().getNavigationPanelAction().subscribe(actions::add)

        tabs.selectTab(com.discord.widgets.tabs.NavigationTab.MENTIONS, true)
        tabs.selectHomeTab(StoreNavigation.PanelAction.OPEN, true)

        assertEquals(
            listOf(
                com.discord.widgets.tabs.NavigationTab.HOME,
                com.discord.widgets.tabs.NavigationTab.MENTIONS,
                com.discord.widgets.tabs.NavigationTab.HOME,
            ),
            selected,
        )
        assertEquals(StoreNavigation.PanelAction.OPEN, actions.last())
        selectedSubscription.unsubscribe()
        actionSubscription.unsubscribe()
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

    @Test
    fun gatewayAuthenticationFailureUsesLogoutAndInvalidatesSession() {
        val server = MockWebServer()
        try {
            StoreStream.getAuthentication().apply {
                bindApi(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES).build(com.discord.restapi.RestAPIInterface::class.java))
                setFingerprint(null, true)
                setAuthed("gateway-secret")
            }

            GatewayEventRouter(stream).handleDisconnect(authenticationFailed = true)

            assertNull(StoreStream.getAuthentication().getAuthState())
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
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

        private fun stageEvent(type: String, topic: String) =
            "{\"op\":0,\"s\":4,\"t\":\"$type\",\"d\":{\"id\":1,\"guild_id\":2,\"channel_id\":3,\"topic\":\"$topic\",\"privacy_level\":2,\"invite_code\":null}}"

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
