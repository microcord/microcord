package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setProgrammaticChecked
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView

/**
 * The moderator's view of a stage's raised-hands queue.
 *
 * Three things live here: the master "anyone may request to speak" switch, the moderator's own
 * speaker/audience toggle, and the queue itself. The moderator's toggle is the one control that
 * needs a runtime permission — moving *onto* stage needs the microphone, moving off never does.
 */
class WidgetStageRaisedHandsBottomSheet : AppBottomSheet() {

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    private val viewModel: WidgetStageRaisedHandsBottomSheetViewModel by appViewModels {
        WidgetStageRaisedHandsBottomSheetViewModel(channelId)
    }

    private lateinit var adapter: WidgetStageRaisedHandsBottomSheetAdapter
    private lateinit var requestToSpeakSubtitle: MaterialTextView
    private lateinit var requestToSpeakToggle: SwitchMaterial
    private lateinit var toggleSpeakerStateButton: MaterialTextView
    private lateinit var toggleSpeakerStateIcon: ImageView
    private lateinit var raisedHandsCountLabel: MaterialTextView
    private lateinit var emptyGroup: Group

    /**
     * The switch is checked in the layout, so the very first state we render would otherwise
     * animate from "on" to whatever the channel actually allows. Jumping the drawables once, on
     * the first emission only, removes that flicker without freezing later user toggles.
     */
    private var hasReceivedInitialRaisedHandsState = false

    override fun getContentViewResId() = R.layout.widget_stage_raised_hands_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasReceivedInitialRaisedHandsState = false

        requestToSpeakSubtitle = view.findViewById(R.id.subtitle)
        requestToSpeakToggle = view.findViewById(R.id.stage_raise_hands_enabled_toggle)
        toggleSpeakerStateButton = view.findViewById(R.id.toggle_speaker_state_button)
        toggleSpeakerStateIcon = view.findViewById(R.id.toggle_speaker_state_icon)
        raisedHandsCountLabel = view.findViewById(R.id.raised_hands_count_label)
        emptyGroup = view.findViewById(R.id.raised_hands_empty_group)

        val recycler = view.findViewById<MaxHeightRecyclerView>(R.id.stage_raised_hands_recycler)
        val divider = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
        ContextCompat.getDrawable(view.context, R.drawable.widget_stage_raised_hands_divider)
            ?.let(divider::setDrawable)
        recycler.addItemDecoration(divider)

        adapter = MGRecyclerAdapter.configure(
            WidgetStageRaisedHandsBottomSheetAdapter(
                recycler = recycler,
                onInviteToSpeak = viewModel::inviteToSpeak,
                onDismissRequest = viewModel::dismissRequestToSpeak,
                onViewProfile = { voiceUser ->
                    WidgetUserSheet.show(
                        fragmentManager = parentFragmentManager,
                        userId = voiceUser.user.id,
                        channelId = channelId,
                    )
                },
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetStageRaisedHandsBottomSheet::class.java) { configureUI(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetStageRaisedHandsBottomSheet::class.java) { handleEvent(it) }
    }

    private fun configureUI(viewState: WidgetStageRaisedHandsBottomSheetViewModel.ViewState) {
        when (viewState) {
            is WidgetStageRaisedHandsBottomSheetViewModel.ViewState.Loaded -> {
                requestToSpeakSubtitle.setText(
                    if (viewState.isRequestToSpeakEnabled) {
                        R.string.request_to_speak_area_all_roles
                    } else {
                        R.string.request_to_speak_area_all_roles_disabled
                    },
                )

                requestToSpeakToggle.isEnabled = !viewState.isUpdatingRequestToSpeakPermissions
                // While a permission write is in flight the switch keeps whatever the moderator
                // just set; re-applying store state mid-flight would visibly bounce it back.
                if (!viewState.isUpdatingRequestToSpeakPermissions) {
                    requestToSpeakToggle.setProgrammaticChecked(
                        viewState.isRequestToSpeakEnabled,
                    ) { _, isChecked -> viewModel.setRequestToSpeakEnabled(isChecked) }
                }
                if (!hasReceivedInitialRaisedHandsState) {
                    hasReceivedInitialRaisedHandsState = true
                    requestToSpeakToggle.jumpDrawablesToCurrentState()
                }

                emptyGroup.visibility = if (viewState.isEmpty) View.VISIBLE else View.GONE
                raisedHandsCountLabel.text = raisedHandsCountLabel.i18nFormat(
                    R.string.request_to_speak_list_title,
                    "numHands" to viewState.raisedHandsParticipants.size,
                )

                configureToggleSpeakerState(viewState)
                adapter.setData(viewState.raisedHandsParticipants)
            }
        }
    }

    private fun configureToggleSpeakerState(
        viewState: WidgetStageRaisedHandsBottomSheetViewModel.ViewState.Loaded,
    ) {
        toggleSpeakerStateButton.setOnClickListener {
            if (viewState.isSpeaker) {
                viewModel.setSpeakingState(true)
            } else {
                requestMicrophone(
                    onFailure = {
                        AppToast.show(
                            context,
                            R.string.stage_channel_permission_microphone_denied,
                        )
                    },
                ) { viewModel.setSpeakingState(false) }
            }
        }
        toggleSpeakerStateButton.setText(
            if (viewState.isSpeaker) {
                R.string.move_me_to_audience
            } else {
                R.string.moderator_raise_own_hand
            },
        )
        toggleSpeakerStateIcon.setImageResource(
            if (viewState.isSpeaker) {
                R.drawable.ic_stage_move_to_audience
            } else {
                R.drawable.ic_stage_move_to_speaker
            },
        )
    }

    private fun handleEvent(event: WidgetStageRaisedHandsBottomSheetViewModel.Event) {
        when (event) {
            is WidgetStageRaisedHandsBottomSheetViewModel.Event.Dismiss -> dismiss()
            is WidgetStageRaisedHandsBottomSheetViewModel.Event.Error ->
                event.error.showToasts(context)
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
        ): WidgetStageRaisedHandsBottomSheet =
            WidgetStageRaisedHandsBottomSheet().apply {
                arguments = bundleOf(EXTRA_CHANNEL_ID to channelId)
                show(
                    fragmentManager,
                    WidgetStageRaisedHandsBottomSheet::class.java.simpleName,
                )
            }
    }
}
