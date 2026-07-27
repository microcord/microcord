package com.discord.widgets.servers.settings.members

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.servers.WidgetServerSettingsEditMember
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.user.WidgetPruneUsers
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import rx.subjects.BehaviorSubject

/**
 * The guild's member list, filterable by name and by role, and the entry point to per-member
 * moderation.
 *
 * Two things are worth knowing:
 *
 * - **The list is only as complete as the client's member cache.** Typing in the search box asks the
 *   gateway for matching members, so a large guild fills in as the query narrows rather than all at
 *   once. That request is throttled separately from — and more slowly than — the local filter, so
 *   typing stays responsive without flooding the socket.
 * - **Tapping a member the acting user cannot manage is not silently ignored.** The row still
 *   responds, and says *why* it cannot be opened: the target owns the server, outranks the acting
 *   user, or holds the same top role. Silence here reads as a bug.
 */
class WidgetServerSettingsMembers : AppFragment(R.layout.widget_server_settings_members) {

    private val guildId: Long
        get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private var adapter: WidgetServerSettingsMembersAdapter? = null

    /** The roles the spinner was last built from, so it is not rebuilt on every emission. */
    private var guildRoles: Map<Long, GuildRole>? = null

    private val nameFilterPublisher: BehaviorSubject<String> = BehaviorSubject.create("")
    private val roleFilterPublisher: BehaviorSubject<Long> = BehaviorSubject.create()

    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var nameSearch: TextInputLayout
    private lateinit var rolesSpinner: Spinner

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        viewFlipper = view.findViewById(R.id.server_settings_members_view_flipper)
        nameSearch = view.findViewById(R.id.server_settings_members_name_search)
        rolesSpinner = view.findViewById(R.id.server_settings_members_roles_spinner)

        val recycler = view.findViewById<RecyclerView>(R.id.server_settings_members_recycler)
        adapter = MGRecyclerAdapter.configure(WidgetServerSettingsMembersAdapter(recycler))

        // The guild id doubles as @everyone's role id, so seeding the filter with it means
        // "no role filter".
        //
        // Divergence from the reference, which seeds only `if (!isRecreated)`. The subject is a
        // fragment field, so after a configuration change it is a *fresh* instance with no value —
        // and `combineLatest` will not emit until every source has one. The reference's guard
        // therefore leaves a rotated screen permanently blank, because the spinner that would
        // otherwise re-seed it is itself only built from the model. Seeding unconditionally is
        // harmless: the restored spinner fires `onItemSelected` for its saved position immediately
        // after its adapter is attached, which overwrites this value with the user's real choice.
        roleFilterPublisher.onNext(guildId)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()

        val editText = nameSearch.editText
        editText?.addBindedTextWatcher(this) { nameFilterPublisher.onNext(it.toString()) }
        nameFilterPublisher.onNext(editText?.text?.toString().orEmpty())

        // A separate, slower throttle than the local list filter: this one leaves the device.
        nameFilterPublisher
            .debounce(MEMBER_REQUEST_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .appSubscribe(WidgetServerSettingsMembers::class.java) { filter ->
                // A numeric query is also a candidate user id, and asking for it by id is the only
                // way to surface a member the client has never seen.
                val userIds = filter.toLongOrNull()?.let { listOf(it) }
                StoreStream.getGatewayConnection()
                    .requestGuildMembers(guildId, filter, userIds)
            }

        WidgetServerSettingsMembersModel
            .get(guildId, nameFilterPublisher, roleFilterPublisher)
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(model: WidgetServerSettingsMembersModel?) {
        if (model == null) {
            // The guild is gone, or the acting user is no longer a member of it.
            appActivity?.onBackPressed()
            return
        }

        configurePruneMenu(model)
        setActionBarTitle(R.string.member_list)
        setActionBarSubtitle(model.guild.name)

        viewFlipper.displayedChild = if (model.memberItems.isEmpty()) {
            VIEW_INDEX_NO_RESULTS
        } else {
            VIEW_INDEX_MEMBER_LIST
        }

        // Rebuilding the spinner resets its selection, so only do it when the roles really changed.
        if (guildRoles != model.roles) {
            guildRoles = model.roles
            setupRolesSpinner(model.guild.id, model.roles)
        }

        adapter?.configure(model) { userId, userRoles -> onMemberSelected(model, userId, userRoles) }
    }

    /**
     * Opens the member-edit screen, or explains why it cannot be opened.
     *
     * The three refusals are distinct on purpose: an owner can never be managed by anyone, an equal
     * rank is a hierarchy tie, and a higher rank is a hierarchy loss. The acting user's own row is
     * always editable — everyone may change their own nickname.
     */
    private fun onMemberSelected(
        model: WidgetServerSettingsMembersModel,
        userId: Long,
        userRoles: List<Long>,
    ) {
        val isSelf = userId == model.meUser.id
        if (model.guild.ownerId == userId && !isSelf) {
            AppToast.show(this, R.string.cannot_manage_is_owner)
            return
        }
        val targetHighestRole = RoleUtils.getHighestRole(model.roles, userRoles)
        when {
            isSelf ||
                model.guild.isOwner(model.meUser.id) ||
                RoleUtils.rankIsHigher(model.myHighestRole, targetHighestRole) ->
                WidgetServerSettingsEditMember.launch(model.guild.id, userId, requireContext())

            RoleUtils.rankEquals(model.myHighestRole, targetHighestRole) ->
                AppToast.show(this, R.string.cannot_manage_same_rank)

            else -> AppToast.show(this, R.string.cannot_manage_higher_rank)
        }
    }

    /**
     * Prune is a bulk kick and lives behind the toolbar overflow.
     *
     * The menu is replaced with an empty one rather than merely disabled when the acting user
     * cannot kick, so the option is not visible at all.
     */
    private fun configurePruneMenu(model: WidgetServerSettingsMembersModel) {
        setActionBarOptionsMenu(
            if (model.canKick) R.menu.menu_server_settings_members else R.menu.menu_empty,
            onSelected = { item ->
                if (item.itemId == R.id.menu_server_settings_members_prune) {
                    // `WidgetPruneUsers` owns the confirmation: it previews how many members would
                    // be removed before the action becomes available.
                    WidgetPruneUsers.create(model.guild.id, parentFragmentManager)
                }
            },
        )
    }

    /**
     * Builds the role filter spinner with `@everyone` pinned to the top.
     *
     * `@everyone` is the "no filter" entry, so it must be first and selected by default; the rest
     * keep the hierarchy order the model sorted them into.
     */
    private fun setupRolesSpinner(everyoneRoleId: Long, guildRoles: Map<Long, GuildRole>) {
        val ordered = ArrayList<GuildRole>(guildRoles.size)
        guildRoles.values.forEach { role ->
            if (role.id == everyoneRoleId) ordered.add(0, role) else ordered.add(role)
        }
        val spinnerAdapter = RolesSpinnerAdapter(requireContext(), ordered)
        rolesSpinner.adapter = spinnerAdapter
        rolesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                roleFilterPublisher.onNext(spinnerAdapter.getItem(position).id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Renders each role in its own colour, both collapsed and in the dropdown. */
    class RolesSpinnerAdapter(
        context: Context,
        private val roles: ArrayList<GuildRole>,
    ) : ArrayAdapter<GuildRole>(context, R.layout.server_settings_members_role_spinner_item, roles) {

        override fun getCount(): Int = roles.size

        override fun getItem(position: Int): GuildRole = roles[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(R.layout.server_settings_members_role_spinner_item, position, convertView, parent)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(
                R.layout.server_settings_members_role_spinner_item_open,
                position,
                convertView,
                parent,
            )

        private fun bind(layout: Int, position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(layout, null, false)
            val label = view.findViewById<TextView>(R.id.role_spinner_item_textview)
            val role = getItem(position)
            label.setTextColor(RoleUtils.getRoleColor(role, context))
            label.text = role.name
            return view
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        private const val VIEW_INDEX_MEMBER_LIST = 0
        private const val VIEW_INDEX_NO_RESULTS = 1

        private const val MEMBER_REQUEST_DEBOUNCE_MS = 750L

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsMembers::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
