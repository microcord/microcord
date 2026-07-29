package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.fragment.app.FragmentContainerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetCreateChannelAddMember : AppFragment(R.layout.widget_create_channel_add_member) {
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private lateinit var fragment: WidgetChannelSettingsAddMemberFragment
    private var selected = emptyMap<Long, PermissionOverwrite.Type>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.channel_permissions_add_moderator_title)
        fragment = childFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? WidgetChannelSettingsAddMemberFragment
            ?: WidgetChannelSettingsAddMemberFragment.create(channelId, true).also {
                childFragmentManager.beginTransaction()
                    .replace(view.findViewById<FragmentContainerView>(R.id.content).id, it, FRAGMENT_TAG)
                    .commitNow()
            }
        appActivity?.toolbar?.menu?.apply {
            clear()
            add(Menu.NONE, DONE_ID, Menu.NONE, R.string.done).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        appActivity?.toolbar?.setOnMenuItemClickListener {
            if (it.itemId == DONE_ID) addPermissionOverwrites()
            it.itemId == DONE_ID
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getChannels().observeChannel(channelId).ui(this).subscribe { channel ->
            if (channel == null) requireActivity().onBackPressed() else setActionBarSubtitle(channel.name)
        }
        fragment.selectedItemsSubject.ui(this).subscribe { selected = it }
    }

    private fun addPermissionOverwrites() {
        if (selected.isEmpty()) {
            requireActivity().onBackPressed()
            return
        }
        val api = RestAPI.apiOrNull ?: return
        Observable.from(selected.entries).concatMap { (id, type) ->
            val body = if (type == PermissionOverwrite.Type.ROLE) {
                RestAPIParams.ChannelPermissionOverwrites.createForRole(id, Permission.MODERATE_STAGE_CHANNEL, 0L)
            } else RestAPIParams.ChannelPermissionOverwrites.createForMember(id, Permission.MODERATE_STAGE_CHANNEL, 0L)
            api.updatePermissionOverwrites(channelId, id, body)
        }.toList().restSubscribeOn().ui(this).subscribe({ requireActivity().onBackPressed() }, { })
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val FRAGMENT_TAG = "create_channel_add_member"
        private const val DONE_ID = 0x43414d
        fun create(context: Context, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetCreateChannelAddMember::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
