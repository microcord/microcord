package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.domain.ModelAuditLog
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelWebhook
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.auditlogs.AuditLogChangeUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import kotlin.math.abs
import rx.Observable

/**
 * The guild-settings audit log: one guild at a time, paged, filtered and post-processed.
 *
 * **Only one guild is held.** [AuditLogState.guildId] identifies it and [getAuditLogState] returns
 * `null` for any other id; switching guilds clears everything. That is why the state is a single
 * value rather than a map — the screen is modal and the log can be very large.
 *
 * **Paging has no explicit cursor.** [fetchAuditLogs] derives `before` from the id of the last
 * entry already held and compares it with [cutoffTimestamp]; when they match, the previous page was
 * the last one and the request is skipped. The initial value dance (`null` becomes `0L` so the
 * first, cursor-less request is not mistaken for a repeat) is reproduced from the reference.
 *
 * **Entries are merged and rewritten before storage** ([transformEntries]) — see that function.
 *
 * **[AuditLogState.deletedTargets] is a side-channel.** Once a target is deleted the log entries
 * that reference it can no longer resolve a name, so the name is scraped out of the delete entry's
 * own `name` change and remembered by (target type, target id) for every later entry to use.
 */
class StoreAuditLog(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /**
     * Splits a `permissions` bitfield change into one `allow` and one `deny` change, so the UI can
     * render "granted X, Y" and "denied Z" instead of two opaque numbers.
     *
     * Injected rather than called directly so a store test can assert the merge/rewrite pipeline
     * without a `Context`.
     */
    private val transformPermissionChange: (ModelAuditLogEntry.Change) -> List<ModelAuditLogEntry.Change> =
        AuditLogChangeUtils::transformPermissionChange,
    /** The channel-overwrite counterpart: `allow`/`deny` are kept, and cleared bits become `reset`. */
    private val transformPermissionOverride: (ModelAuditLogEntry.Change) -> List<ModelAuditLogEntry.Change> =
        AuditLogChangeUtils::transformPermissionOverride,
) : StoreV2() {

    data class AuditLogFilter(val userFilter: Long, val actionFilter: Int) {
        companion object {
            /** `0` means "no filter" on both axes; the REST layer converts it back to `null`. */
            fun emptyFilter() = AuditLogFilter(NO_USER_FILTER, NO_ACTION_FILTER)
        }
    }

    data class AuditLogState(
        val guildId: Long = NO_GUILD,
        val users: Map<Long, User> = emptyMap(),
        /** `null` means "never fetched", which is not the same as "fetched and empty". */
        val entries: List<ModelAuditLogEntry>? = null,
        val webhooks: List<ModelWebhook> = emptyList(),
        val integrations: List<ModelGuildIntegration> = emptyList(),
        val guildScheduledEvents: List<GuildScheduledEvent> = emptyList(),
        val threads: List<Channel> = emptyList(),
        val selectedItemId: Long? = null,
        val filter: AuditLogFilter = AuditLogFilter.emptyFilter(),
        val deletedTargets: Map<ModelAuditLogEntry.TargetType, MutableMap<Long, CharSequence?>> = emptyMap(),
        val isLoading: Boolean = false,
        val hasError: Boolean = false,
    )

    private var state = AuditLogState()
    private var stateSnapshot = AuditLogState()

    /** Id of the last entry of the previous page; `null` before the first request. */
    private var cutoffTimestamp: Long? = null

    /** `null` unless [guildId] is the guild currently loaded. */
    fun getAuditLogState(guildId: Long): AuditLogState? =
        stateSnapshot.takeIf { it.guildId == guildId }

    /**
     * Emits the guild's log, starting or continuing the fetch on subscribe. Nulls are filtered, so
     * the stream stays silent while a different guild is loaded rather than emitting a foreign
     * state.
     */
    fun observeAuditLogState(guildId: Long): Observable<AuditLogState> =
        observationDeck.connectRx(this) { getAuditLogState(guildId) }
            .distinctUntilChanged()
            .doOnNext { fetchAuditLogIfNeeded(guildId) }
            .filter { it != null }
            .map { it!! }

    /** Switches guild if needed, and requests the first page if none is held. */
    fun fetchAuditLogIfNeeded(guildId: Long) = dispatcher.schedule {
        val isDifferentGuild = guildId != state.guildId
        val needsFirstPage = state.entries == null && !state.isLoading && !state.hasError
        if (isDifferentGuild) clearStateInternal()
        if (isDifferentGuild || needsFirstPage) fetchAuditLogs(guildId, state.filter)
    }

    fun fetchMoreAuditLogEntries() = dispatcher.schedule {
        fetchAuditLogs(state.guildId, state.filter)
    }

    /** Changing a filter drops the entries so the next fetch starts from the top. */
    fun setAuditLogFilterUserId(userId: Long) = dispatcher.schedule {
        if (userId != state.filter.userFilter) {
            cutoffTimestamp = null
            state = state.copy(entries = null, hasError = false)
        }
        state = state.copy(filter = state.filter.copy(userFilter = userId))
        markChanged()
    }

    fun setAuditLogFilterActionId(actionId: Int) = dispatcher.schedule {
        if (actionId != state.filter.actionFilter) {
            cutoffTimestamp = null
            state = state.copy(entries = null, hasError = false)
        }
        state = state.copy(filter = state.filter.copy(actionFilter = actionId))
        markChanged()
    }

    /** Expands one row, or collapses it if it was already expanded. */
    fun toggleSelectedState(selectedItemId: Long) = dispatcher.schedule {
        state = state.copy(
            selectedItemId = if (state.selectedItemId == selectedItemId) null else selectedItemId,
        )
        markChanged()
    }

    fun clearState() = dispatcher.schedule { clearStateInternal() }

    @StoreThread
    internal fun clearStateInternal() {
        cutoffTimestamp = null
        state = AuditLogState()
        markChanged()
    }

    @StoreThread
    internal fun reset() = clearStateInternal()

    @StoreThread
    private fun fetchAuditLogs(guildId: Long, filter: AuditLogFilter) {
        if (state.isLoading) return
        val before = state.entries?.lastOrNull()?.id
        // First request: seed the cutoff so `before == null` does not read as "already fetched".
        if (cutoffTimestamp == null) cutoffTimestamp = 0L
        if (before == cutoffTimestamp) return
        cutoffTimestamp = before
        state = state.copy(guildId = guildId, isLoading = true, hasError = false)
        markChanged()

        val api = restApi() ?: return handleFetchFailureInternal()
        api.getAuditLogs(
            guildId = guildId,
            limit = PAGE_SIZE,
            before = before,
            userId = filter.userFilter.takeIf { it != NO_USER_FILTER },
            actionType = filter.actionFilter.takeIf { it != NO_ACTION_FILTER },
        )
            .restSubscribeOn(false)
            .appSubscribe(
                StoreAuditLog::class.java,
                errorHandler = { dispatcher.schedule { handleFetchFailureInternal() } },
            ) { newAuditLog ->
                dispatcher.schedule { handleFetchSuccessInternal(guildId, newAuditLog) }
            }
    }

    @StoreThread
    internal fun handleFetchFailureInternal() {
        cutoffTimestamp = null
        state = state.copy(isLoading = false, hasError = true)
        markChanged()
    }

    /**
     * Every collection in the response is *appended*, never replaced: a later page carries the
     * users/webhooks/integrations its own entries reference, and the earlier pages still need
     * theirs.
     */
    @StoreThread
    internal fun handleFetchSuccessInternal(guildId: Long, newAuditLog: ModelAuditLog) {
        newAuditLog.users?.let { users ->
            state = state.copy(
                users = state.users + users.associate { it.id to CoreUser(it) },
            )
        }
        newAuditLog.webhooks?.let { state = state.copy(webhooks = state.webhooks + it) }
        newAuditLog.integrations?.let { state = state.copy(integrations = state.integrations + it) }
        newAuditLog.guildScheduledEvents?.let {
            state = state.copy(guildScheduledEvents = state.guildScheduledEvents + it)
        }
        newAuditLog.threads?.let { state = state.copy(threads = state.threads + it) }

        val transformed = transformEntries(guildId, newAuditLog.auditLogEntries.orEmpty())
        state = state.copy(
            entries = state.entries.orEmpty() + transformed,
            isLoading = false,
            hasError = false,
        )
        markChanged()
    }

    /**
     * Rewrites a page of raw entries into what the list renders.
     *
     * Three things happen at once, which is why this is one pass:
     *
     * 1. **Change normalisation** — `reason` becomes a synthetic change so it renders like any
     *    other line; permission bitfields and channel overwrites are expanded through the two
     *    seams; a prune entry gains a synthetic `prune_delete_days` change.
     * 2. **Merging** — consecutive entries by the same user, on the same target, with the same
     *    action and options, within 30 minutes, collapse into one with their changes concatenated.
     *    At most [MAX_MERGES] in a row, and never for invites, message deletes, or member
     *    move/disconnect, where each occurrence is individually meaningful.
     * 3. **Deleted-target names** — see [AuditLogState.deletedTargets].
     *
     * The page is walked **in reverse** and results are pushed to index 0, so the output is
     * newest-first while merging always compares against the immediately older neighbour.
     */
    @StoreThread
    private fun transformEntries(
        guildId: Long,
        rawEntries: List<ModelAuditLogEntry>,
    ): List<ModelAuditLogEntry> {
        val result = ArrayList<ModelAuditLogEntry>()
        var numMerges = 0

        for (rawEntry in rawEntries.asReversed()) {
            val changes = ArrayList<ModelAuditLogEntry.Change>()
            rawEntry.reason?.let { changes.add(ModelAuditLogEntry.Change(KEY_REASON, null, it)) }

            var nameChange: ModelAuditLogEntry.Change? = null
            var typeChange: ModelAuditLogEntry.Change? = null
            rawEntry.changes?.forEach { change ->
                when (change.key) {
                    KEY_ALLOW, KEY_DENY, KEY_RESET -> {
                        changes.addAll(transformPermissionOverride(change))
                        return@forEach
                    }
                    KEY_PERMISSIONS -> {
                        changes.addAll(transformPermissionChange(change))
                        return@forEach
                    }
                }
                changes.add(change)
                when (change.key) {
                    KEY_NAME -> nameChange = change
                    KEY_TYPE -> typeChange = change
                }
            }

            if (rawEntry.actionTypeId == ModelAuditLogEntry.ACTION_MEMBER_PRUNE) {
                changes.add(
                    ModelAuditLogEntry.Change(
                        KEY_PRUNE_DELETE_DAYS,
                        null,
                        rawEntry.options?.deleteMemberDays ?: 1,
                    ),
                )
            }

            val entry = ModelAuditLogEntry(
                rawEntry.id,
                rawEntry.actionTypeId,
                rawEntry.targetId,
                rawEntry.userId,
                changes,
                rawEntry.options,
                guildId,
                null,
            )

            val previous = result.firstOrNull()
            if (!shouldMergeEntries(previous, entry, numMerges)) {
                recordDeletedTarget(entry, nameChange, typeChange)
                result.add(0, entry)
                numMerges = 0
                continue
            }

            val mergedChanges = previous?.changes?.toMutableList()?.also {
                it.addAll(entry.changes.orEmpty())
            } ?: entry.changes
            result[0] = ModelAuditLogEntry(
                previous?.id ?: entry.id,
                previous?.actionTypeId ?: entry.actionTypeId,
                previous?.targetId ?: entry.targetId,
                previous?.userId ?: entry.userId,
                mergedChanges,
                previous?.options ?: entry.options,
                guildId,
                timestampStart(entry),
            )
            numMerges++
        }
        return result
    }

    @StoreThread
    private fun recordDeletedTarget(
        entry: ModelAuditLogEntry,
        nameChange: ModelAuditLogEntry.Change?,
        typeChange: ModelAuditLogEntry.Change?,
    ) {
        if (entry.actionType != ModelAuditLogEntry.ActionType.DELETE || nameChange == null) return
        var name = nameChange.oldValue as? String
        if (entry.targetType == ModelAuditLogEntry.TargetType.CHANNEL) {
            // Reference bug reproduced: the old channel `type` is parsed as a Long (see
            // `ModelAuditLogEntry.ValueFactory`) and the reference compares it against a boxed
            // Integer, so this branch never fires and a deleted text channel is never rendered
            // with its leading '#'. Fixing it means comparing numerically.
            if (typeChange?.oldValue == 0) name = "#$name"
        }
        val deletedTargets = state.deletedTargets.toMutableMap()
        val byId = deletedTargets[entry.targetType]
        if (byId == null) {
            deletedTargets[entry.targetType] = mutableMapOf(entry.targetId to name)
        } else {
            byId[entry.targetId] = name
        }
        state = state.copy(deletedTargets = deletedTargets)
    }

    private fun shouldMergeEntries(
        prevEntry: ModelAuditLogEntry?,
        entry: ModelAuditLogEntry,
        numMerges: Int,
        timeWindowMins: Int = MERGE_WINDOW_MINUTES,
        maxMerges: Int = MAX_MERGES,
    ): Boolean {
        if (prevEntry == null) return false
        if (prevEntry.actionTypeId != entry.actionTypeId) return false
        if (prevEntry.targetId != entry.targetId) return false
        if (prevEntry.userId != entry.userId) return false
        if (prevEntry.options != entry.options) return false
        if (numMerges >= maxMerges) return false
        if (entry.targetType == ModelAuditLogEntry.TargetType.INVITE) return false
        if (entry.actionTypeId in NEVER_MERGED_ACTIONS) return false
        return abs(timestampStart(entry) - timestampStart(prevEntry)) < timeWindowMins * 60_000L
    }

    @StoreThread
    override fun snapshotData() {
        stateSnapshot = state.copy(
            users = HashMap(state.users),
            entries = state.entries?.let(::ArrayList),
            webhooks = ArrayList(state.webhooks),
            integrations = ArrayList(state.integrations),
            guildScheduledEvents = ArrayList(state.guildScheduledEvents),
            threads = ArrayList(state.threads),
            deletedTargets = state.deletedTargets.mapValues { HashMap(it.value) },
        )
    }

    companion object {
        private const val NO_GUILD = -1L
        private const val NO_USER_FILTER = 0L
        private const val NO_ACTION_FILTER = 0

        /** `RestAPI.getAuditLogs` hardcodes this; confirmed at bytecode level (`bipush 50`). */
        internal const val PAGE_SIZE = 50
        internal const val MERGE_WINDOW_MINUTES = 30
        internal const val MAX_MERGES = 50

        /**
         * Actions where each occurrence stands alone: a bulk-delete count, and who was moved or
         * disconnected, are lost if two entries collapse.
         */
        private val NEVER_MERGED_ACTIONS = setOf(
            ModelAuditLogEntry.ACTION_MESSAGE_DELETE,
            ModelAuditLogEntry.ACTION_MESSAGE_BULK_DELETE,
            ModelAuditLogEntry.ACTION_MEMBER_MOVE,
            ModelAuditLogEntry.ACTION_MEMBER_DISCONNECT,
        )

        private const val KEY_REASON = "reason"
        private const val KEY_ALLOW = "allow"
        private const val KEY_DENY = "deny"
        private const val KEY_RESET = "reset"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_NAME = "name"
        private const val KEY_TYPE = "type"
        private const val KEY_PRUNE_DELETE_DAYS = "prune_delete_days"

        /**
         * Reference: `AuditLogUtils.getTimestampStart`. Inlined because
         * `com.discord.utilities.auditlogs` has not been reconstructed; move it there when it is.
         */
        private fun timestampStart(entry: ModelAuditLogEntry): Long =
            SnowflakeUtils.toTimestamp(entry.id)
    }
}
