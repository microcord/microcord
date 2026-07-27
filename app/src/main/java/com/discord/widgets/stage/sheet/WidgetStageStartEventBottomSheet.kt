package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setEnabledAndAlpha
import com.discord.utilities.view.text.addBindedTextWatcher
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Opens a stage, or renames the one already running — the same form serves both, and only the
 * copy and the REST call differ.
 *
 * Starting is gated on the microphone permission, but a refusal does not block the stage: the
 * stage opens either way and the starter simply stays in the audience.
 */
class WidgetStageStartEventBottomSheet : AppBottomSheet(shouldAvoidKeyboard = true) {

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    private val viewModel: WidgetStageStartEventBottomSheetViewModel by appViewModels {
        WidgetStageStartEventBottomSheetViewModel(channelId)
    }

    /** Invoked once the stage has been opened or saved, so the host can close whatever led here. */
    var onDismiss: (() -> Unit)? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var speakerHint: TextView
    private lateinit var topicLayout: TextInputLayout
    private lateinit var topicInput: TextInputEditText
    private lateinit var startStageButton: MaterialButton

    override fun getContentViewResId() = R.layout.widget_stage_start_event_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.title)
        subtitle = view.findViewById(R.id.subtitle)
        speakerHint = view.findViewById(R.id.speaker_hint)
        topicLayout = view.findViewById(R.id.topic_layout)
        topicInput = view.findViewById(R.id.topic_input)
        startStageButton = view.findViewById(R.id.start_stage_button)

        topicInput.addBindedTextWatcher(this) { editable ->
            // Typing is the acknowledgement of the "topic required" error.
            topicLayout.error = null
            viewModel.setTopic(editable.toString())
        }

        startStageButton.setOnClickListener {
            requestMicrophone(onFailure = { openStage(microphonePermissionGranted = false) }) {
                openStage(microphonePermissionGranted = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetStageStartEventBottomSheet::class.java) { handleEvent(it) }
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetStageStartEventBottomSheet::class.java) { configureUI(it) }
    }

    private fun openStage(microphonePermissionGranted: Boolean) {
        viewModel.openStage(microphonePermissionGranted)
        onDismiss?.invoke()
    }

    private fun configureUI(viewState: WidgetStageStartEventBottomSheetViewModel.ViewState) {
        val loaded = viewState as? WidgetStageStartEventBottomSheetViewModel.ViewState.Loaded
            ?: return

        startStageButton.setEnabledAndAlpha(loaded.topic.isNotEmpty())

        // Only push the model's topic back into the field when it has genuinely diverged, so
        // echoing the user's own keystrokes does not move their cursor to the end of the line.
        if (loaded.topic != topicLayout.getTextOrEmpty()) {
            topicInput.setText(loaded.topic)
        }

        val isStarting = loaded.stageInstance == null
        title.setText(
            if (isStarting) R.string.start_stage_channel_event_modal_title else R.string.edit_stage_title,
        )
        subtitle.setText(
            if (isStarting) {
                R.string.start_stage_channel_event_modal_subtitle
            } else {
                R.string.edit_stage_subtitle
            },
        )
        startStageButton.setText(
            if (isStarting) R.string.start_stage_channel_event_modal_button else R.string.save_changes,
        )
        // "You will be a speaker" only applies to opening a stage, never to renaming one.
        speakerHint.visibility = if (isStarting) View.VISIBLE else View.GONE
    }

    private fun handleEvent(event: WidgetStageStartEventBottomSheetViewModel.Event) {
        when (event) {
            is WidgetStageStartEventBottomSheetViewModel.Event.SetTopicSuccess -> {
                hideKeyboard()
                dismiss()
            }
            is WidgetStageStartEventBottomSheetViewModel.Event.SetTopicFailure ->
                AppToast.show(context, event.failureMessageStringRes)
            is WidgetStageStartEventBottomSheetViewModel.Event.InvalidTopic ->
                topicLayout.error = getString(event.failureMessageStringRes)
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            onDismiss: (() -> Unit)? = null,
        ): WidgetStageStartEventBottomSheet =
            WidgetStageStartEventBottomSheet().apply {
                this.onDismiss = onDismiss
                arguments = bundleOf(EXTRA_CHANNEL_ID to channelId)
                show(fragmentManager, WidgetStageStartEventBottomSheet::class.java.simpleName)
            }
    }
}
