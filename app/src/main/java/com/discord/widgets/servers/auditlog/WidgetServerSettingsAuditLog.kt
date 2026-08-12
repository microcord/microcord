package com.discord.widgets.servers.auditlog

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isTextyChannelType
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.databinding.WidgetServerSettingsAuditLogBinding
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.domain.ModelAuditLogEntry.TargetType
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreAuditLog.AuditLogState
import com.discord.stores.StoreStream
import com.discord.utilities.auditlogs.AuditLogChangeUtils
import com.discord.utilities.auditlogs.AuditLogUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.user.usersheet.WidgetUserSheet
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * The guild's audit log: a reverse-chronological list of moderator actions, filterable by actor and
 * by action.
 *
 * Two things are unusual about this screen and both come from [com.discord.stores.StoreAuditLog]:
 *
 * - **Paging is driven by the scroll position, not by a cursor the screen holds.** Reaching the
 *   bottom calls `fetchMoreAuditLogEntries()`; the store works out whether another page exists.
 * - **The filters live in the store, not here.** Both filter pickers are separate screens that write
 *   back to the store, so this screen only ever renders the current filter's label.
 *
 * Leaving the screen for good clears the store's whole state, because it holds one guild at a time.
 */
class WidgetServerSettingsAuditLog : AppFragment(R.layout.widget_server_settings_audit_log) {

    private var _binding: WidgetServerSettingsAuditLogBinding? = null
    private val binding get() = checkNotNull(_binding)

    private var guildId: Long = INVALID_GUILD_ID
    private var adapter: WidgetServerSettingsAuditLogAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetServerSettingsAuditLogBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()

        guildId = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
        if (guildId == INVALID_GUILD_ID) {
            // Nothing can be rendered without a guild; the reference toasts and closes rather than
            // leaving an empty screen behind.
            AppToast.show(this, R.string.crash_unexpected)
            appActivity?.finish()
            return
        }

        setActionBarTitle(R.string.guild_settings_label_audit_log)
        setActionBarSubtitle(mostRecentIntent.getStringExtra(INTENT_EXTRA_GUILD_NAME))
        configureFilterMenu()

        binding.serverSettingsAuditLogsUserFilter.setOnClickListener {
            WidgetServerSettingsAuditLogFilter.show(it.context, guildId, FILTER_TYPE_USER)
        }
        binding.serverSettingsAuditLogsActionFilter.setOnClickListener {
            WidgetServerSettingsAuditLogFilter.show(it.context, guildId, FILTER_TYPE_ACTION)
        }

        val recycler = binding.serverSettingsAuditLogsRecycler
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    // `canScrollVertically(1) == false` means "the bottom is on screen". The store
                    // is responsible for ignoring the call when there is nothing more to load.
                    if (!recyclerView.canScrollVertically(SCROLL_DIRECTION_DOWN)) {
                        StoreStream.getAuditLog().fetchMoreAuditLogEntries()
                    }
                }
            },
        )
        adapter = MGRecyclerAdapter
            .configure<MGRecyclerDataPayload, WidgetServerSettingsAuditLogAdapter>(
                WidgetServerSettingsAuditLogAdapter(recycler),
            )
            .apply {
                onAuditLogAvatarClicked = { userId, memberGuildId ->
                    WidgetUserSheet.show(parentFragmentManager, userId, guildId = memberGuildId)
                }
            }
    }

    override fun onDestroyView() {
        binding.serverSettingsAuditLogsRecycler.adapter = null
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * The toolbar's filter button.
     *
     * It is a custom action view rather than a plain menu item, so the click has to be attached to
     * the view itself; the popup it opens is the actual "filter by user / by action" chooser.
     */
    private fun configureFilterMenu() {
        setActionBarOptionsMenu(
            menuResId = R.menu.menu_filter,
            // The item itself is never "selected": its action view swallows the touch.
            onSelected = {},
        ) { menu ->
            val actionView = menu.findItem(R.id.menu_filter)?.actionView ?: return@setActionBarOptionsMenu
            actionView.setOnClickListener { anchor ->
                val popup = PopupMenu(
                    ContextThemeWrapper(anchor.context, R.style.AppTheme_PopupMenu),
                    anchor,
                )
                popup.menuInflater.inflate(R.menu.menu_audit_log_sort, popup.menu)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_audit_log_sort_users ->
                            WidgetServerSettingsAuditLogFilter.show(anchor.context, guildId, FILTER_TYPE_USER)
                        R.id.menu_audit_log_sort_actions ->
                            WidgetServerSettingsAuditLogFilter.show(anchor.context, guildId, FILTER_TYPE_ACTION)
                    }
                    true
                }
                popup.show()
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (guildId == INVALID_GUILD_ID) return
        StoreStream.getAuditLog().fetchAuditLogIfNeeded(guildId)
        Model.get(guildId, requireContext())
            .computationLatest()
            .ui(this)
            .subscribe(::configureUI)
    }

    override fun onDestroy() {
        // The store holds one guild's log at a time. Rotating must not throw it away, so it is only
        // cleared when the whole activity is going.
        if (activity?.isFinishing == true) StoreStream.getAuditLog().clearState()
        super.onDestroy()
    }

    private fun configureUI(model: Model) {
        if (_binding == null) return
        when (model) {
            is Model.Loading ->
                binding.serverSettingsAuditLogsViewFlipper.displayedChild = VIEW_INDEX_LOGS_LOADING
            is Model.Error -> {
                adapter?.configure(emptyList())
                binding.serverSettingsAuditLogsViewFlipper.displayedChild = VIEW_INDEX_LOGS_CONTENT
                binding.serverSettingsAuditLogsViewResultsFlipper.displayedChild = RESULTS_VIEW_INDEX_NO_LOGS
                binding.noLogsTitle.setText(R.string.guild_settings_label_audit_log_error_title)
                binding.noLogsBody.setText(R.string.guild_settings_label_audit_log_error_body)
            }
            // Losing View Audit Log while the screen is open is the same as never having reached it.
            is Model.Unauthorized -> appActivity?.onBackPressed()
            is Model.Loaded -> {
                adapter?.configure(model.auditLogEntryItems)
                binding.serverSettingsAuditLogsViewFlipper.displayedChild = VIEW_INDEX_LOGS_CONTENT
                binding.serverSettingsAuditLogsUserFilter.text = model.usernameFilterText
                binding.serverSettingsAuditLogsActionFilter.text = model.actionFilterText
                binding.noLogsTitle.setText(R.string.guild_settings_label_audit_log_empty_title)
                binding.noLogsBody.setText(R.string.guild_settings_label_audit_log_empty_body)
                // An empty result is not an empty log — it usually means the filters exclude
                // everything, so the empty view sits *inside* the loaded state.
                binding.serverSettingsAuditLogsViewResultsFlipper.displayedChild = if (
                    model.auditLogEntryItems.isEmpty()
                ) {
                    RESULTS_VIEW_INDEX_NO_LOGS
                } else {
                    RESULTS_VIEW_INDEX_LOGS
                }
            }
        }
    }

    sealed class Model {

        /** The first page has not arrived. Distinct from "arrived and empty". */
        object Loading : Model()

        /** The request or response parser failed, so loading has reached a terminal state. */
        object Error : Model()

        /**
         * The user may not read this guild's audit log any more.
         *
         * The reference has no such state — it relies on `WidgetServerSettings` never offering the
         * row without [com.discord.api.permission.Permission.VIEW_AUDIT_LOG]. That leaves the screen
         * showing a stale log if the permission is revoked while it is open, so the check is
         * repeated here and the screen closes itself, matching every other server-settings page in
         * this project.
         */
        object Unauthorized : Model()

        data class Loaded(
            val auditLogEntryItems: List<MGRecyclerDataPayload>,
            val usernameFilterText: CharSequence,
            val actionFilterText: CharSequence,
        ) : Model()

        companion object {

            /**
             * Combines the store's raw page with everything needed to name the things it references.
             *
             * The user map is throttled to one emission every three seconds: a busy guild updates
             * presences constantly, and every emission would otherwise re-render the entire list.
             */
            fun get(guildId: Long, context: Context): Observable<Model> = Observable.combineLatest(
                StoreStream.getAuditLog().observeAuditLogState(guildId),
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getChannels().observeChannelsForGuild(guildId),
                StoreStream.getUsers().observeAllUsers()
                    .leadingEdgeThrottle(USER_THROTTLE_SECONDS, TimeUnit.SECONDS),
                StoreStream.getGuilds().observeRoles(guildId),
                StoreStream.getGuilds().observeComputed(guildId),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
            ) { auditLogState, guild, channels, users, roles, guildMembers, guildPermissions ->
                toModel(
                    context,
                    guildId,
                    auditLogState,
                    guild,
                    channels,
                    users,
                    roles,
                    guildMembers,
                    guildPermissions,
                )
            }.distinctUntilChanged()

            @Suppress("LongParameterList")
            private fun toModel(
                context: Context,
                guildId: Long,
                auditLogState: AuditLogState,
                guild: Guild?,
                channels: Map<Long, Channel>,
                users: Map<Long, User>,
                roles: Map<Long, com.discord.api.role.GuildRole>,
                guildMembers: Map<Long, GuildMember>,
                guildPermissions: Long?,
            ): Model {
                // Owners and administrators are already resolved to `Permission.ALL` by
                // `PermissionUtils.computeNonThreadPermissions`, so this single bit test covers them
                // too — no separate owner check is needed.
                if (!PermissionUtils.can(Permission.VIEW_AUDIT_LOG, guildPermissions)) {
                    return Unauthorized
                }
                if (auditLogState.hasError) return Error
                val entries = auditLogState.entries
                if (entries == null || guild == null) return Loading

                val targets = buildTargets(guildId, auditLogState, guild, channels, users, roles)

                val items = mutableListOf<MGRecyclerDataPayload>()
                for (entry in entries) {
                    val actor = auditLogState.users[entry.userId]
                    items.add(
                        WidgetServerSettingsAuditLogAdapter.AuditLogEntryItem(
                            auditLogEntry = entry,
                            isSelected = auditLogState.selectedItemId == entry.id,
                            user = actor,
                            guildMember = guildMembers[entry.userId],
                            headerText = AuditLogUtils.getHeaderString(
                                entry,
                                actor?.getUserNameWithDiscriminator() ?: "",
                                context,
                                targets,
                                channelPrefixFor(entry, channels),
                            ),
                            changeText = AuditLogChangeUtils.getChangeSummary(context, entry, targets),
                            hasChangesToRender = AuditLogChangeUtils.hasChangesToRender(entry),
                            timeStampText = AuditLogUtils.getTimestampString(entry, context),
                        ),
                    )
                }
                if (auditLogState.isLoading) {
                    items.add(WidgetServerSettingsAuditLogAdapter.AuditLogLoadingItem())
                }

                return Loaded(
                    auditLogEntryItems = items,
                    usernameFilterText = users[auditLogState.filter.userFilter]
                        ?.getUserNameWithDiscriminator()
                        ?: context.getString(R.string.guild_settings_filter_all_users),
                    actionFilterText = context.getString(
                        AuditLogUtils.getActionName(auditLogState.filter.actionFilter),
                    ),
                )
            }

            /**
             * Names for everything the page's entries can point at, by kind and id.
             *
             * Seeded with the store's scraped names for *deleted* targets and then overwritten with
             * live ones, so an object that still exists always wins over the name it had when it was
             * removed.
             */
            private fun buildTargets(
                guildId: Long,
                auditLogState: AuditLogState,
                guild: Guild,
                channels: Map<Long, Channel>,
                users: Map<Long, User>,
                roles: Map<Long, com.discord.api.role.GuildRole>,
            ): Map<TargetType, MutableMap<Long, CharSequence?>> {
                val targets = HashMap<TargetType, MutableMap<Long, CharSequence?>>()
                fun bucket(type: TargetType) = targets.getOrPut(type) { HashMap() }

                // Names recoverable from the entries themselves, for objects deleted mid-page.
                auditLogState.deletedTargets.forEach { (type, byId) -> bucket(type).putAll(byId) }
                for (entry in auditLogState.entries.orEmpty()) {
                    val name: CharSequence? = when (entry.targetType) {
                        TargetType.CHANNEL, TargetType.CHANNEL_OVERWRITE ->
                            resolveChannelName(channels, entry)
                        TargetType.USER -> users[entry.targetId]?.getUserNameWithDiscriminator()
                        TargetType.ROLE -> roles[entry.targetId]?.name
                        else -> null
                    }
                    if (name != null) bucket(entry.targetType)[entry.targetId] = name
                }

                val channelNames = bucket(TargetType.CHANNEL)
                channels.values.forEach { channel ->
                    channelNames[channel.id] =
                        (if (channel.isGuildTextyChannel()) "#" else "") + channel.getDisplayName()
                }
                val userNames = bucket(TargetType.USER)
                auditLogState.users.forEach { (id, user) ->
                    userNames[id] = user.getUserNameWithDiscriminator()
                }
                // Every guild-scoped target names the guild itself.
                listOf(
                    TargetType.GUILD,
                    TargetType.GUILD_HOME,
                    TargetType.GUILD_PROFILE,
                    TargetType.GUILD_ONBOARDING,
                    TargetType.GUILD_MEMBER_VERIFICATION,
                ).forEach { bucket(it)[guildId] = guild.name }

                val webhookNames = bucket(TargetType.WEBHOOK)
                auditLogState.webhooks.forEach { webhookNames[it.id] = it.name }
                val integrationNames = bucket(TargetType.INTEGRATION)
                auditLogState.integrations.forEach { integrationNames[it.id] = it.name }
                val eventNames = bucket(TargetType.GUILD_SCHEDULED_EVENT)
                auditLogState.guildScheduledEvents.forEach { eventNames[it.id] = it.name }
                val threadNames = bucket(TargetType.THREAD)
                auditLogState.threads.forEach { threadNames[it.id] = it.name }
                val ruleNames = bucket(TargetType.AUTO_MODERATION_RULE)
                auditLogState.autoModerationRules.forEach { ruleNames[it.id] = it.name }
                // A chat command reads as a command rather than as a name, hence the slash; the
                // other command kinds are menu entries and take none.
                val commandNames = bucket(TargetType.APPLICATION_COMMAND)
                auditLogState.applicationCommands.forEach {
                    commandNames[it.id] =
                        if (it.type == COMMAND_TYPE_CHAT_INPUT) "/${it.name}" else it.name
                }
                return targets
            }

            /**
             * `"#general"` for a live channel, or the name the delete entry itself recorded.
             *
             * The fallback only applies to `channel_delete`: for any other action a channel that is
             * not in the store is simply unresolvable.
             */
            private fun resolveChannelName(
                channels: Map<Long, Channel>,
                entry: ModelAuditLogEntry,
            ): String? {
                val channel = channels[entry.targetId]
                if (channel != null) {
                    return (if (channel.isGuildTextyChannel()) "#" else "") + channel.getDisplayName()
                }
                if (entry.actionTypeId != ACTION_CHANNEL_DELETE) return null
                val name = entry.changes?.firstOrNull { it.key == "name" }?.oldValue as? String
                    ?: return null
                val type = entry.changes?.firstOrNull { it.key == "type" }?.oldValue as? Long
                    ?: return null
                return if (isTextyChannelType(type.toInt())) "#$name" else name
            }

            /**
             * `"#"` when the entry's target is a live text channel.
             *
             * This is a *separate* value from the name in the target map because
             * `AuditLogUtils.getTargetText` only applies the prefix on the fallback path, where no
             * resolved name exists.
             */
            private fun channelPrefixFor(
                entry: ModelAuditLogEntry,
                channels: Map<Long, Channel>,
            ): String {
                val referencesChannel = entry.targetType == TargetType.CHANNEL ||
                    entry.targetType == TargetType.CHANNEL_OVERWRITE
                if (!referencesChannel) return ""
                return if (channels[entry.targetId]?.isGuildTextyChannel() == true) "#" else ""
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val INTENT_EXTRA_GUILD_NAME = "GUILD_NAME"
        private const val INVALID_GUILD_ID = -1L

        private const val SCROLL_DIRECTION_DOWN = 1
        private const val USER_THROTTLE_SECONDS = 3L
        private const val ACTION_CHANNEL_DELETE = 12
        private const val COMMAND_TYPE_CHAT_INPUT = 1

        private const val VIEW_INDEX_LOGS_LOADING = 0
        private const val VIEW_INDEX_LOGS_CONTENT = 1
        private const val RESULTS_VIEW_INDEX_LOGS = 0
        private const val RESULTS_VIEW_INDEX_NO_LOGS = 1

        const val FILTER_TYPE_USER = 0
        const val FILTER_TYPE_ACTION = 1

        fun launch(context: Context, guildId: Long, guildName: String) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsAuditLog::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_GUILD_NAME, guildName),
            )
        }
    }
}
