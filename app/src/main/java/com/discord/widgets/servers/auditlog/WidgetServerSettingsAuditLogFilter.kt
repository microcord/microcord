package com.discord.widgets.servers.auditlog

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreAuditLog.AuditLogState
import com.discord.stores.StoreStream
import com.discord.utilities.auditlogs.AuditLogUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.utilities.search.SearchUtils
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.extensions.setSingleLineHint
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The "filter by actor" / "filter by action" picker for the audit log.
 *
 * One screen serves both, chosen by [ARG_FILTER_TYPE]. Picking a row writes the new filter into
 * [com.discord.stores.StoreAuditLog] and immediately closes — the audit-log screen behind it is
 * already subscribed to that store, so it re-renders on its own.
 */
class WidgetServerSettingsAuditLogFilter : AppFragment(R.layout.widget_audit_log_filter) {

    private var adapter: WidgetServerSettingsAuditLogFilterAdapter? = null
    private val filterPublisher: BehaviorSubject<String> = BehaviorSubject.create("")

    private var filterType: Int = FILTER_TYPE_UNSET
    private var guildId: Long = NO_GUILD

    private lateinit var searchInput: TextInputLayout

    /** Picking a filter is the whole purpose of this screen, so it closes on selection. */
    private val onFilterSelected: () -> Unit = { activity?.finish() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        filterType = mostRecentIntent.getIntExtra(ARG_FILTER_TYPE, FILTER_TYPE_UNSET)
        guildId = mostRecentIntent.getLongExtra(ARG_GUILD_ID, NO_GUILD)

        setActionBarTitle(R.string.guild_settings_label_audit_log)
        setActionBarSubtitle(
            if (filterType == FILTER_TYPE_USER) {
                R.string.guild_settings_filter_user
            } else {
                R.string.guild_settings_filter_action
            },
        )

        searchInput = view.findViewById(R.id.audit_log_filter_input)
        searchInput.setSingleLineHint(
            if (filterType == FILTER_TYPE_USER) R.string.search_members else R.string.search_actions,
        )
        searchInput.editText?.addBindedTextWatcher(this) { filterPublisher.onNext(it.toString()) }

        val recycler = view.findViewById<RecyclerView>(R.id.audit_log_filter_recycler)
        adapter = MGRecyclerAdapter
            .configure<MGRecyclerDataPayload, WidgetServerSettingsAuditLogFilterAdapter>(
                WidgetServerSettingsAuditLogFilterAdapter(recycler),
            )
            .apply {
                onAuditLogAvatarClicked = { userId, memberGuildId ->
                    WidgetUserSheet.show(parentFragmentManager, userId, guildId = memberGuildId)
                }
            }
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Reached without a filter type there is nothing to show; the reference closes rather than
        // guessing one.
        if (filterType == FILTER_TYPE_UNSET) {
            activity?.finish()
            return
        }
        Model.get(requireContext(), guildId, filterType, filterPublisher)
            .computationLatest()
            .ui(this)
            .subscribe(
                { adapter?.configure(it.listItems, onFilterSelected) },
                { activity?.finish() },
            )
    }

    private data class Model(val listItems: List<MGRecyclerDataPayload>) {

        companion object {

            fun get(
                context: Context,
                guildId: Long,
                filterType: Int,
                filterPublisher: Observable<String>,
            ): Observable<Model> = Observable.combineLatest(
                StoreStream.getAuditLog().observeAuditLogState(guildId),
                StoreStream.getUsers().observeAllUsers(),
                StoreStream.getGuilds().observeComputed(guildId),
                StoreStream.getGuilds().observeRoles(guildId),
                StoreStream.getGuilds().observeGuild(guildId),
                filterPublisher
                    .leadingEdgeThrottle(SEARCH_THROTTLE_MS, TimeUnit.MILLISECONDS)
                    .distinctUntilChanged(),
            ) { auditLogState, users, guildMembers, roles, guild, filterText ->
                if (filterType == FILTER_TYPE_USER) {
                    Model(
                        userItems(
                            context, auditLogState, users, guildMembers, roles, guild, filterText,
                        ),
                    )
                } else {
                    Model(actionItems(context, auditLogState, filterText))
                }
            }.distinctUntilChanged()

            /**
             * The users worth filtering by, fuzzy-matched on username, selected one first.
             *
             * Not every user the client has ever cached: only the ones that could plausibly appear
             * in this guild's log — whoever already acted in the entries loaded so far, plus the
             * owner and anyone holding a role with a management permission. Anything wider offers
             * to filter by people who can never have produced an entry here.
             *
             * The "All users" row is only offered while the search box is empty: it is not a search
             * result, and leaving it pinned to the top of a filtered list would be misleading.
             */
            private fun userItems(
                context: Context,
                auditLogState: AuditLogState,
                users: Map<Long, User>,
                guildMembers: Map<Long, GuildMember>,
                roles: Map<Long, GuildRole>,
                guild: Guild?,
                filterText: String,
            ): List<WidgetServerSettingsAuditLogFilterAdapter.AuditLogUserFilterItem> {
                val allUsersRow = if (filterText.isBlank()) {
                    listOf(
                        WidgetServerSettingsAuditLogFilterAdapter.AuditLogUserFilterItem(
                            user = null,
                            guildMember = null,
                            id = NO_USER_FILTER,
                            text = context.getString(R.string.guild_settings_filter_all_users),
                            discriminator = null,
                            isChecked = auditLogState.filter.userFilter == NO_USER_FILTER,
                        ),
                    )
                } else {
                    emptyList()
                }

                val candidateIds = LinkedHashSet<Long>()
                auditLogState.entries.orEmpty().forEach { entry ->
                    if (entry.userId != NO_USER_FILTER) candidateIds.add(entry.userId)
                }
                guildMembers.values.forEach { member ->
                    if (member.userId == guild?.ownerId || member.canManageGuild(roles)) {
                        candidateIds.add(member.userId)
                    }
                }

                val matches = candidateIds
                    .mapNotNull { users[it] ?: auditLogState.users[it] }
                    .filter { SearchUtils.fuzzyMatch(filterText.lowercase(), it.username.lowercase()) }
                    .map { user ->
                        WidgetServerSettingsAuditLogFilterAdapter.AuditLogUserFilterItem(
                            user = user,
                            guildMember = guildMembers[user.id],
                            id = user.id,
                            text = null,
                            discriminator = user.discriminator,
                            isChecked = auditLogState.filter.userFilter == user.id,
                        )
                    }

                return (allUsersRow + matches).sortedBy { !it.isChecked }
            }

            private fun GuildMember.canManageGuild(roles: Map<Long, GuildRole>): Boolean =
                getRoles().any { roleId ->
                    val permissions = roles[roleId]?.permissions ?: return@any false
                    permissions and MANAGEMENT_PERMISSIONS != 0L
                }

            /** Every renderable action, fuzzy-matched on its localised name, selected one first. */
            private fun actionItems(
                context: Context,
                auditLogState: AuditLogState,
                filterText: String,
            ): List<WidgetServerSettingsAuditLogFilterAdapter.AuditLogActionFilterItem> =
                AuditLogUtils.ALL_ACTION_TYPES
                    .map { it to context.getString(AuditLogUtils.getActionName(it)) }
                    .filter { (_, name) ->
                        SearchUtils.fuzzyMatch(filterText.lowercase(), name.lowercase())
                    }
                    .map { (actionId, name) ->
                        WidgetServerSettingsAuditLogFilterAdapter.AuditLogActionFilterItem(
                            actionId = actionId,
                            text = name,
                            isChecked = auditLogState.filter.actionFilter == actionId,
                        )
                    }
                    .sortedBy { !it.isChecked }
        }
    }

    companion object {
        private const val ARG_FILTER_TYPE = "ARG_FILTER_TYPE"
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"

        private const val FILTER_TYPE_UNSET = -1
        private const val FILTER_TYPE_USER =
            WidgetServerSettingsAuditLogFilterAdapter.FILTER_TYPE_USER
        private const val NO_USER_FILTER =
            WidgetServerSettingsAuditLogFilterAdapter.NO_USER_FILTER
        private const val NO_GUILD = 0L
        private const val SEARCH_THROTTLE_MS = 100L

        /**
         * The permissions that can produce an audit-log entry.
         *
         * Deliberately not [com.discord.api.permission.Permission.MANAGEMENT_PERMISSIONS], which is
         * the "can see server settings" bundle: this one drops `MANAGE_EVENTS`/`MODERATE_MEMBERS`
         * and adds `MANAGE_MESSAGES`/`MOVE_MEMBERS`.
         */
        private val MANAGEMENT_PERMISSIONS = Permission.KICK_MEMBERS or
            Permission.BAN_MEMBERS or
            Permission.ADMINISTRATOR or
            Permission.MANAGE_CHANNELS or
            Permission.MANAGE_GUILD or
            Permission.MANAGE_MESSAGES or
            Permission.MANAGE_NICKNAMES or
            Permission.MANAGE_ROLES or
            Permission.MANAGE_WEBHOOKS or
            Permission.MANAGE_GUILD_EXPRESSIONS or
            Permission.MOVE_MEMBERS or
            Permission.MUTE_MEMBERS or
            Permission.DEAFEN_MEMBERS

        fun show(context: Context, guildId: Long, filterType: Int) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsAuditLogFilter::class.java,
                Intent()
                    .putExtra(ARG_GUILD_ID, guildId)
                    .putExtra(ARG_FILTER_TYPE, filterType),
            )
        }
    }
}
