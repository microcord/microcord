package com.discord.widgets.channels.permissions

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppBottomSheet
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetChannelSettingsAddMemberSheet : AppBottomSheet() {
    private val channelId get() = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID)
    private lateinit var fragment: WidgetChannelSettingsAddMemberFragment
    override fun getContentViewResId() = R.layout.widget_channel_settings_add_member_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragment = childFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? WidgetChannelSettingsAddMemberFragment
            ?: WidgetChannelSettingsAddMemberFragment.create(channelId, false).also {
                childFragmentManager.beginTransaction()
                    .replace(view.findViewById<FragmentContainerView>(R.id.content).id, it, FRAGMENT_TAG)
                    .commitNow()
            }
        view.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        view.findViewById<TextView>(R.id.add_button).setOnClickListener { addPermissionOverwrites() }
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getChannels().observeChannel(channelId).ui(this).subscribe { channel ->
            if (channel == null) dismiss() else view?.findViewById<TextView>(R.id.subtitle)?.text =
                channel.getDisplayNameOrDefault(requireContext(), false)
        }
        fragment.selectedItemsSubject.ui(this).subscribe { selected ->
            view?.findViewById<TextView>(R.id.add_button)?.apply {
                isEnabled = selected.isNotEmpty()
                alpha = if (isEnabled) 1f else 0.3f
            }
        }
    }

    private fun addPermissionOverwrites() {
        val api = RestAPI.apiOrNull ?: return
        val selected = fragment.selectedItemsSubject.value.orEmpty()
        Observable.from(selected.entries)
            .concatMap { (id, type) ->
                val body = if (type == PermissionOverwrite.Type.ROLE) {
                    RestAPIParams.ChannelPermissionOverwrites.createForRole(id, Permission.MODERATE_STAGE_CHANNEL, 0L)
                } else {
                    RestAPIParams.ChannelPermissionOverwrites.createForMember(id, Permission.MODERATE_STAGE_CHANNEL, 0L)
                }
                api.updatePermissionOverwrites(channelId, id, body)
            }
            .toList().restSubscribeOn().ui(this).subscribe({ dismiss() }, { })
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val FRAGMENT_TAG = "add_permission_owner"
        fun show(fragmentManager: FragmentManager, channelId: Long) = WidgetChannelSettingsAddMemberSheet().apply {
            arguments = Bundle().apply { putLong(EXTRA_CHANNEL_ID, channelId) }
        }.show(fragmentManager, WidgetChannelSettingsAddMemberSheet::class.java.name)
    }
}
