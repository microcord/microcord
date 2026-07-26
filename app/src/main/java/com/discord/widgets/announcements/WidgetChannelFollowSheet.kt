package com.discord.widgets.announcements

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppBottomSheet
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.ui
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rx.subscriptions.CompositeSubscription

/**
 * "Follow" an announcement channel into one of the viewer's own servers.
 *
 * Two pickers — destination server, then destination channel — and a Follow button that stays
 * disabled until both are chosen. When the viewer has `MANAGE_WEBHOOKS` nowhere the sheet flips to
 * a single explanatory panel instead.
 */
class WidgetChannelFollowSheet : AppBottomSheet() {

    private val viewModel: WidgetChannelFollowSheetViewModel by appViewModels {
        WidgetChannelFollowSheetViewModel(
            sourceGuildId = argumentsOrDefault.getLong(EXTRA_GUILD_ID),
            sourceChannelId = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID),
        )
    }

    private lateinit var guildIcon: SimpleDraweeView
    private lateinit var channelName: TextView
    private lateinit var flipper: AppViewFlipper
    private lateinit var selectGuild: MaterialCardView
    private lateinit var selectedGuildName: TextView
    private lateinit var selectChannel: MaterialCardView
    private lateinit var selectedChannelName: TextView
    private lateinit var errorText: TextView
    private lateinit var followButton: MaterialButton

    override fun getContentViewResId(): Int = R.layout.widget_channel_follow_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guildIcon = view.findViewById(R.id.channel_follow_guild_icon)
        channelName = view.findViewById(R.id.channel_follow_channel_name)
        flipper = view.findViewById(R.id.follow_sheet_view_flipper)
        selectGuild = view.findViewById(R.id.channel_follow_select_guild)
        selectedGuildName = view.findViewById(R.id.channel_follow_selected_guild_name)
        selectChannel = view.findViewById(R.id.channel_follow_select_channel)
        selectedChannelName = view.findViewById(R.id.channel_follow_selected_channel_name)
        errorText = view.findViewById(R.id.channel_follow_error_text)
        followButton = view.findViewById(R.id.create_channel_follower_button)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { viewState ->
                if (viewState is WidgetChannelFollowSheetViewModel.ViewState.Loaded) configureUI(viewState)
            }
        viewModel.observeEvents()
            .ui(this)
            .subscribe {
                dismiss()
                WidgetChannelFollowSuccessDialog.show(parentFragmentManager)
            }
    }

    private fun configureUI(viewState: WidgetChannelFollowSheetViewModel.ViewState.Loaded) {
        MGImages.setImage(guildIcon, IconUtils.getForGuild(viewState.sourceGuild))
        viewState.sourceChannel?.let { channelName.text = it.getDisplayNameOrDefault(requireContext(), includePrefix = false) }

        if (viewState.availableGuilds.isEmpty()) {
            flipper.displayedChild = VIEW_INDEX_NO_AVAILABLE_GUILDS
        } else {
            flipper.displayedChild = VIEW_INDEX_FOLLOW
            configureGuildSelector(viewState)
            configureChannelSelector(viewState)
        }

        errorText.visibility = if (viewState.errorTextRes != null) View.VISIBLE else View.GONE
        viewState.errorTextRes?.let(errorText::setText)
    }

    private fun configureGuildSelector(viewState: WidgetChannelFollowSheetViewModel.ViewState.Loaded) {
        selectGuild.setOnClickListener { showGuildPicker(viewState.availableGuilds) }
        val selected = viewState.selectedGuild
        selectedGuildName.text = selected?.name ?: getString(R.string.select)
        selectedGuildName.setTextColor(
            ColorCompat.getThemedColor(
                selectedGuildName,
                if (selected != null) R.attr.colorInteractiveActive else R.attr.colorInteractiveNormal,
            ),
        )
    }

    private fun configureChannelSelector(viewState: WidgetChannelFollowSheetViewModel.ViewState.Loaded) {
        val selectedGuild = viewState.selectedGuild
        if (selectedGuild != null) {
            selectChannel.setOnClickListener {
                showChannelPicker(selectedGuild.id, viewState.availableChannels)
            }
        }

        val selectedChannel = viewState.selectedChannel
        if (selectedChannel != null) {
            selectedChannelName.text = selectedChannel.getDisplayNameOrDefault(requireContext())
            followButton.isEnabled = true
            followButton.setOnClickListener { viewModel.followChannel(selectedChannel.id) }
        } else {
            selectedChannelName.text = getString(R.string.select)
            followButton.isEnabled = false
        }

        selectedChannelName.setTextColor(
            ColorCompat.getThemedColor(
                selectedChannelName,
                if (selectedChannel != null) R.attr.colorInteractiveActive else R.attr.colorInteractiveNormal,
            ),
        )
    }

    /**
     * The reference opens `WidgetGuildSelector` / `WidgetChannelSelector` — full searchable
     * pickers that take a `FilterFunction` and answer through the fragment-result API. Neither
     * screen exists in this project, so both pickers are single-choice dialogs over the very same
     * id sets the view model already computed. **Swap them back when those screens land**; the
     * filter predicates are exactly `availableGuilds` and `availableChannels ∩ guildId`.
     */
    private fun showGuildPicker(availableGuilds: Set<Long>) {
        val guilds = StoreStream.getGuilds().getGuilds()
        val options = availableGuilds.mapNotNull { guilds[it] }.sortedBy { it.name }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.form_label_send_to)
            .setItems(options.map { it.name }.toTypedArray()) { _, index ->
                viewModel.selectGuild(options[index].id)
                // Clearing the channel is what the reference's result callback does; a channel from
                // the previous guild must not survive the switch.
                viewModel.selectChannel(0L)
            }
            .show()
    }

    private fun showChannelPicker(selectedGuildId: Long, availableChannels: Set<Long>) {
        val channels = StoreStream.getChannels().getAllChannels()
        val options = availableChannels
            .mapNotNull(channels::get)
            .filter { it.guildId == selectedGuildId }
            .sortedBy { it.getDisplayNameOrDefault(requireContext()) }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.form_label_select_channel)
            .setItems(options.map { it.getDisplayNameOrDefault(requireContext()) }.toTypedArray()) { _, index ->
                viewModel.selectChannel(options[index].id)
            }
            .show()
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val VIEW_INDEX_FOLLOW = 0
        private const val VIEW_INDEX_NO_AVAILABLE_GUILDS = 1

        fun show(fragmentManager: FragmentManager, channelId: Long, guildId: Long) {
            WidgetChannelFollowSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_CHANNEL_ID, channelId)
                    putLong(EXTRA_GUILD_ID, guildId)
                }
            }.show(fragmentManager, WidgetChannelFollowSheet::class.java.name)
        }
    }
}

/** Reference `WidgetChannelSelector.FilterFunction` for this flow, kept so the predicate is named. */
data class ChannelFollowChannelFilterFunction(
    private val selectedGuildId: Long,
    private val channelIds: Set<Long>,
) {
    fun includeChannel(channel: Channel): Boolean =
        channel.id in channelIds && channel.guildId == selectedGuildId
}
