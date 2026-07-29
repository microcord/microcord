package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetChannelSettingsPermissionsAddRole : AppFragment(R.layout.widget_channel_settings_permissions_add_role) {
    private val adapter = Adapter()
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<RecyclerView>(R.id.channel_settings_permissions_add_role_recycler).adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
        ) { channel, me, permissions ->
            val guild = channel?.let { guilds.getGuild(it.guildId) }
            if (channel == null || guild == null || !PermissionEditor.canManage(guild, me, permissions)) null
            else channel to PermissionEditor.roleItems(channel.permissionOverwrites, guilds.getRoles()[channel.guildId].orEmpty().values)
        }.ui(this).subscribe { model ->
            if (model == null || model.second.isEmpty()) {
                requireActivity().onBackPressed()
            } else {
                setActionBarTitle(R.string.add_a_role)
                setActionBarSubtitle(model.first.getDisplayNameOrDefault(requireContext()))
                adapter.submit(model.second) { role ->
                    WidgetChannelSettingsEditPermissions.createForRole(requireContext(), model.first.guildId, model.first.id, role.id)
                }
            }
        }
    }

    private class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var roles = emptyList<GuildRole>()
        private var click: (GuildRole) -> Unit = {}
        fun submit(value: List<GuildRole>, click: (GuildRole) -> Unit) {
            roles = value.sortedWith(RoleUtils.ROLE_COMPARATOR)
            this.click = click
            notifyDataSetChanged()
        }
        override fun getItemCount() = roles.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.simple_role_list_item, parent, false) as TextView,
        )
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(roles[position], click)
        class Holder(private val text: TextView) : RecyclerView.ViewHolder(text) {
            fun bind(role: GuildRole, click: (GuildRole) -> Unit) {
                text.text = role.name
                text.setTextColor(RoleUtils.getRoleColor(role, text.context))
                text.setOnClickListener { click(role) }
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        fun launch(context: Context, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetChannelSettingsPermissionsAddRole::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
