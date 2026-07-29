package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.SimpleMembersAdapter
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.textfield.TextInputLayout
import rx.Observable
import rx.subjects.BehaviorSubject

class WidgetChannelSettingsPermissionsAddMember : AppFragment(R.layout.widget_channel_settings_permissions_add_member) {
    private lateinit var adapter: SimpleMembersAdapter
    private val query = BehaviorSubject.create("")
    private val guildId get() = arguments?.getLong(EXTRA_GUILD_ID) ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1L)
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        val recycler = view.findViewById<RecyclerView>(R.id.channel_settings_permissions_add_member_recycler)
        adapter = SimpleMembersAdapter(recycler)
        recycler.adapter = adapter
        view.findViewById<TextInputLayout>(R.id.channel_settings_permissions_add_member_name_search).editText
            ?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) { query.onNext(value?.toString().orEmpty()) }
            })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
            query,
        ) { channel, me, permissions, search ->
            val guild = channel?.let { guilds.getGuild(it.guildId) }
            if (channel == null || guild == null || !PermissionEditor.canManage(guild, me, permissions)) null
            else channel to PermissionEditor.memberItems(
                search,
                channel.permissionOverwrites,
                StoreStream.getUsers().getUsers().values,
                guilds.getMembers()[guildId].orEmpty(),
            )
        }.ui(this).subscribe { model ->
            if (model == null) {
                requireActivity().onBackPressed()
                return@subscribe
            }
            setActionBarTitle(R.string.add_a_member)
            setActionBarSubtitle(model.first.getDisplayNameOrDefault(requireContext()))
            adapter.setData(model.second) { user ->
                WidgetChannelSettingsEditPermissions.createForUser(requireContext(), guildId, channelId, user.id)
            }
            view?.findViewById<AppViewFlipper>(R.id.channel_settings_permissions_add_member_view_flipper)
                ?.displayedChild = if (model.second.isEmpty()) 1 else 0
        }
        query.debounce(750, java.util.concurrent.TimeUnit.MILLISECONDS).filter(String::isNotBlank).ui(this).subscribe {
            StoreStream.getGatewayConnection().requestGuildMembers(guildId, it)
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        fun launch(context: Context, guildId: Long, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetChannelSettingsPermissionsAddMember::class.java,
            Intent().putExtra(EXTRA_GUILD_ID, guildId).putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
