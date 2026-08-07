package com.discord.widgets.channels.settings

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.producerViewModelFactory
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

class WidgetThreadSettings : AppFragment(R.layout.widget_thread_settings) {
    private val channelId: Long
        get() = arguments?.getLong(EXTRA_CHANNEL_ID)
            ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val viewModel by viewModels<WidgetThreadSettingsViewModel> {
        producerViewModelFactory { WidgetThreadSettingsViewModel(channelId) }
    }

    private lateinit var name: TextInputLayout
    private lateinit var slowMode: SeekBar
    private lateinit var slowModeLabel: TextView
    private lateinit var invitable: CheckedSetting
    private lateinit var spoiler: CheckedSetting
    private lateinit var save: FloatingActionButton
    private var bindingState = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        name = view.findViewById(R.id.thread_settings_edit_name)
        slowMode = view.findViewById(R.id.thread_settings_slow_mode_cooldown_slider)
        slowModeLabel = view.findViewById(R.id.thread_settings_slow_mode_cooldown_label)
        invitable = view.findViewById(R.id.thread_settings_invitable)
        spoiler = view.findViewById(R.id.thread_settings_spoiler)
        save = view.findViewById(R.id.thread_settings_save)
        parentFragmentManager.setFragmentResultListener(ThreadDeleteDialog.REQUEST_KEY, this) { _, result ->
            viewModel.onThreadDeleted(result.getLong(ThreadDeleteDialog.RESULT_CHANNEL_ID))
        }

        name.addBindedTextWatcher(this) {
            if (!bindingState) viewModel.onChannelNameInputChanged(it.toString())
        }
        slowMode.max = WidgetTextChannelSettings.SLOWMODE_COOLDOWN_VALUES.lastIndex
        slowMode.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!bindingState && fromUser) {
                    val seconds = WidgetTextChannelSettings.SLOWMODE_COOLDOWN_VALUES[progress]
                    slowModeLabel.text = WidgetTextChannelSettings.durationLabel(requireContext(), seconds)
                    viewModel.onSlowModeInputChanged(seconds)
                    seekBar.contentDescription = slowModeLabel.text
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        invitable.setOnCheckedListener { if (!bindingState) viewModel.onInvitableChanged(it) }
        spoiler.setOnCheckedListener { if (!bindingState) viewModel.onSpoilerChanged(it) }
        save.setOnClickListener { viewModel.saveThread() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(WidgetThreadSettings::class.java, onNext = ::render)
        viewModel.observeEvents().ui(this)
            .appSubscribe(WidgetThreadSettings::class.java, onNext = ::handleEvent)
    }

    private fun render(state: WidgetThreadSettingsViewModel.ViewState) {
        if (state !is WidgetThreadSettingsViewModel.ViewState.Valid) {
            activity?.finish()
            return
        }
        setActionBarDisplayHomeAsUpEnabled()
        // A thread in a forum is a post everywhere else in the product; the settings screen follows.
        val isForumPost = StoreStream.getChannels()
            .getChannel(state.channel.parentId)?.isThreadOnlyChannel() == true
        setActionBarTitle(if (isForumPost) R.string.forum_post_settings else R.string.thread_settings)
        setActionBarSubtitle(state.channel.getDisplayNameOrDefault(requireContext(), includePrefix = false))
        setActionBarOptionsMenu(
            R.menu.menu_category_settings,
            onSelected = { if (it.itemId == R.id.menu_channel_settings_delete) confirmDelete(state.channel) },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_channel_settings_delete).apply {
                    isVisible = state.canManageThread
                    setTitle(if (isForumPost) R.string.delete_forum_post else R.string.delete_thread)
                }
                menu.findItem(R.id.menu_channel_settings_reset).isVisible = false
            },
        )

        requireView().findViewById<LinearLayout>(R.id.thread_settings_edit_wrap).isVisible = state.canManageThread
        requireView().findViewById<LinearLayout>(R.id.thread_settings_section_slow_mode).isVisible = state.canManageThread
        requireView().findViewById<LinearLayout>(R.id.thread_settings_section_invitable).isVisible =
            state.isPrivateThread
        invitable.isEnabled = state.canManageThread
        spoiler.isEnabled = state.canManageThread
        bindingState = true
        if (name.getTextOrEmpty() != state.channelNameDraft.orEmpty()) name.setText(state.channelNameDraft.orEmpty())
        invitable.isChecked = state.invitableDraft
        spoiler.isChecked = state.spoilerDraft
        val progress = WidgetTextChannelSettings.slowModeProgress(state.slowModeCooldownDraft)
        slowMode.progress = progress
        slowModeLabel.text = WidgetTextChannelSettings.durationLabel(requireContext(), state.slowModeCooldownDraft)
        slowMode.contentDescription = slowModeLabel.text
        bindingState = false

        val pins = requireView().findViewById<TextView>(R.id.channel_settings_pinned_messages)
        val disabledOverlay = requireView().findViewById<View>(R.id.channel_settings_pinned_messages_disabled_overlay)
        pins.isEnabled = state.isPinsEnabled
        pins.setOnClickListener {
            WidgetChannelPinnedMessages.show(requireContext(), state.channel.id)
        }
        disabledOverlay.isVisible = !state.isPinsEnabled
        disabledOverlay.setOnClickListener {
            AppToast.show(this, R.string.pins_disabled_nsfw)
        }
        save.isVisible = state.hasUnsavedChanges
    }

    private fun confirmDelete(channel: Channel) {
        ThreadDeleteDialog.show(
            parentFragmentManager,
            channel.id,
            channel.getDisplayNameOrDefault(requireContext()),
        )
    }

    private fun handleEvent(event: WidgetThreadSettingsViewModel.Event) {
        when (event) {
            is WidgetThreadSettingsViewModel.Event.ShowToast -> AppToast.show(this, event.messageStringRes)
            WidgetThreadSettingsViewModel.Event.Close -> requireActivity().finish()
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        fun launch(channelId: Long, context: Context) = SettingsNavigation.launch(
            context,
            WidgetThreadSettings::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
