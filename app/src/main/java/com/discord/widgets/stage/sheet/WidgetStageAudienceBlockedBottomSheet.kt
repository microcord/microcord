package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.widgets.stage.StageChannelJoinHelper

/**
 * Last chance to back out before joining a stage that contains accounts the user has blocked.
 *
 * Accepting joins with `warnedAboutBlockedUsers = true` so [StageChannelJoinHelper] does not
 * immediately show this sheet again.
 */
class WidgetStageAudienceBlockedBottomSheet : AppBottomSheet() {

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }
    private val launchStageFullScreen: Boolean by lazy {
        argumentsOrDefault.getBoolean(EXTRA_LAUNCH_FULL_SCREEN)
    }

    private val viewModel: StageAudienceBlockedBottomSheetViewModel by appViewModels {
        StageAudienceBlockedBottomSheetViewModel(channelId)
    }

    private lateinit var adapter: StageAudienceBlockedBottomSheetAdapter
    private lateinit var acceptButton: View
    private lateinit var cancelButton: View

    override fun getContentViewResId() = R.layout.widget_stage_audience_blocked_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setPeekHeightBottomView(view)
        val recycler = view.findViewById<MaxHeightRecyclerView>(R.id.blocked_users)
        adapter = StageAudienceBlockedBottomSheetAdapter(recycler)
        acceptButton = view.findViewById(R.id.accept_button)
        cancelButton = view.findViewById(R.id.cancel_button)
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetStageAudienceBlockedBottomSheet::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: StageAudienceBlockedBottomSheetViewModel.ViewState) {
        when (viewState) {
            is StageAudienceBlockedBottomSheetViewModel.ViewState.Invalid -> dismiss()
            is StageAudienceBlockedBottomSheetViewModel.ViewState.Loaded -> {
                acceptButton.setOnClickListener {
                    StageChannelJoinHelper.connectToStage(
                        context = requireContext(),
                        fragmentManager = parentFragmentManager,
                        channelId = viewState.channel.id,
                        warnedAboutBlockedUsers = true,
                        launchFullscreen = launchStageFullScreen,
                        onCompleted = { dismiss() },
                    )
                }
                cancelButton.setOnClickListener { dismiss() }
                adapter.setData(viewState.listItems)
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_LAUNCH_FULL_SCREEN = "LAUNCH_FULL_SCREEN"

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            launchStageFullScreen: Boolean,
        ) {
            WidgetStageAudienceBlockedBottomSheet().apply {
                arguments = bundleOf(
                    EXTRA_CHANNEL_ID to channelId,
                    EXTRA_LAUNCH_FULL_SCREEN to launchStageFullScreen,
                )
            }.show(fragmentManager, WidgetStageAudienceBlockedBottomSheet::class.java.simpleName)
        }
    }
}
