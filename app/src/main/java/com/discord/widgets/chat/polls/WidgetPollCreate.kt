package com.discord.widgets.chat.polls

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.databinding.WidgetPollCreateAnswerBinding
import com.discord.databinding.WidgetPollCreateBinding
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.hubs.RadioSelectorItem
import com.discord.widgets.hubs.RadioSelectorItems
import com.discord.widgets.hubs.WidgetRadioSelectorBottomSheet

class WidgetPollCreate : AppFragment(R.layout.widget_poll_create) {
    private val viewModel: PollCreateViewModel by appViewModels { PollCreateViewModel() }
    private var binding: WidgetPollCreateBinding? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetPollCreateBinding.bind(view).also { this.binding = it }
        val channelId = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId <= 0L) {
            activity?.finish()
            return
        }

        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.poll_create)
        setActionBarSubtitle(mostRecentIntent.getStringExtra(EXTRA_CHANNEL_NAME))

        val state = viewModel.requireViewState()
        binding.pollQuestionWrap.editText?.setText(state.question)
        binding.pollQuestionWrap.editText?.doAfterTextChanged { viewModel.setQuestion(it.toString()) }
        binding.pollAddAnswer.setOnClickListener { viewModel.addAnswer() }
        binding.pollDuration.setOnClickListener { showDurationSelector() }
        binding.pollMultiselect.setOnCheckedChangeListener { _, checked -> viewModel.setMultiselect(checked) }
        binding.pollCreate.setOnClickListener { viewModel.submit(channelId) }
        renderAnswers(state)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(javaClass, onNext = ::configureState)
        viewModel.observeFailures().ui(this).appSubscribe(javaClass) {
            AppToast.show(this, R.string.poll_create_failed)
        }
    }

    private fun configureState(state: PollCreateViewModel.State) {
        if (state.submitted) {
            activity?.finish()
            return
        }
        val binding = binding ?: return
        if (binding.pollAnswersContainer.childCount != state.answers.size) renderAnswers(state)
        binding.pollDurationValue.setText(state.duration.labelRes)
        if (binding.pollMultiselect.isChecked != state.multiselect) {
            binding.pollMultiselect.isChecked = state.multiselect
        }
        binding.pollAddAnswer.visibility = if (state.answers.size < PollCreateViewModel.MAX_ANSWERS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.pollCreate.isEnabled = state.valid && !state.submitting
        binding.pollCreate.setText(if (state.submitting) R.string.poll_creating else R.string.poll_create)
    }

    private fun renderAnswers(state: PollCreateViewModel.State = viewModel.requireViewState()) {
        val container = binding?.pollAnswersContainer ?: return
        container.removeAllViews()
        state.answers.forEachIndexed { index, answer ->
            val row = WidgetPollCreateAnswerBinding.inflate(LayoutInflater.from(container.context), container, true)
            row.pollAnswerWrap.editText?.setText(answer.text)
            row.pollAnswerWrap.editText?.doAfterTextChanged { viewModel.setAnswerText(index, it.toString()) }
            row.pollAnswerDelete.visibility = if (state.answers.size > PollCreateViewModel.MIN_ANSWERS) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            row.pollAnswerDelete.setOnClickListener { viewModel.removeAnswer(index) }
            row.pollAnswerEmoji.setPollEmoji(answer.emoji)
            row.pollAnswerEmojiPlaceholder.visibility = if (answer.emoji == null) View.VISIBLE else View.GONE
            row.pollAnswerEmojiButton.contentDescription = answer.emoji?.name
                ?: getString(R.string.poll_answer_emoji)
            row.pollAnswerEmojiButton.setOnClickListener {
                WidgetEmojiPickerSheet.show(parentFragmentManager) { emoji ->
                    viewModel.setAnswerEmoji(index, emoji)
                    renderAnswers()
                }
            }
        }
    }

    private fun showDurationSelector() {
        val selected = viewModel.requireViewState().duration
        WidgetRadioSelectorBottomSheet.show(
            parentFragmentManager,
            RadioSelectorItems(
                getString(R.string.poll_duration),
                PollDuration.entries.map { duration ->
                    RadioSelectorItem(duration.hours, getString(duration.labelRes), duration == selected)
                },
            ),
        ) { hours ->
            PollDuration.entries.firstOrNull { it.hours == hours }?.let(viewModel::setDuration)
            (parentFragmentManager.findFragmentByTag(WidgetRadioSelectorBottomSheet::class.java.name)
                as? WidgetRadioSelectorBottomSheet)?.dismiss()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_CHANNEL_NAME = "com.discord.intent.extra.EXTRA_CHANNEL_NAME"

        fun launch(context: Context, channelId: Long, channelName: String?) {
            AppScreenRouter.launch(
                context,
                WidgetPollCreate::class.java,
                Intent().putExtra(EXTRA_CHANNEL_ID, channelId).putExtra(EXTRA_CHANNEL_NAME, channelName),
            )
        }
    }
}
