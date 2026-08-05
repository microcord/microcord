package com.discord.widgets.chat.list.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.message.PollAnswerCount
import com.discord.app.AppToast
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.databinding.WidgetChatListAdapterItemPollBinding
import com.discord.databinding.WidgetPollAnswerBinding
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.PollEntry
import com.discord.widgets.chat.polls.WidgetPollDetails
import com.discord.widgets.chat.polls.setPollEmoji
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

class WidgetChatListAdapterItemPoll private constructor(
    adapter: WidgetChatListAdapter,
    private val binding: WidgetChatListAdapterItemPollBinding,
) : WidgetChatListItem(binding.root, adapter) {
    constructor(adapter: WidgetChatListAdapter) : this(
        adapter,
        WidgetChatListAdapterItemPollBinding.inflate(
            LayoutInflater.from(adapter.recycler.context),
            adapter.recycler,
            false,
        ),
    )

    private var entry: PollEntry? = null
    private val selected = linkedSetOf<Int>()
    private var peekingResults = false
    private var submitting = false
    private var showingResults: Boolean? = null
    private val refreshCountdown = Runnable { entry?.let(::bind) }

    init {
        itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                entry?.let(::bind)
            }
            override fun onViewDetachedFromWindow(view: View) {
                binding.pollInfo.removeCallbacks(refreshCountdown)
            }
        })
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val next = data as PollEntry
        if (entry?.message?.id != next.message.id) {
            selected.clear()
            peekingResults = false
            submitting = false
            showingResults = null
        }
        entry = next
        configureCellHighlight(
            next.message,
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            next.isPendingReply,
        )
        bind(next)
    }

    private fun bind(entry: PollEntry) {
        binding.pollInfo.removeCallbacks(refreshCountdown)
        val context = itemView.context
        val poll = entry.poll
        val results = poll.results?.answerCounts.orEmpty()
        val closed = poll.results?.finalized == true ||
            (poll.expiry?.dateTimeMillis ?: Long.MAX_VALUE) <= System.currentTimeMillis()
        val voted = results.any { it.meVoted }
        val showingResults = closed || voted || peekingResults
        val animateResults = shouldAnimatePollResults(
            showingResults,
            this.showingResults,
            AccessibilityUtils.isReducedMotionEnabled(),
        )
        val totalVotes = results.sumOf(PollAnswerCount::count)

        binding.pollQuestion.text = poll.question.text.orEmpty()
        binding.pollSubtitle.text = context.getString(
            if (poll.allowMultiselect) R.string.poll_select_multiple else R.string.poll_select_one,
        )
        binding.pollSubtitle.visibility = if (closed) View.GONE else View.VISIBLE
        binding.pollAnswers.removeAllViews()
        poll.answers.forEach { answer ->
            val count = results.firstOrNull { it.id == answer.answerId }
                ?: PollAnswerCount(answer.answerId, 0, false)
            val row = WidgetPollAnswerBinding.inflate(
                LayoutInflater.from(context),
                binding.pollAnswers,
                true,
            )
            row.pollAnswerText.text = answer.pollMedia.text.orEmpty()
            row.pollAnswerEmoji.setPollEmoji(answer.pollMedia.emoji)
            row.pollAnswerRadio.visibility = if (!showingResults && !poll.allowMultiselect) View.VISIBLE else View.GONE
            row.pollAnswerCheck.visibility = if (!showingResults && poll.allowMultiselect) View.VISIBLE else View.GONE
            row.pollAnswerRadio.isChecked = answer.answerId in selected
            row.pollAnswerCheck.isChecked = answer.answerId in selected
            row.pollAnswerVotes.visibility = if (showingResults) View.VISIBLE else View.GONE
            row.pollAnswerProgress.visibility = if (showingResults) View.VISIBLE else View.GONE
            row.pollAnswerMeVoted.visibility = if (showingResults && count.meVoted) View.VISIBLE else View.GONE
            val percent = count.count * 100 / totalVotes.coerceAtLeast(1)
            row.pollAnswerVotes.text = context.getString(
                R.string.poll_answer_result,
                percent,
                context.resources.getQuantityString(R.plurals.poll_vote_count, count.count, count.count),
            )
            row.pollAnswerProgress.setIndicatorColor(
                ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(context, if (count.meVoted) R.color.brand_500 else R.color.primary_500),
                    0x50,
                ),
            )
            row.pollAnswerProgress.trackColor = Color.TRANSPARENT
            row.pollAnswerProgress.setProgressCompat(if (animateResults) 0 else percent, false)
            if (animateResults) row.pollAnswerProgress.post {
                row.pollAnswerProgress.setProgressCompat(percent, true)
            }
            val winner = closed && count.count > 0 && count.count == results.maxOfOrNull { it.count }
            row.pollAnswerWinner.visibility = if (winner) View.VISIBLE else View.GONE
            row.root.setOnClickListener {
                if (showingResults) {
                    WidgetPollDetails.launch(context, entry.message.channelId, entry.message.id, answer.answerId)
                } else if (!submitting) {
                    if (!poll.allowMultiselect) selected.clear()
                    if (!selected.add(answer.answerId)) selected.remove(answer.answerId)
                    bind(entry)
                }
            }
        }

        binding.pollVoteControls.visibility = if (!showingResults) View.VISIBLE else View.GONE
        binding.pollBackToVote.visibility = if (peekingResults && !closed && !voted) View.VISIBLE else View.GONE
        binding.pollRemoveVote.visibility = if (voted && !closed) View.VISIBLE else View.GONE
        binding.pollVote.isEnabled = selected.isNotEmpty() && !submitting
        binding.pollShowResults.isEnabled = !submitting
        binding.pollRemoveVote.isEnabled = !submitting
        binding.pollVote.setOnClickListener { submitVote(entry, selected.toList()) }
        binding.pollShowResults.setOnClickListener { peekingResults = true; bind(entry) }
        binding.pollBackToVote.setOnClickListener { peekingResults = false; bind(entry) }
        binding.pollRemoveVote.setOnClickListener { submitVote(entry, emptyList()) }
        binding.pollInfo.text = pollInfo(entry, totalVotes, closed)
        binding.pollInfo.setOnClickListener {
            WidgetPollDetails.launch(context, entry.message.channelId, entry.message.id)
        }
        poll.expiry?.dateTimeMillis?.takeIf { !closed }?.let { expiry ->
            binding.pollInfo.postDelayed(
                refreshCountdown,
                minOf(60_000L, (expiry - System.currentTimeMillis()).coerceAtLeast(1L)),
            )
        }
        this.showingResults = showingResults
    }

    private fun pollInfo(entry: PollEntry, totalVotes: Int, closed: Boolean): String {
        val context = itemView.context
        val status = if (closed) {
            context.getString(R.string.poll_closed)
        } else {
            val remaining = (entry.poll.expiry?.dateTimeMillis ?: System.currentTimeMillis()) -
                System.currentTimeMillis()
            context.getString(
                R.string.poll_ends_in,
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    System.currentTimeMillis() + remaining.coerceAtLeast(0L),
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
                ),
            )
        }
        return context.getString(
            R.string.poll_votes_and_status,
            context.resources.getQuantityString(R.plurals.poll_vote_count, totalVotes, totalVotes),
            status,
        )
    }

    private fun submitVote(entry: PollEntry, answerIds: List<Int>) {
        val api = RestAPI.apiOrNull
        if (api == null) {
            AppToast.show(itemView.context, R.string.poll_vote_failed)
            return
        }
        submitting = true
        bind(entry)
        val messageId = entry.message.id
        api.votePoll(entry.message.channelId, messageId, RestAPIParams.PollVote(answerIds))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {},
                {
                    if (this.entry?.message?.id == messageId) {
                        submitting = false
                        bind(this.entry ?: return@subscribe)
                    }
                    AppToast.show(itemView.context, R.string.poll_vote_failed)
                },
                {
                    if (this.entry?.message?.id == messageId) {
                        submitting = false
                        selected.clear()
                        bind(this.entry ?: return@subscribe)
                    }
                },
            )
    }
}

internal fun shouldAnimatePollResults(showingResults: Boolean, previous: Boolean?, reducedMotion: Boolean) =
    showingResults && previous != true && !reducedMotion
