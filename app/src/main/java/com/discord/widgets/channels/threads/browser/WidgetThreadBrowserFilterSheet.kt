package com.discord.widgets.channels.threads.browser

import android.os.Bundle
import android.view.View
import androidx.cardview.widget.CardView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.views.CheckedSetting
import rx.Subscription

class WidgetThreadBrowserFilterSheet : AppBottomSheet() {
    private val guildId get() = arguments?.getLong(EXTRA_GUILD_ID) ?: 0
    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: 0
    private lateinit var viewModel: WidgetThreadBrowserArchivedViewModel
    private lateinit var publicThreads: CheckedSetting
    private lateinit var privateThreads: CheckedSetting
    private lateinit var moderator: CheckedSetting
    private lateinit var moderatorCard: CardView
    private var subscription: Subscription? = null

    override fun getContentViewResId() = R.layout.widget_thread_browser_filter_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity().viewModelStore, WidgetThreadBrowserArchived.factory {
            WidgetThreadBrowserArchivedViewModel(guildId, channelId)
        })[WidgetThreadBrowserArchived.key(guildId, channelId), WidgetThreadBrowserArchivedViewModel::class.java]
        publicThreads = view.findViewById(R.id.thread_browser_visibility_public)
        privateThreads = view.findViewById(R.id.thread_browser_visibility_private)
        moderator = view.findViewById(R.id.thread_browser_moderator_view)
        moderatorCard = view.findViewById(R.id.thread_browser_moderator_view_card)
        publicThreads.setContainerClickListener {
            viewModel.onVisibilityChanged(WidgetThreadBrowserArchivedViewModel.VisibilityMode.PublicThreads)
        }
        privateThreads.setContainerClickListener {
            viewModel.onVisibilityChanged(WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads)
        }
        moderator.setContainerClickListener {
            moderator.toggle()
            viewModel.onModeratorModeChanged(moderator.isChecked())
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = viewModel.observeViewState().subscribe(::render)
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private fun render(state: WidgetThreadBrowserArchivedViewModel.ViewState) {
        publicThreads.setChecked(
            state.viewMode.visibility == WidgetThreadBrowserArchivedViewModel.VisibilityMode.PublicThreads,
            false,
        )
        privateThreads.setChecked(
            state.viewMode.visibility == WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
            false,
        )
        moderatorCard.visibility = if (state.isModerator) View.VISIBLE else View.GONE
        moderator.isEnabled = state.viewMode.visibility == WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads
        moderator.setChecked(state.viewMode.isModeratorMode, false)
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        fun show(fragmentManager: FragmentManager, guildId: Long, channelId: Long) {
            WidgetThreadBrowserFilterSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_CHANNEL_ID, channelId)
                }
            }.show(fragmentManager, WidgetThreadBrowserFilterSheet::class.java.name)
        }
    }
}
