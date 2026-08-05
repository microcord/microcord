package com.discord.widgets.chat.polls

import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.app.AppViewModel
import com.discord.models.domain.NonceGenerator
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class PollCreateViewModel : AppViewModel<PollCreateViewModel.State>(State()) {
    private val failures = PublishSubject.create<Unit>()

    fun observeFailures(): Observable<Unit> = failures

    fun setQuestion(question: String) = edit { copy(question = question) }
    fun setDuration(duration: PollDuration) = edit { copy(duration = duration) }
    fun setMultiselect(multiselect: Boolean) = edit { copy(multiselect = multiselect) }

    fun addAnswer() = edit {
        if (answers.size >= MAX_ANSWERS) this else copy(answers = answers + Answer())
    }

    fun removeAnswer(index: Int) = edit {
        if (answers.size <= MIN_ANSWERS) this
        else copy(answers = answers.filterIndexed { answerIndex, _ -> answerIndex != index })
    }

    fun setAnswerText(index: Int, text: String) = edit {
        copy(answers = answers.mapIndexed { answerIndex, answer ->
            if (answerIndex == index) answer.copy(text = text) else answer
        })
    }

    fun setAnswerEmoji(index: Int, emoji: MessageReactionEmoji) = edit {
        copy(answers = answers.mapIndexed { answerIndex, answer ->
            if (answerIndex == index) answer.copy(emoji = emoji) else answer
        })
    }

    fun submit(channelId: Long) {
        val state = requireViewState()
        if (!state.valid || state.submitting) return
        updateViewState(state.copy(submitting = true))
        val api = RestAPI.reactNativeApiOrNull
        if (api == null) {
            updateViewState(state.copy(submitting = false))
            failures.onNext(Unit)
            return
        }
        api.sendMessage(channelId, state.toBody(), null)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                javaClass,
                errorHandler = {
                    updateViewState(requireViewState().copy(submitting = false))
                    failures.onNext(Unit)
                },
                onNext = {
                    updateViewState(requireViewState().copy(submitting = false, submitted = true))
                },
            )
    }

    private fun edit(block: State.() -> State) {
        val state = requireViewState()
        if (!state.submitting) updateViewState(state.block())
    }

    data class Answer(
        val text: String = "",
        val emoji: MessageReactionEmoji? = null,
    )

    data class State(
        val question: String = "",
        val answers: List<Answer> = List(INITIAL_ANSWERS) { Answer() },
        val duration: PollDuration = PollDuration.ONE_DAY,
        val multiselect: Boolean = false,
        val submitting: Boolean = false,
        val submitted: Boolean = false,
    ) {
        val valid: Boolean
            get() = question.isNotBlank() && answers.size in MIN_ANSWERS..MAX_ANSWERS &&
                answers.all { it.text.isNotBlank() }

        fun toBody() = RestAPIParams.Message(
            nonce = NonceGenerator.computeNonce().toString(),
            poll = RestAPIParams.Message.Poll(
                question = RestAPIParams.Message.PollMedia(question.trim()),
                answers = answers.map { answer ->
                    RestAPIParams.Message.PollAnswer(
                        RestAPIParams.Message.PollMedia(
                            text = answer.text.trim(),
                            emoji = answer.emoji?.let { emoji ->
                                RestAPIParams.Message.PollEmoji(
                                    id = emoji.id?.toLongOrNull(),
                                    name = emoji.name.orEmpty(),
                                    animated = emoji.animated.takeIf { emoji.id != null },
                                )
                            },
                        ),
                    )
                },
                duration = duration.hours,
                allowMultiselect = multiselect,
            ),
        )
    }

    companion object {
        const val MIN_ANSWERS = 1
        private const val INITIAL_ANSWERS = 2
        const val MAX_ANSWERS = 10
    }
}
