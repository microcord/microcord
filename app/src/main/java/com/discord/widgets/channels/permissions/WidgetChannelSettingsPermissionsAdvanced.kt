package com.discord.widgets.channels.permissions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.databinding.PermissionOwnerRowModernBinding
import com.discord.databinding.PermissionRoleRowModernBinding
import com.discord.databinding.WidgetChannelSettingsPermissionsAdvancedBinding
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.SimpleMembersAdapter
import com.discord.widgets.chat.list.actions.MessageMenuGroup
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsStrings
import rx.Observable

class WidgetChannelSettingsPermissionsAdvanced : AppFragment() {
    /** The modern layout shares every id with the legacy one, so one binding drives both. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() }
    private var _binding: WidgetChannelSettingsPermissionsAdvancedBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var membersAdapter: SimpleMembersAdapter
    private val rolesAdapter = RolesAdapter()
    private val channelId get() = arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: parentFragment?.arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID, -1L)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(
            if (modern) {
                R.layout.widget_channel_settings_permissions_advanced_modern
            } else {
                R.layout.widget_channel_settings_permissions_advanced
            },
            container,
            false,
        )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetChannelSettingsPermissionsAdvancedBinding.bind(view)
        binding.rolesRecycler.apply {
            isNestedScrollingEnabled = false
            adapter = rolesAdapter
        }
        binding.membersRecycler.apply {
            isNestedScrollingEnabled = false
            membersAdapter = SimpleMembersAdapter(this)
            adapter = membersAdapter
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
            if (channel == null || guild == null || !PermissionEditor.canManage(guild, me, permissions)) {
                null
            } else {
                val roles = guilds.getRoles()[channel.guildId].orEmpty()
                val overwriteRoles = channel.permissionOverwrites.orEmpty()
                    .filter { it.type == PermissionOverwrite.Type.ROLE }
                    .mapNotNullTo(LinkedHashSet()) { roles[it.id] }
                Model(
                    channel,
                    channel.parentId.takeIf { it != 0L }?.let(channels::getChannel),
                    // React Native always lists @everyone, overwrite or not.
                    (if (modern) overwriteRoles + listOfNotNull(roles[channel.guildId]) else overwriteRoles).toList(),
                    channel.permissionOverwrites.orEmpty()
                        .filter { it.type == PermissionOverwrite.Type.MEMBER }
                        .mapNotNull { StoreStream.getUsers().getUsers()[it.id] }
                        .sortedBy { it.username.lowercase() },
                )
            }
        }.ui(this).appSubscribe(javaClass) { it?.let(::render) }
    }

    private fun render(model: Model) {
        if (_binding == null) return
        val channel = model.channel
        binding.addRole.setOnClickListener {
            WidgetChannelSettingsPermissionsAddRole.launch(requireContext(), channel.id)
        }
        binding.addMember.setOnClickListener {
            WidgetChannelSettingsPermissionsAddMember.launch(requireContext(), channel.guildId, channel.id)
        }
        val roles = model.roles.sortedWith(RoleUtils.ROLE_COMPARATOR)
        val members = model.members.map { user ->
            SimpleMembersAdapter.MemberItem(user, StoreStream.getGuilds().getMember(channel.guildId, user.id))
        }
        binding.rolesContainer.isVisible = roles.isNotEmpty()
        binding.membersContainer.isVisible = members.isNotEmpty()
        if (modern) {
            renderSync(model)
            renderRoles(channel, roles)
            renderMembers(channel, members)
        } else {
            rolesAdapter.submit(roles) { role -> editRole(channel, role) }
            membersAdapter.setData(members) { user -> editMember(channel, user) }
        }
    }

    /** Shown whenever the channel sits in a category, saying whether it still matches it. */
    private fun renderSync(model: Model) {
        val category = model.category
        binding.syncContainer.isVisible = category != null
        if (category == null) return
        val synced = PermissionEditor.isSyncedWith(model.channel, category)
        binding.syncLabel.text = SettingsStrings.render(
            requireContext(),
            if (synced) R.string.channel_locked_to_category else R.string.permissions_unsynced,
            "categoryName" to category.getDisplayNameOrDefault(requireContext()),
        )
        binding.syncNow.setOnClickListener { confirmSync(model.channel, category) }
    }

    private fun renderRoles(channel: Channel, roles: List<GuildRole>) {
        val list = binding.rolesList
        list.removeAllViews()
        roles.forEach { role ->
            PermissionRoleRowModernBinding.inflate(LayoutInflater.from(list.context), list, true).root.apply {
                text = role.name
                setTextColor(RoleUtils.getRoleColor(role, context))
                setOnClickListener { editRole(channel, role) }
            }
        }
        (list as? MessageMenuGroup)?.refresh()
    }

    private fun renderMembers(channel: Channel, members: List<SimpleMembersAdapter.MemberItem>) {
        val list = binding.membersList
        list.removeAllViews()
        val ownerId = StoreStream.getGuilds().getGuild(channel.guildId)?.ownerId
        members.forEach { item ->
            PermissionOwnerRowModernBinding.inflate(LayoutInflater.from(list.context), list, true).apply {
                owner.configure(PermissionOwner.Member(item.user, item.guildMember?.nick, item.user.id == ownerId))
                action.setImageResource(R.drawable.ic_menu_chevron)
                root.setOnClickListener { editMember(channel, item.user) }
            }
        }
        (list as? MessageMenuGroup)?.refresh()
    }

    private fun confirmSync(channel: Channel, category: Channel) {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.sync_permissions),
            bodyText = SettingsStrings.render(
                requireContext(),
                R.string.sync_permissions_explanation,
                "channelName" to channel.getDisplayNameOrDefault(requireContext()),
                "categoryName" to category.getDisplayNameOrDefault(requireContext()),
            ),
            okText = getString(R.string.sync),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View -> sync(channel, category) }),
        )
    }

    /** Copies the category's overwrites onto the channel, which is what syncing means. */
    private fun sync(channel: Channel, category: Channel) {
        RestAPI.api.syncChannelPermissions(
            channel.id,
            RestAPIParams.ChannelPermissionSync(
                category.permissionOverwrites.orEmpty().map(RestAPIParams.ChannelPermissionOverwrites::fromPermissionOverwrite),
            ),
        ).restSubscribeOn().ui(this).appSubscribe(javaClass, context = context) { }
    }

    private fun editRole(channel: Channel, role: GuildRole) =
        WidgetChannelSettingsEditPermissions.createForRole(requireContext(), channel.guildId, channel.id, role.id)

    private fun editMember(channel: Channel, user: User) =
        WidgetChannelSettingsEditPermissions.createForUser(requireContext(), channel.guildId, channel.id, user.id)

    internal data class Model(
        val channel: Channel,
        val category: Channel?,
        val roles: List<GuildRole>,
        val members: List<User>,
    )

    private class RolesAdapter : RecyclerView.Adapter<RolesAdapter.Holder>() {
        private var items = emptyList<GuildRole>()
        private var click: (GuildRole) -> Unit = {}
        fun submit(items: List<GuildRole>, click: (GuildRole) -> Unit) {
            this.items = items
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
