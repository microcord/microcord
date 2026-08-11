package com.discord.stores

import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildscheduledevent.GuildRoleSubscriptionRoleBenefits
import com.discord.api.user.User as ApiUser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.Model
import com.discord.models.domain.ModelAppliedGuildBoost
import com.discord.models.domain.ModelAuditLog
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelGuildTemplate
import com.discord.models.domain.ModelMemberVerificationForm
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.error.RestApiError
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler

/**
 * Behavioural coverage for the guild-administration stores.
 *
 * None of these tests go near the REST layer: every store takes its API as `() -> RestAPIInterface?`
 * and is given `{ null }`, so the fetch paths short-circuit and the state machines are driven
 * through their `handle*` entry points. That is deliberate — the interesting behaviour in this
 * batch is what the stores do with a response, not how they ask for one.
 */
class GuildAdminStoresTest {

    // Self-sufficient rather than relying on whichever test class happened to run first.
    @Before
    fun initPersister() {
        Persister.init(Files.createTempDirectory("guild-admin-stores-test").toFile())
    }

    // -- StoreBans ----------------------------------------------------------------------------

    @Test
    fun `a ban event for a guild that was never fetched is ignored`() {
        val f = Fixture()
        f.dispatch { bans.handleBanAdd(ban(guildId = 1, userId = 100)) }

        // Creating a one-entry list here would render as "this server has exactly one ban".
        assertNull(f.bans.getBans(1))

        f.dispatch { bans.handleBansLoaded(1, listOf(ban(guildId = 1, userId = 200))) }
        f.dispatch { bans.handleBanAdd(ban(guildId = 1, userId = 100)) }
        assertEquals(setOf(100L, 200L), f.bans.getBans(1)?.keys)

        f.dispatch { bans.handleBanRemove(ban(guildId = 1, userId = 200)) }
        assertEquals(setOf(100L), f.bans.getBans(1)?.keys)

        f.dispatch { bans.handleGuildRemove(1) }
        assertNull(f.bans.getBans(1))
    }

    // -- StoreGuildIntegrations ---------------------------------------------------------------

    @Test
    fun `integrations refresh only while the integrations screen is open`() {
        val f = Fixture()
        val seen = mutableListOf<Map<Long, ModelGuildIntegration>?>()
        f.integrations.get(1).subscribe { seen += it }

        // GUILD_INTEGRATIONS_UPDATE before the screen opens must not start a fetch.
        f.dispatch { integrations.handleUpdate(ModelGuildIntegration.Update(1)) }
        assertEquals(listOf<Map<Long, ModelGuildIntegration>?>(null), seen)

        f.dispatch { integrations.handleIntegrationScreenOpened(1) }
        f.dispatch { integrations.handleIntegrationsLoaded(1, listOf(integration(id = 55))) }
        assertEquals(setOf(55L), seen.last()?.keys)
    }

    @Test
    fun `closing the integrations screen is only believed after the grace period`() {
        val f = Fixture()
        f.dispatch { integrations.handleIntegrationScreenOpened(1) }
        f.integrations.onIntegrationScreenClosed()

        // Re-opening within the grace period cancels the pending close, so the latch never drops
        // and a re-open does not re-fetch.
        f.integrations.onIntegrationScreenOpened(1)
        f.scheduler.advanceTimeBy(
            StoreGuildIntegrations.HANDLE_CLOSE_INTEGRATION_SCREEN_DELAY_MS * 2,
            TimeUnit.MILLISECONDS,
        )
        f.scheduler.triggerActions()

        // The latch is still set: an update is still acted on rather than dropped.
        f.dispatch { integrations.handleIntegrationsLoaded(1, listOf(integration(id = 55))) }
        assertEquals(setOf(55L), f.integrations.get(1).toBlocking().first()?.keys)
    }

    // -- StoreGuildJoinRequest ----------------------------------------------------------------

    @Test
    fun `join requests are filtered to the current user and fire the approval hook once`() {
        val f = Fixture()
        f.dispatch { users.handleConnectionOpen(readyPayload()) }

        // A moderator sees other people's applications on the same event; they must be ignored.
        f.dispatch { joinRequests.handleGuildJoinRequestCreateOrUpdate(1, joinRequest(userId = 999)) }
        assertNull(f.joinRequests.getGuildJoinRequest(1))

        f.dispatch {
            joinRequests.handleGuildJoinRequestCreateOrUpdate(
                1,
                joinRequest(userId = 7, status = ApplicationStatus.APPROVED, lastSeen = null),
            )
        }
        assertEquals(listOf(1L), f.approvedGuildIds)

        // `last_seen` is what makes the dialog once-only.
        f.dispatch {
            joinRequests.handleGuildJoinRequestCreateOrUpdate(
                1,
                joinRequest(userId = 7, status = ApplicationStatus.APPROVED, lastSeen = "2021-01-01T00:00:00+00:00"),
            )
        }
        assertEquals(listOf(1L), f.approvedGuildIds)

        f.dispatch { joinRequests.handleGuildJoinRequestDelete(userId = 999, guildId = 1) }
        assertTrue(f.joinRequests.getGuildJoinRequest(1) != null)
        f.dispatch { joinRequests.handleGuildJoinRequestDelete(userId = 7, guildId = 1) }
        assertNull(f.joinRequests.getGuildJoinRequest(1))
    }

    // -- StoreGuildTemplates ------------------------------------------------------------------

    @Test
    fun `a rejected template code is terminal while a network failure retries`() {
        val f = Fixture()
        f.dispatch { templates.handleRequestGuildTemplateError("dead", RestApiError.Type.DISCORD_REQUEST_ERROR) }
        assertEquals(StoreGuildTemplates.GuildTemplateState.Invalid, f.templates.getGuildTemplate("dead"))

        // Invalid is terminal: a re-request must not reset it to Loading.
        f.templates.maybeInitTemplateState("dead")
        f.scheduler.triggerActions()
        assertEquals(StoreGuildTemplates.GuildTemplateState.Invalid, f.templates.getGuildTemplate("dead"))

        f.dispatch { templates.handleRequestGuildTemplateError("flaky", RestApiError.Type.NETWORK) }
        assertEquals(StoreGuildTemplates.GuildTemplateState.LoadFailed, f.templates.getGuildTemplate("flaky"))

        // LoadFailed is retryable.
        f.templates.maybeInitTemplateState("flaky")
        f.scheduler.triggerActions()
        assertEquals(StoreGuildTemplates.GuildTemplateState.Loading, f.templates.getGuildTemplate("flaky"))

        // An unmapped error type leaves the code exactly as it was.
        f.dispatch { templates.handleRequestGuildTemplateError("flaky", RestApiError.Type.TIMEOUT) }
        assertEquals(StoreGuildTemplates.GuildTemplateState.Loading, f.templates.getGuildTemplate("flaky"))

        f.dispatch { templates.handleRequestGuildTemplateSuccess(guildTemplate("good")) }
        val resolved = f.templates.getGuildTemplate("good") as StoreGuildTemplates.GuildTemplateState.Resolved
        assertEquals("good", resolved.guildTemplate.code)
    }

    @Test
    fun `the dynamic-link template code round-trips through the dispatcher`() {
        val f = Fixture()
        assertNull(f.templates.getDynamicLinkGuildTemplateCode())
        f.templates.setDynamicLinkGuildTemplateCode("abc")
        f.scheduler.triggerActions()
        assertEquals("abc", f.templates.getDynamicLinkGuildTemplateCode())
        f.templates.clearDynamicLinkGuildTemplateCode()
        f.scheduler.triggerActions()
        assertNull(f.templates.getDynamicLinkGuildTemplateCode())
    }

    // -- StoreGuildMemberVerificationForm ------------------------------------------------------

    @Test
    fun `verification form fetch states are per guild`() {
        val f = Fixture()
        f.dispatch { memberVerification.handleMemberVerificationFormFetchStart(1) }
        assertEquals(
            StoreGuildMemberVerificationForm.FetchStates.FETCHING,
            f.memberVerification.getMemberVerificationFormData(1)?.fetchState,
        )
        assertNull(f.memberVerification.getMemberVerificationFormData(2))

        f.dispatch { memberVerification.handleMemberVerificationFormFetchSuccess(1, verificationForm()) }
        val data = f.memberVerification.getMemberVerificationFormData(1)
        assertEquals(StoreGuildMemberVerificationForm.FetchStates.SUCCEEDED, data?.fetchState)
        assertEquals("1", data?.form?.version)

        // A failure clears the form rather than leaving a stale one behind.
        f.dispatch { memberVerification.handleMemberVerificationFormFetchFailed(1) }
        assertNull(f.memberVerification.getMemberVerificationFormData(1)?.form)
    }

    /** `FETCHING, FAILED, SUCCEEDED` — read from the reference's `$VALUES` array, not source order. */
    @Test
    fun `verification fetch state ordinals match the reference`() {
        assertEquals(
            listOf("FETCHING", "FAILED", "SUCCEEDED"),
            StoreGuildMemberVerificationForm.FetchStates.values().map { it.name },
        )
    }

    // -- StoreGuildBoost ----------------------------------------------------------------------

    @Test
    fun `boost slots filter by guild and survive a single-slot update`() {
        val f = Fixture()
        assertEquals(StoreGuildBoost.State.Loading, f.guildBoost.getState())

        f.dispatch {
            guildBoost.handleFetchStateSuccess(
                listOf(boostSlot(id = 10, guildId = 1), boostSlot(id = 11, guildId = 2), boostSlot(id = 12, guildId = null)),
            )
        }
        assertEquals(setOf(10L, 11L, 12L), (f.guildBoost.getGuildBoostsState() as StoreGuildBoost.State.Loaded).boostSlotMap.keys)
        assertEquals(setOf(10L), (f.guildBoost.getGuildBoostsState(1) as StoreGuildBoost.State.Loaded).boostSlotMap.keys)

        f.guildBoost.updateGuildBoostSlot(boostSlot(id = 12, guildId = 1))
        f.scheduler.triggerActions()
        assertEquals(setOf(10L, 12L), (f.guildBoost.getGuildBoostsState(1) as StoreGuildBoost.State.Loaded).boostSlotMap.keys)

        // A failure replaces the whole state; a later single-slot update has nothing to merge into.
        f.dispatch { guildBoost.handleFetchError() }
        f.guildBoost.updateGuildBoostSlot(boostSlot(id = 13, guildId = 1))
        f.scheduler.triggerActions()
        assertEquals(StoreGuildBoost.State.Failure, f.guildBoost.getState())
    }

    // -- StoreAuditLog ------------------------------------------------------------------------

    @Test
    fun `audit entries merge only when they are the same action on the same target`() {
        val f = Fixture()
        f.dispatch { auditLog.fetchAuditLogIfNeeded(1) }
        f.dispatch {
            auditLog.handleFetchSuccessInternal(
                1,
                auditLogResponse(
                    // Newest first, as the API returns them.
                    auditEntry(id = snowflake(3_000), actionType = 1, targetId = 5, userId = 7, reason = "third"),
                    auditEntry(id = snowflake(2_000), actionType = 1, targetId = 5, userId = 7, reason = "second"),
                    auditEntry(id = snowflake(1_000), actionType = 1, targetId = 9, userId = 7, reason = "first"),
                ),
            )
        }
        val entries = requireNotNull(f.auditLog.getAuditLogState(1)?.entries)

        // The two entries against target 5 collapse; target 9 stays separate.
        assertEquals(2, entries.size)
        assertEquals(listOf(5L, 9L), entries.map { it.targetId })
        // `reason` becomes a synthetic change so it renders like any other line, and merging
        // concatenates both entries' changes.
        assertEquals(listOf("reason", "reason"), entries.first().changes?.map { it.key })
    }

    @Test
    fun `switching guild clears the audit log and its filters are per fetch`() {
        val f = Fixture()
        f.dispatch { auditLog.fetchAuditLogIfNeeded(1) }
        f.dispatch { auditLog.handleFetchSuccessInternal(1, auditLogResponse(auditEntry(id = snowflake(1), actionType = 1, targetId = 5, userId = 7))) }
        assertEquals(1, f.auditLog.getAuditLogState(1)?.entries?.size)

        // Another guild's state is simply not this one.
        assertNull(f.auditLog.getAuditLogState(2))

        f.dispatch { auditLog.fetchAuditLogIfNeeded(2) }
        assertNull(f.auditLog.getAuditLogState(1))
        assertEquals(2L, f.auditLog.getAuditLogState(2)?.guildId)

        // Changing a filter drops the entries so the next fetch starts from the top.
        f.dispatch { auditLog.handleFetchSuccessInternal(2, auditLogResponse(auditEntry(id = snowflake(1), actionType = 1, targetId = 5, userId = 7))) }
        f.auditLog.setAuditLogFilterUserId(42)
        f.scheduler.triggerActions()
        assertNull(f.auditLog.getAuditLogState(2)?.entries)
        assertEquals(42L, f.auditLog.getAuditLogState(2)?.filter?.userFilter)
    }

    @Test
    fun `selecting the same audit row twice collapses it`() {
        val f = Fixture()
        f.auditLog.toggleSelectedState(99)
        f.scheduler.triggerActions()
        assertEquals(99L, f.auditLog.getAuditLogState(-1)?.selectedItemId)
        f.auditLog.toggleSelectedState(99)
        f.scheduler.triggerActions()
        assertNull(f.auditLog.getAuditLogState(-1)?.selectedItemId)
    }

    // -- StoreGuildRoleSubscriptions ----------------------------------------------------------

    @Test
    fun `tiers are stored apart from the group and re-joined in the group's own order`() {
        val f = Fixture()
        f.dispatch {
            roleSubscriptions.handleGuildRoleSubscriptionGroupFetch(
                1,
                groupListing(id = 900, tierIds = listOf(20, 10)),
            )
        }
        f.roleSubscriptions.handleGuildRoleSubscriptionTierListingUpdate(1, tierListing(10))
        f.roleSubscriptions.handleGuildRoleSubscriptionTierListingUpdate(1, tierListing(20))
        f.scheduler.triggerActions()

        val loaded = f.roleSubscriptions.getGuildRoleSubscriptionState(1)
            as StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded
        // Order follows `subscriptionListingsIds`, not insertion order.
        assertEquals(
            listOf(20L, 10L),
            loaded.guildRoleSubscriptionGroupListing?.subscriptionListings?.map { it.id },
        )

        // A guild that has never set monetisation up is Loaded(null), not absent.
        f.dispatch { roleSubscriptions.handleGuildRoleSubscriptionGroupFetch(2, null) }
        assertEquals(
            StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded(null),
            f.roleSubscriptions.getGuildRoleSubscriptionState(2),
        )
    }

    @Test
    fun `a null free-trial list evicts the guild rather than storing an empty map`() {
        val f = Fixture()
        f.dispatch { roleSubscriptions.handleGuildRoleSubscriptionFreeTrialListFetch(1, emptyList()) }
        assertEquals(emptyMap<Long, Any>(), f.roleSubscriptions.getGuildRoleSubscriptionFreeTrials(1))
        f.dispatch { roleSubscriptions.handleGuildRoleSubscriptionFreeTrialListFetch(1, null) }
        assertNull(f.roleSubscriptions.getGuildRoleSubscriptionFreeTrials(1))
    }

    @Test
    fun `free-trial editor options are fixed`() {
        val f = Fixture()
        assertEquals(listOf(null, 10, 25, 50, 100), f.roleSubscriptions.getGuildRoleSubscriptionFreeTrialActiveUserLimitOptions())
        assertEquals(listOf(1, 7), f.roleSubscriptions.getGuildRoleSubscriptionFreeTrialIntervalOptions().map { it.intervalCount })
    }

    @Test
    fun `stores reset to their initial state on logout`() {
        val f = Fixture()
        f.dispatch {
            bans.handleBansLoaded(1, listOf(ban(1, 100)))
            joinRequests.handleGuildJoinRequestCreateOrUpdate(1, joinRequest(userId = 7))
            templates.handleRequestGuildTemplateSuccess(guildTemplate("good"))
            memberVerification.handleMemberVerificationFormFetchStart(1)
            guildBoost.handleFetchStateSuccess(listOf(boostSlot(10, 1)))
            auditLog.handleFetchSuccessInternal(1, auditLogResponse(auditEntry(snowflake(1), 1, 5, 7)))
        }
        f.dispatch {
            bans.handleConnectionOpen()
            joinRequests.reset()
            templates.reset()
            memberVerification.reset()
            guildBoost.reset()
            auditLog.reset()
            roleSubscriptions.reset()
        }
        assertNull(f.bans.getBans(1))
        assertNull(f.joinRequests.getGuildJoinRequest(1))
        assertNull(f.templates.getGuildTemplate("good"))
        assertNull(f.memberVerification.getMemberVerificationFormData(1))
        assertEquals(StoreGuildBoost.State.Loading, f.guildBoost.getState())
        assertNull(f.auditLog.getAuditLogState(1))
        assertFalse(f.roleSubscriptions.getPriceTiersState() is StoreGuildRoleSubscriptions.PriceTierState.Loaded)
    }

    // -- Fixture --------------------------------------------------------------------------------

    private class Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val approvedGuildIds = mutableListOf<Long>()

        val bans = StoreBans(dispatcher, deck) { null }
        val integrations = StoreGuildIntegrations(
            dispatcher,
            restApi = { null },
            timer = { delay, unit -> Observable.timer(delay, unit, scheduler) },
        )
        val joinRequests = StoreGuildJoinRequest(
            users, guilds, dispatcher, deck, { null }, onJoinRequestApproved = { approvedGuildIds += it },
        )
        val templates = StoreGuildTemplates(dispatcher, deck) { null }
        val memberVerification = StoreGuildMemberVerificationForm(dispatcher, deck) { null }
        val guildBoost = StoreGuildBoost(dispatcher, deck) { null }
        val auditLog = StoreAuditLog(dispatcher, deck, { null })
        val roleSubscriptions = StoreGuildRoleSubscriptions(dispatcher, deck, { null })

        init {
            dispatcher.registerDispatchHandlers(
                StoreV2DispatchHandler(
                    listOf(
                        users, guilds, bans, joinRequests, templates,
                        memberVerification, guildBoost, auditLog, roleSubscriptions,
                    ),
                    deck,
                ),
            )
        }

        fun dispatch(block: Fixture.() -> Unit) {
            dispatcher.schedule { block() }
            scheduler.triggerActions()
        }
    }

    // -- Payload builders -----------------------------------------------------------------------

    private fun readyPayload(): ModelPayload = gson.fromJson(
        """{"user":{"id":7,"username":"me","discriminator":"0000"},"private_channels":[],"guilds":[]}""",
        ModelPayload::class.java,
    )

    private fun ban(guildId: Long, userId: Long): ModelBan = ModelBan(guildId, apiUser(userId), null)

    private fun apiUser(userId: Long): ApiUser = gson.fromJson(
        """{"id":$userId,"username":"u$userId","discriminator":"0001"}""",
        ApiUser::class.java,
    )

    private fun integration(id: Long) = ModelGuildIntegration(
        id = id,
        name = "twitch",
        type = ModelGuildIntegration.TYPE_TWITCH,
        enabled = true,
        syncing = false,
        roleId = 1,
        enableEmoticons = true,
        expireBehavior = 0,
        expireGracePeriod = 0,
        user = null,
        account = null,
        syncedAt = null,
        subscriberCount = 0,
    )

    private fun joinRequest(
        userId: Long,
        status: ApplicationStatus = ApplicationStatus.PENDING,
        lastSeen: String? = null,
    ) = GuildJoinRequest(
        userId = userId,
        user = null,
        guildId = 1,
        createdAt = "2021-01-01T00:00:00+00:00",
        applicationStatus = status,
        rejectionReason = null,
        inviteCode = null,
        lastSeen = lastSeen,
        actionedAt = null,
    )

    private fun guildTemplate(code: String) = ModelGuildTemplate(
        creatorId = 7,
        creator = null,
        sourceGuildId = 1,
        serializedSourceGuild = null,
        createdAt = "",
        updatedAt = "",
        usageCount = 0,
        code = code,
        name = "t",
        description = "",
    )

    private fun verificationForm() = ModelMemberVerificationForm(version = "1", formFields = emptyList())

    private fun boostSlot(id: Long, guildId: Long?) = ModelGuildBoostSlot(
        cooldownEndsAt = null,
        id = id,
        subscriptionId = 1,
        premiumGuildSubscription = guildId?.let { ModelAppliedGuildBoost(id, it, 7) },
        canceled = false,
    )

    private fun auditLogResponse(vararg entries: String): ModelAuditLog = gson.fromJson(
        """{"audit_log_entries":[${entries.joinToString(",")}]}""",
        ModelAuditLog::class.java,
    )

    private fun auditEntry(
        id: Long,
        actionType: Int,
        targetId: Long,
        userId: Long,
        reason: String? = null,
    ): String = """{"id":$id,"action_type":$actionType,"target_id":$targetId,"user_id":$userId,
        "reason":${reason?.let { "\"$it\"" } ?: "null"}}"""

    private fun groupListing(id: Long, tierIds: List<Long>) = GuildRoleSubscriptionGroupListing(
        id = id,
        guildId = 1,
        applicationId = 2,
        imageAsset = null,
        description = null,
        subscriptionListingsIds = tierIds,
        subscriptionListings = emptyList(),
        fullServerGate = false,
    )

    private fun tierListing(id: Long) = GuildRoleSubscriptionTierListing(
        id = id,
        applicationId = 2,
        name = "tier$id",
        published = true,
        description = null,
        imageAsset = null,
        subscriptionPlans = emptyList(),
        roleBenefits = GuildRoleSubscriptionRoleBenefits(0, emptyList()),
        roleId = id,
    )

    /** Snowflake carrying [millisAfterEpoch] ms past the Discord epoch, for merge-window tests. */
    private fun snowflake(millisAfterEpoch: Long): Long = millisAfterEpoch shl 22

    companion object {
        private val gson = GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.STATIC)
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            // `ModelAuditLog`/`ModelAuditLogEntry` are hand-rolled `Model` readers, not reflective
            // Gson types; the same factory `RestAPIBuilder` installs is needed to parse them.
            .registerTypeAdapterFactory(Model.TypeAdapterFactory())
            .create()
    }
}
