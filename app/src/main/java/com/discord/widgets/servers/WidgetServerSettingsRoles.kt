package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.DragAndDropHelper
import com.discord.utilities.mg_recycler.DragAndDropAdapter.Payload
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.views.SearchInputView
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable

class WidgetServerSettingsRoles : AppFragment(R.layout.widget_server_settings_roles) {
    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_ID)

    private var adapter: WidgetServerSettingsRolesAdapter? = null
    private var latestModel: Model? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private lateinit var addRole: FloatingActionButton
    private lateinit var dimmer: DimmerView
    private lateinit var search: SearchInputView
    private var query: String = ""

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        addRole = view.findViewById(R.id.roles_list_add_role_fab)
        dimmer = view.findViewById(R.id.dimmer_view)
        search = view.findViewById(R.id.server_settings_roles_search)
        search.bindTextWatcher(this) { typed ->
            query = typed
            latestModel?.let(::configureUI)
        }
        val recycler = view.findViewById<RecyclerView>(R.id.server_settings_roles_recycler)
        adapter = MGRecyclerAdapter.configure(WidgetServerSettingsRolesAdapter(recycler))
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = ItemTouchHelper(DragAndDropHelper(requireNotNull(adapter))).also {
            it.attachToRecyclerView(recycler)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getGuildRoleMemberCounts().fetchGuildRoleMemberCounts(guildId)
        Model.observe(guildId).computationLatest().ui(this).appSubscribe(javaClass) { configureUI(it) }
    }

    private fun configureUI(model: Model?) {
        if (model == null || model.roleItems.isEmpty()) {
            appActivity?.onBackPressed()
            return
        }
        latestModel = model
        setActionBarTitle(R.string.roles)
        setActionBarSubtitle(model.guildName)
        // Reordering by drag is meaningless over a filtered list, so searching only appears once
        // the list is long enough to be worth scanning.
        val searchable = model.roleItems.count { it is WidgetServerSettingsRolesAdapter.RoleItem } >=
            MIN_ROLES_FOR_SEARCH
        search.visibility = if (searchable) View.VISIBLE else View.GONE
        adapter?.configure(
            model.roleItems.filtered(),
            roleSelected = { WidgetServerSettingsEditRole.launch(model.guildId, it.id, requireContext()) },
            rolesMoved = { processRoleDrop(it, model) },
        )
        if (model.canManageRoles && model.elevated) {
            addRole.show()
            addRole.setOnClickListener { createRole(model) }
        } else {
            addRole.hide()
            addRole.setOnClickListener(null)
        }
    }

    /** The help row has nothing to match on, so a search drops it along with the misses. */
    private fun List<Payload>.filtered(): List<Payload> {
        if (query.isBlank()) return this
        return filterIsInstance<WidgetServerSettingsRolesAdapter.RoleItem>()
            .filter { it.role.name.contains(query, ignoreCase = true) }
    }

    private fun createRole(snapshot: Model) {
        var createdObserved = false
        RestAPI.api.createRole(snapshot.guildId)
            .restSubscribeOn(retry = false)
            .flatMap { created ->
                StoreStream.getGuilds().observeRoles(snapshot.guildId, listOf(created.id))
                    .map { it[created.id] }
                    .filter { it != null }
                    .map { it!! }
                    // The create already succeeded; store synchronization is not a network failure.
                    .onErrorResumeNext(Observable.empty())
            }
            .takeSingleUntilTimeout(emitError = false)
            .ui(this)
            .appSubscribe(
                javaClass,
                requireContext(),
                onCompleted = { if (!createdObserved) configureUI(snapshot) },
            ) { created ->
                createdObserved = true
                WidgetServerSettingsEditRole.launch(snapshot.guildId, created.id, requireContext())
            }
    }

    private fun processRoleDrop(newPositions: Map<String, Int>, snapshot: Model) {
        val updates = newPositions.map { (roleId, position) ->
            RestAPIParams.Role.createForPosition(roleId.toLong(), position)
        }
        dimmer.setDimmed(true)
        RestAPI.api.batchUpdateRole(snapshot.guildId, updates)
            .restSubscribeOn(retry = false)
            .ui(this)
            .appSubscribe(
                javaClass,
                requireContext(),
                errorHandler = {
                    dimmer.setDimmed(false)
                    configureUI(snapshot)
                },
                onCompleted = {
                    Toast.makeText(requireContext(), R.string.role_edit_saved, Toast.LENGTH_SHORT).show()
                },
                onTerminated = { dimmer.setDimmed(false) },
            ) { }
    }

    data class Model(
        val guildId: Long,
        val guildName: String?,
        val canManageRoles: Boolean,
        val elevated: Boolean,
        val roleItems: List<Payload>,
    ) {
        companion object {
            fun observe(guildId: Long): Observable<Model?> = StoreStream.getUsers().observeMe().flatMap { me ->
                Observable.combineLatest(
                    StoreStream.getGuilds().observeGuild(guildId),
                    StoreStream.getGuilds().observeComputed(guildId).map { it[me.id] },
                    StoreStream.getPermissions().observePermissionsForGuild(guildId),
                    StoreStream.getGuilds().observeRoles(guildId),
                    StoreStream.getGuildRoleMemberCounts().observeGuildRoleMemberCounts(guildId),
                ) { guild, member, permissions, roles, memberCounts ->
                    create(guildId, me, guild, member, permissions, roles, memberCounts)
                }
            }.distinctUntilChanged()

            internal fun create(
                guildId: Long,
                me: MeUser,
                guild: Guild?,
                member: GuildMember?,
                permissions: Long?,
                roles: Map<Long, GuildRole>,
                memberCounts: Map<Long, Int>? = null,
            ): Model? {
                if (guild == null || member == null || permissions == null) return null
                val canManage = PermissionUtils.can(Permission.MANAGE_ROLES, permissions)
                val elevated = PermissionUtils.isElevated(me.mfaEnabled, guild.mfaLevel)
                val highestRole = RoleUtils.getHighestRole(roles, member)
                val items = ArrayList<Payload>(roles.size + 1)
                items += WidgetServerSettingsRolesAdapter.HelpItem(guildId)
                roles.values.sortedWith(RoleUtils.ROLE_COMPARATOR).forEach { role ->
                    items += WidgetServerSettingsRolesAdapter.RoleItem(
                        role = role,
                        everyoneRole = role.id == guildId,
                        locked = guild.ownerId != me.id && !RoleUtils.rankIsHigher(highestRole, role),
                        userAbleToManageRoles = canManage,
                        elevated = elevated,
                        guildId = guildId,
                        memberCount = memberCounts?.get(role.id),
                    )
                }
                return Model(guildId, guild.name, canManage, elevated, items)
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_ID = -1L
        private const val MIN_ROLES_FOR_SEARCH = 10

        fun create(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("ROLES", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsRoles::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }

        fun launch(context: Context, guildId: Long) = create(context, guildId)
    }
}
