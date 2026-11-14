package com.discord.widgets.channels.permissions

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetChannelSettingsAddMemberSheetBinding
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetChannelSettingsAddMemberSheet : AppBottomSheet() {
    private val channelId get() = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID)
    private val permission get() = argumentsOrDefault.getLong(EXTRA_PERMISSION, Permission.MODERATE_STAGE_CHANNEL)
    private lateinit var fragment: WidgetChannelSettingsAddMemberFragment

    /** The modern layout shares every id with the legacy one, so one binding drives both. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() }
    private var _binding: WidgetChannelSettingsAddMemberSheetBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun getContentViewResId() =
        if (modern) R.layout.widget_channel_settings_add_member_sheet_modern else R.layout.widget_channel_settings_add_member_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetChannelSettingsAddMemberSheetBinding.bind(view)
        fragment = childFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? WidgetChannelSettingsAddMemberFragment
            ?: WidgetChannelSettingsAddMemberFragment.create(channelId, false, permission).also {
                childFragmentManager.beginTransaction()
                    .replace(binding.content.id, it, FRAGMENT_TAG)
                    .commitNow()
            }
        // The base sheet inflates without a parent, so the view has no params of its own yet; without
        // explicit ones the sheet wraps its content and the member list gets no height at all.
        // The sheet's container measures with margins, so these must be margin params.
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.9f).toInt(),
        )
        setBottomSheetCollapsedStateDisabled()
        argumentsOrDefault.getInt(EXTRA_TITLE).takeIf { it != 0 }?.let(binding.title::setText)
        binding.addButton.setOnClickListener { addPermissionOverwrites() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getChannels().observeChannel(channelId).ui(this).subscribe { channel ->
            // React Native shows the channel with its # under the title.
            if (channel == null) dismiss() else _binding?.subtitle?.text =
                channel.getDisplayNameOrDefault(requireContext(), modern)
        }
        fragment.selectedItemsSubject.ui(this).subscribe { selected ->
            _binding?.addButton?.apply {
                isEnabled = selected.isNotEmpty()
                alpha = if (isEnabled) 1f else 0.3f
            }
        }
    }

    private fun addPermissionOverwrites() {
        val api = RestAPI.apiOrNull ?: return
        val selected = fragment.selectedItemsSubject.value.orEmpty()
        val existing = StoreStream.getChannels().getChannel(channelId)?.permissionOverwrites.orEmpty()
            .associateBy(PermissionOverwrite::id)
        Observable.from(selected.entries)
            .concatMap { (id, type) ->
                // The write replaces the whole overwrite, so whatever it already allows or denies is kept.
                val allow = (existing[id]?.allow ?: 0L) or permission
                val deny = (existing[id]?.deny ?: 0L) and permission.inv()
                val body = if (type == PermissionOverwrite.Type.ROLE) {
                    RestAPIParams.ChannelPermissionOverwrites.createForRole(id, allow, deny)
                } else {
                    RestAPIParams.ChannelPermissionOverwrites.createForMember(id, allow, deny)
                }
                api.updatePermissionOverwrites(channelId, id, body)
            }
            .toList().restSubscribeOn().ui(this).subscribe({ dismiss() }, { })
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_PERMISSION = "EXTRA_PERMISSION"
        private const val EXTRA_TITLE = "EXTRA_TITLE"
        private const val FRAGMENT_TAG = "add_permission_owner"
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            permission: Long = Permission.MODERATE_STAGE_CHANNEL,
            @StringRes title: Int = 0,
        ) = WidgetChannelSettingsAddMemberSheet().apply {
            arguments = Bundle().apply {
                putLong(EXTRA_CHANNEL_ID, channelId)
                putLong(EXTRA_PERMISSION, permission)
                putInt(EXTRA_TITLE, title)
            }
        }.show(fragmentManager, WidgetChannelSettingsAddMemberSheet::class.java.name)
    }
}
