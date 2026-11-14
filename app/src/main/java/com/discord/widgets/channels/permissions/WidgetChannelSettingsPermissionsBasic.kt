package com.discord.widgets.channels.permissions

import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.app.AppFragment
import com.discord.databinding.PermissionOwnerRowModernBinding
import com.discord.databinding.WidgetChannelSettingsPermissionsBasicBinding
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

/** The modern Basic permissions view: make a channel private and choose who can still access it. */
class WidgetChannelSettingsPermissionsBasic : AppFragment(R.layout.widget_channel_settings_permissions_basic) {
    private val channelId get() = arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: parentFragment?.arguments?.getLong(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(WidgetChannelSettingsPermissionsOverview.EXTRA_CHANNEL_ID, -1L)
    private var _binding: WidgetChannelSettingsPermissionsBasicBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var model: Model? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetChannelSettingsPermissionsBasicBinding.bind(view)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
            guilds.observeRoles(),
        ) { channel, users, me, permissions, roles ->
            val guild = channel?.let { guilds.getGuild(it.guildId) }
            if (channel == null || guild == null) {
                null
            } else {
                val canEdit = PermissionEditor.canManage(guild, me, permissions)
                Model(
                    channel,
                    BasicChannelPermissions.isPrivate(channel),
                    BasicChannelPermissions.accessItems(
                        channel,
                        roles[guild.id].orEmpty().values,
                        users,
                        guilds.getMembers()[guild.id].orEmpty(),
                        guild.ownerId,
                        canEdit,
                    ),
                    canEdit,
                )
            }
        }.ui(this).appSubscribe(javaClass) { it?.let(::render) }
    }

    private fun render(next: Model) {
        if (_binding == null) return
        model = next
        val channel = next.channel
        val access = BasicChannelPermissions.accessPermission(channel)
        binding.basicDescription.setText(
            if (channel.isGuildVocalChannel()) {
                R.string.channel_permissions_private_channel_description_voice
            } else {
                R.string.channel_permissions_private_channel_description
            },
        )
        binding.basicPrivate.apply {
            setOnCheckedListener(null)
            isChecked = next.isPrivate
            isEnabled = next.canEdit
            setOnCheckedListener(::setPrivate)
        }
        binding.basicPrivateSection.isVisible = next.isPrivate
        binding.basicAdd.apply {
            isEnabled = next.canEdit
            alpha = if (next.canEdit) 1f else 0.3f
            setOnClickListener {
                WidgetChannelSettingsAddMemberSheet.show(
                    parentFragmentManager,
                    channel.id,
                    access,
                    R.string.channel_permissions_add_members_title,
                )
            }
        }
        val list = binding.basicAccessList
        list.removeAllViews()
        next.items.forEach { item ->
            val row = PermissionOwnerRowModernBinding.inflate(LayoutInflater.from(list.context), list, true)
            row.owner.configure(item.permissionOwner)
            row.action.apply {
                setImageResource(R.drawable.ic_delete_circle)
                alpha = if (item.removeStatus is PermissionOwnerListView.RemoveStatus.CanRemove) 1f else 0.3f
                contentDescription = getString(R.string.remove)
                setOnClickListener {
                    when (val status = item.removeStatus) {
                        PermissionOwnerListView.RemoveStatus.CanRemove ->
                            ConfirmRemovePermissionDialog.show(item.permissionOwner, channel.id, parentFragmentManager, access)
                        PermissionOwnerListView.RemoveStatus.IsRemoving -> Unit
                        is PermissionOwnerListView.RemoveStatus.CannotRemove ->
                            PermissionOwnerListView.showCannotRemove(context, status.reason)
                    }
                }
            }
        }
        list.refresh()
    }

    /** Denies @everyone the channel, or lifts that denial; an overwrite left empty is deleted. */
    private fun setPrivate(private: Boolean) {
        val channel = model?.channel ?: return
        val api = RestAPI.apiOrNull ?: return
        val overwrite = BasicChannelPermissions.everyoneOverwriteFor(channel, private)
        val request = if (overwrite == null) {
            api.deletePermissionOverwrites(channel.id, channel.guildId)
        } else {
            api.updatePermissionOverwrites(
                channel.id,
                channel.guildId,
                RestAPIParams.ChannelPermissionOverwrites.fromPermissionOverwrite(overwrite),
            )
        }
        request.restSubscribeOn().ui(this).appSubscribe(
            javaClass,
            context = context,
            // A refused change leaves the channel as it was, so the switch goes back with it.
            errorHandler = { model?.let(::render) },
        ) { }
    }

    private data class Model(
        val channel: Channel,
        val isPrivate: Boolean,
        val items: List<PermissionOwnerListView.Item>,
        val canEdit: Boolean,
    )
}
