package com.discord.widgets.channels.permissions

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.SimpleMembersAdapter
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetChannelSettingsPermissionsAdvanced : AppFragment(R.layout.widget_channel_settings_permissions_advanced) {
    private lateinit var membersAdapter: SimpleMembersAdapter
    private val rolesAdapter = RolesAdapter()
    private val channelId get() = arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: parentFragment?.arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID, -1L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<RecyclerView>(R.id.roles_recycler).apply {
            isNestedScrollingEnabled = false
            adapter = rolesAdapter
        }
        view.findViewById<RecyclerView>(R.id.members_recycler).apply {
            isNestedScrollingEnabled = false
            membersAdapter = SimpleMembersAdapter(this)
            adapter = membersAdapter
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val channels = StoreStream.getChannels()
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            channels.observeChannel(channelId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
        ) { channel, me, permissions ->
            val guild = channel?.let { guilds.getGuild(it.guildId) }
            if (channel == null || guild == null || !PermissionEditor.canManage(guild, me, permissions)) null
            else Model(
                channel.guildId,
                channel.id,
                channel.permissionOverwrites.orEmpty()
                    .filter { it.type == PermissionOverwrite.Type.ROLE }
                    .mapNotNull { guilds.getRoles()[channel.guildId].orEmpty()[it.id] },
                channel.permissionOverwrites.orEmpty()
                    .filter { it.type == PermissionOverwrite.Type.MEMBER }
                    .mapNotNull { StoreStream.getUsers().getUsers()[it.id] }
                    .sortedBy { it.username.lowercase() },
            )
        }.ui(this).subscribe(::render)
    }

    private fun render(model: Model?) {
        val root = view ?: return
        if (model == null) return
        root.findViewById<View>(R.id.add_role).setOnClickListener {
            WidgetChannelSettingsPermissionsAddRole.launch(requireContext(), model.channelId)
        }
        root.findViewById<View>(R.id.add_member).setOnClickListener {
            WidgetChannelSettingsPermissionsAddMember.launch(requireContext(), model.guildId, model.channelId)
        }
        rolesAdapter.submit(model.roles) { role ->
            WidgetChannelSettingsEditPermissions.createForRole(requireContext(), model.guildId, model.channelId, role.id)
        }
        val members = model.members.map { user -> SimpleMembersAdapter.MemberItem(user, StoreStream.getGuilds().getMember(model.guildId, user.id)) }
        membersAdapter.setData(members) { user ->
            WidgetChannelSettingsEditPermissions.createForUser(requireContext(), model.guildId, model.channelId, user.id)
        }
        root.findViewById<LinearLayout>(R.id.roles_container).visibility = if (model.roles.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<LinearLayout>(R.id.members_container).visibility = if (members.isEmpty()) View.GONE else View.VISIBLE
    }

    internal data class Model(val guildId: Long, val channelId: Long, val roles: List<GuildRole>, val members: List<User>)

    private class RolesAdapter : RecyclerView.Adapter<RolesAdapter.Holder>() {
        private var items = emptyList<GuildRole>()
        private var click: (GuildRole) -> Unit = {}
        fun submit(items: List<GuildRole>, click: (GuildRole) -> Unit) {
            this.items = items.sortedWith(RoleUtils.ROLE_COMPARATOR)
            this.click = click
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.simple_role_list_item, parent, false),
        )
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], click)
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(role: GuildRole, click: (GuildRole) -> Unit) {
                (itemView as? TextView ?: itemView.findViewById(android.R.id.text1))?.apply {
                    text = role.name
                    setTextColor(RoleUtils.getRoleColor(role, context))
                }
                itemView.setOnClickListener { click(role) }
            }
        }
    }
}
