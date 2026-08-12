package com.discord.stores

import com.discord.R
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelAuditLogEntry.ActionType
import com.discord.models.domain.ModelAuditLogEntry.TargetType
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.auditlogs.AuditLogUtils
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class AuditLogContractTest {
    private val server = MockWebServer()
    private val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
        .build(RestAPIInterface::class.java)

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `rest fixture preserves every 126_21 action through store and rendering maps`() {
        server.enqueue(MockResponse().setBody(fixture("audit_log_actions.json")))

        val response = requireNotNull(
            api.getAuditLogs(7, 50, null, null, null).toBlocking().single(),
        )
        val parsed = requireNotNull(response.auditLogEntries)
        assertEquals(47, parsed.count { it.actionTypeId != UNKNOWN_ACTION })
        assertEquals(contracts.map(ActionContract::id), parsed.map(ModelAuditLogEntry::actionTypeId))
        assertTrue(
            AuditLogUtils.ALL_ACTION_TYPES.containsAll(
                contracts.dropLast(1).map(ActionContract::id) + ModelAuditLogEntry.ACTION_ALL,
            ),
        )

        val byId = parsed.associateBy(ModelAuditLogEntry::actionTypeId)
        contracts.forEach { expected ->
            val entry = requireNotNull(byId[expected.id])
            assertEquals(expected.action, entry.actionType)
            assertEquals(expected.target, entry.targetType)
            assertEquals(expected.title, AuditLogUtils.getEntryTitle(entry))
            if (expected.id == UNKNOWN_ACTION) {
                assertEquals(0, AuditLogUtils.getActionName(expected.id))
            } else {
                assertNotEquals(0, AuditLogUtils.getActionName(expected.id))
            }
        }
        assertEquals(
            0L,
            requireNotNull(byId.getValue(10).changes).first { it.key == "type" }.newValue,
        )
        val addedRoles = requireNotNull(byId.getValue(25).changes).single().newValue
        require(addedRoles is List<*>)
        val addedRole = addedRoles.single()
        require(addedRole is ModelAuditLogEntry.ChangeNameId)
        assertEquals(6L, addedRole.id)
        assertEquals(7, byId.getValue(21).options?.deleteMemberDays)

        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreAuditLog(
            dispatcher = dispatcher,
            observationDeck = deck,
            restApi = { null },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        store.fetchAuditLogIfNeeded(7)
        scheduler.triggerActions()
        val failed = requireNotNull(store.getAuditLogState(7))
        assertFalse(failed.isLoading)
        assertTrue(failed.hasError)
        dispatcher.schedule { store.handleFetchSuccessInternal(7, response) }
        scheduler.triggerActions()

        val stored = requireNotNull(store.getAuditLogState(7)?.entries)
        assertEquals(
            contracts.map(ActionContract::id),
            stored.map(ModelAuditLogEntry::actionTypeId),
        )
        val pruneChanges = requireNotNull(stored.first { it.actionTypeId == 21 }.changes)
        assertEquals(7, pruneChanges.first { it.key == "prune_delete_days" }.newValue)
        assertFalse(requireNotNull(store.getAuditLogState(7)).isLoading)
        assertFalse(requireNotNull(store.getAuditLogState(7)).hasError)
    }

    @Test
    fun `store completes empty pages paginates once and exposes parse errors`() {
        server.enqueue(MockResponse().setBody(fixture("audit_log_actions.json")))
        server.enqueue(MockResponse().setBody("{\"audit_log_entries\":[]}"))
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreAuditLog(
            dispatcher = dispatcher,
            observationDeck = deck,
            restApi = { api },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.fetchAuditLogIfNeeded(7)
        awaitState(scheduler, store, 7) { it.entries?.isNotEmpty() == true && !it.isLoading }
        val firstPage = requireNotNull(store.getAuditLogState(7))
        assertFalse(firstPage.hasError)
        assertEquals(null, server.takeRequest().requestUrl?.queryParameter("before"))

        store.fetchMoreAuditLogEntries()
        awaitState(scheduler, store, 7) { server.requestCount == 2 && !it.isLoading }
        assertEquals(
            requireNotNull(firstPage.entries).last().id.toString(),
            server.takeRequest().requestUrl?.queryParameter("before"),
        )
        assertEquals(firstPage.entries, store.getAuditLogState(7)?.entries)
        assertFalse(requireNotNull(store.getAuditLogState(7)).hasError)

        store.fetchMoreAuditLogEntries()
        scheduler.triggerActions()
        assertEquals(2, server.requestCount)

        val malformedServer = MockWebServer()
        try {
            malformedServer.enqueue(MockResponse().setBody("{\"audit_log_entries\":[}"))
            val malformedApi = RestAPIBuilder(
                malformedServer.url("/api/").toString(),
                CookieJar.NO_COOKIES,
            ).build(RestAPIInterface::class.java)
            val malformedScheduler = TestScheduler()
            val malformedDeck = ObservationDeck()
            val malformedDispatcher = Dispatcher(malformedScheduler)
            val malformedStore = StoreAuditLog(
                dispatcher = malformedDispatcher,
                observationDeck = malformedDeck,
                restApi = { malformedApi },
            )
            malformedDispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(listOf(malformedStore), malformedDeck),
            )
            malformedStore.fetchAuditLogIfNeeded(8)
            awaitState(malformedScheduler, malformedStore, 8) { it.hasError }
            val error = requireNotNull(malformedStore.getAuditLogState(8))
            assertFalse(error.isLoading)
            assertTrue(error.hasError)
        } finally {
            malformedServer.shutdown()
        }
    }

    @Test
    fun `successful empty first page is loaded rather than loading`() {
        server.enqueue(MockResponse().setBody("{\"audit_log_entries\":[]}"))
        val scheduler = TestScheduler()
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(scheduler)
        val store = StoreAuditLog(
            dispatcher = dispatcher,
            observationDeck = deck,
            restApi = { api },
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.fetchAuditLogIfNeeded(7)
        awaitState(scheduler, store, 7) { it.entries != null && !it.isLoading }
        val state = requireNotNull(store.getAuditLogState(7))
        assertTrue(requireNotNull(state.entries).isEmpty())
        assertFalse(state.hasError)
    }

    /**
     * The actions Discord has added since 126.21.
     *
     * [ModelAuditLogEntry.getTargetType] places them by *range*, so a wrong boundary silently
     * relabels a whole block rather than one action; every id below is a boundary or sits next to
     * one.
     */
    @Test
    fun `actions added since 126_21 map to a type, a target and a title`() {
        modernContracts.forEach { expected ->
            assertEquals(
                "action ${expected.id}",
                expected.action,
                ModelAuditLogEntry.getActionType(expected.id),
            )
            assertEquals(
                "action ${expected.id}",
                expected.target,
                ModelAuditLogEntry.getTargetType(expected.id),
            )
            val entry = ModelAuditLogEntry(expected.id.toLong(), expected.id, 1, 2, null, null, 7, null)
            assertEquals("action ${expected.id}", expected.title, AuditLogUtils.getEntryTitle(entry))
        }
        AuditLogUtils.ALL_ACTION_TYPES.forEach {
            assertNotEquals("filter name for $it", 0, AuditLogUtils.getActionName(it))
        }
    }

    private fun awaitState(
        scheduler: TestScheduler,
        store: StoreAuditLog,
        guildId: Long,
        predicate: (StoreAuditLog.AuditLogState) -> Boolean,
    ) {
        repeat(200) {
            scheduler.triggerActions()
            store.getAuditLogState(guildId)?.takeIf(predicate)?.let { return }
            Thread.sleep(10)
        }
        throw AssertionError("Audit-log store did not reach the expected state")
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/rest/$name"),
    ).readText()

    private data class ActionContract(
        val id: Int,
        val action: ActionType,
        val target: TargetType,
        val title: Int,
    )

    private companion object {
        const val UNKNOWN_ACTION = 999

        val contracts = listOf(
            ActionContract(1, ActionType.UPDATE, TargetType.GUILD, R.string.guild_settings_audit_log_guild_update),
            ActionContract(10, ActionType.CREATE, TargetType.CHANNEL, R.string.guild_settings_audit_log_channel_text_create),
            ActionContract(11, ActionType.UPDATE, TargetType.CHANNEL, R.string.guild_settings_audit_log_channel_update),
            ActionContract(12, ActionType.DELETE, TargetType.CHANNEL, R.string.guild_settings_audit_log_channel_delete),
            ActionContract(13, ActionType.CREATE, TargetType.CHANNEL_OVERWRITE, R.string.guild_settings_audit_log_channel_overwrite_create),
            ActionContract(14, ActionType.UPDATE, TargetType.CHANNEL_OVERWRITE, R.string.guild_settings_audit_log_channel_overwrite_update),
            ActionContract(15, ActionType.DELETE, TargetType.CHANNEL_OVERWRITE, R.string.guild_settings_audit_log_channel_overwrite_delete),
            ActionContract(20, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_member_kick),
            ActionContract(21, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_member_prune),
            ActionContract(22, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_member_ban_add),
            ActionContract(23, ActionType.CREATE, TargetType.USER, R.string.guild_settings_audit_log_member_ban_remove),
            ActionContract(24, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_member_update),
            ActionContract(25, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_member_role_update),
            ActionContract(26, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_member_move),
            ActionContract(27, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_member_disconnect),
            ActionContract(28, ActionType.CREATE, TargetType.USER, R.string.guild_settings_audit_log_bot_add),
            ActionContract(30, ActionType.CREATE, TargetType.ROLE, R.string.guild_settings_audit_log_role_create),
            ActionContract(31, ActionType.UPDATE, TargetType.ROLE, R.string.guild_settings_audit_log_role_update),
            ActionContract(32, ActionType.DELETE, TargetType.ROLE, R.string.guild_settings_audit_log_role_delete),
            ActionContract(40, ActionType.CREATE, TargetType.INVITE, R.string.guild_settings_audit_log_invite_create),
            ActionContract(41, ActionType.UPDATE, TargetType.INVITE, R.string.guild_settings_audit_log_invite_update),
            ActionContract(42, ActionType.DELETE, TargetType.INVITE, R.string.guild_settings_audit_log_invite_delete),
            ActionContract(50, ActionType.CREATE, TargetType.WEBHOOK, R.string.guild_settings_audit_log_webhook_create),
            ActionContract(51, ActionType.UPDATE, TargetType.WEBHOOK, R.string.guild_settings_audit_log_webhook_update),
            ActionContract(52, ActionType.DELETE, TargetType.WEBHOOK, R.string.guild_settings_audit_log_webhook_delete),
            ActionContract(60, ActionType.CREATE, TargetType.EMOJI, R.string.guild_settings_audit_log_emoji_create),
            ActionContract(61, ActionType.UPDATE, TargetType.EMOJI, R.string.guild_settings_audit_log_emoji_update),
            ActionContract(62, ActionType.DELETE, TargetType.EMOJI, R.string.guild_settings_audit_log_emoji_delete),
            ActionContract(72, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_message_delete),
            ActionContract(73, ActionType.DELETE, TargetType.CHANNEL, R.string.guild_settings_audit_log_message_bulk_delete),
            ActionContract(74, ActionType.CREATE, TargetType.USER, R.string.guild_settings_audit_log_message_pin),
            ActionContract(75, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_message_unpin),
            ActionContract(80, ActionType.CREATE, TargetType.INTEGRATION, R.string.guild_settings_audit_log_integration_create),
            ActionContract(81, ActionType.UPDATE, TargetType.INTEGRATION, R.string.guild_settings_audit_log_integration_update),
            ActionContract(82, ActionType.DELETE, TargetType.INTEGRATION, R.string.guild_settings_audit_log_integration_delete),
            ActionContract(83, ActionType.CREATE, TargetType.STAGE_INSTANCE, R.string.guild_settings_audit_log_stage_instance_create),
            ActionContract(84, ActionType.UPDATE, TargetType.STAGE_INSTANCE, R.string.guild_settings_audit_log_stage_instance_update),
            ActionContract(85, ActionType.DELETE, TargetType.STAGE_INSTANCE, R.string.guild_settings_audit_log_stage_instance_delete),
            ActionContract(90, ActionType.CREATE, TargetType.STICKER, R.string.guild_settings_audit_log_sticker_create),
            ActionContract(91, ActionType.UPDATE, TargetType.STICKER, R.string.guild_settings_audit_log_sticker_update),
            ActionContract(92, ActionType.DELETE, TargetType.STICKER, R.string.guild_settings_audit_log_sticker_delete),
            ActionContract(100, ActionType.CREATE, TargetType.GUILD_SCHEDULED_EVENT, R.string.guild_settings_audit_log_scheduled_event_create),
            ActionContract(101, ActionType.UPDATE, TargetType.GUILD_SCHEDULED_EVENT, R.string.guild_settings_audit_log_scheduled_event_update),
            ActionContract(102, ActionType.DELETE, TargetType.GUILD_SCHEDULED_EVENT, R.string.guild_settings_audit_log_scheduled_event_delete),
            ActionContract(110, ActionType.CREATE, TargetType.THREAD, R.string.guild_settings_audit_log_thread_create),
            ActionContract(111, ActionType.UPDATE, TargetType.THREAD, R.string.guild_settings_audit_log_thread_update),
            ActionContract(112, ActionType.DELETE, TargetType.THREAD, R.string.guild_settings_audit_log_thread_delete),
            ActionContract(UNKNOWN_ACTION, ActionType.ALL, TargetType.UNKNOWN, R.string.guild_settings_audit_log_unknown_action),
        )

        val modernContracts = listOf(
            ActionContract(121, ActionType.UPDATE, TargetType.APPLICATION_COMMAND, R.string.guild_settings_audit_log_command_permission_update),
            ActionContract(130, ActionType.CREATE, TargetType.GUILD_SOUNDBOARD, R.string.guild_settings_audit_log_guild_soundboard_sound_created),
            ActionContract(131, ActionType.UPDATE, TargetType.GUILD_SOUNDBOARD, R.string.guild_settings_audit_log_guild_soundboard_sound_updated),
            ActionContract(132, ActionType.DELETE, TargetType.GUILD_SOUNDBOARD, R.string.guild_settings_audit_log_guild_soundboard_sound_deleted),
            ActionContract(140, ActionType.CREATE, TargetType.AUTO_MODERATION_RULE, R.string.guild_settings_audit_log_automod_rule_create),
            ActionContract(141, ActionType.UPDATE, TargetType.AUTO_MODERATION_RULE, R.string.guild_settings_audit_log_automod_rule_update),
            ActionContract(142, ActionType.DELETE, TargetType.AUTO_MODERATION_RULE, R.string.guild_settings_audit_log_automod_rule_delete),
            ActionContract(143, ActionType.DELETE, TargetType.USER, R.string.guild_settings_audit_log_auto_moderation_block_message),
            ActionContract(144, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_auto_moderation_flag_to_channel_message),
            ActionContract(145, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_auto_moderation_user_communication_disabled_message),
            ActionContract(146, ActionType.UPDATE, TargetType.USER, R.string.guild_settings_audit_log_auto_moderation_quarantine_user),
            ActionContract(150, ActionType.CREATE, TargetType.GUILD, R.string.creator_monetization_applied_audit_log_title),
            ActionContract(151, ActionType.UPDATE, TargetType.GUILD, R.string.creator_monetization_terms_accepted_audit_log_title),
            ActionContract(163, ActionType.CREATE, TargetType.ONBOARDING_PROMPT, R.string.guild_settings_audit_log_role_prompt_create),
            ActionContract(164, ActionType.UPDATE, TargetType.ONBOARDING_PROMPT, R.string.guild_settings_audit_log_role_prompt_update),
            ActionContract(165, ActionType.DELETE, TargetType.ONBOARDING_PROMPT, R.string.guild_settings_audit_log_role_prompt_delete),
            ActionContract(166, ActionType.CREATE, TargetType.GUILD_ONBOARDING, R.string.guild_settings_audit_log_onboarding_create),
            ActionContract(167, ActionType.UPDATE, TargetType.GUILD_ONBOARDING, R.string.guild_settings_audit_log_onboarding_update),
            ActionContract(171, ActionType.CREATE, TargetType.GUILD_HOME, R.string.guild_settings_audit_log_guild_home_feature_item),
            ActionContract(172, ActionType.DELETE, TargetType.GUILD_HOME, R.string.guild_settings_audit_log_guild_home_remove_item),
            ActionContract(180, ActionType.ALL, TargetType.GUILD, R.string.guild_settings_audit_log_unknown_action),
            ActionContract(190, ActionType.CREATE, TargetType.HOME_SETTINGS, R.string.guild_settings_audit_log_home_settings_create),
            ActionContract(191, ActionType.UPDATE, TargetType.HOME_SETTINGS, R.string.guild_settings_audit_log_home_settings_update),
            ActionContract(192, ActionType.CREATE, TargetType.VOICE_CHANNEL_STATUS, R.string.guild_settings_audit_log_voice_channel_status_created),
            ActionContract(193, ActionType.DELETE, TargetType.VOICE_CHANNEL_STATUS, R.string.guild_settings_audit_log_voice_channel_status_deleted),
            ActionContract(200, ActionType.UPDATE, TargetType.GUILD_SCHEDULED_EVENT_EXCEPTION, R.string.guild_settings_audit_log_scheduled_event_recurrence_update),
            ActionContract(201, ActionType.UPDATE, TargetType.GUILD_SCHEDULED_EVENT_EXCEPTION, R.string.guild_settings_audit_log_scheduled_event_recurrence_update),
            ActionContract(202, ActionType.DELETE, TargetType.GUILD_SCHEDULED_EVENT_EXCEPTION, R.string.guild_settings_audit_log_scheduled_event_recurrence_delete),
            ActionContract(210, ActionType.UPDATE, TargetType.GUILD_MEMBER_VERIFICATION, R.string.guild_settings_audit_log_member_verification_update),
            ActionContract(211, ActionType.UPDATE, TargetType.GUILD_PROFILE, R.string.guild_settings_audit_log_guild_profile_update),
            ActionContract(212, ActionType.UPDATE, TargetType.GUILD, R.string.guild_settings_audit_log_migrate_pin_permission),
            ActionContract(213, ActionType.UPDATE, TargetType.GUILD, R.string.guild_settings_audit_log_migrate_bypass_slowmode_permission),
        )
    }
}
