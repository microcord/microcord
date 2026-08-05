package com.discord.widgets.chat.polls

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.MessagePoll
import com.discord.api.message.PollAnswer
import com.discord.api.message.PollAnswerCount
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetPollDetailsBinding
import com.discord.databinding.WidgetPollDetailsAnswerBinding
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

class WidgetPollDetails : AppFragment(R.layout.widget_poll_details) {
    private var binding: WidgetPollDetailsBinding? = null
    private var answersAdapter: AnswersAdapter? = null
    private var resultsAdapter: ManageReactionsResultsAdapter? = null
    private var poll: MessagePoll? = null
    private var selectedAnswerId = 1
    private var request: Subscription? = null
    private var loadedCount = -1

    private val channelId get() = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val messageId get() = mostRecentIntent.getLongExtra(EXTRA_MESSAGE_ID, -1L)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (channelId <= 0L || messageId <= 0L) {
            activity?.finish()
            return
        }
        val binding = WidgetPollDetailsBinding.bind(view).also { this.binding = it }
        selectedAnswerId = mostRecentIntent.getIntExtra(EXTRA_ANSWER_ID, 1)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.poll_votes)
        answersAdapter = AnswersAdapter { selectAnswer(it) }.also {
            binding.manageReactionsEmojisRecycler.adapter = it
        }
        resultsAdapter = MGRecyclerAdapter.configure(
            ManageReactionsResultsAdapter(binding.manageReactionsResultsRecycler),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getMessages().observeMessagesForChannel(channelId)
            .map { messages -> messages.firstOrNull { it.id == messageId }?.poll }
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(javaClass) { poll ->
                if (poll == null) {
                    activity?.finish()
                    return@appSubscribe
                }
                val previousCount = answerCount(selectedAnswerId)
                this.poll = poll
                setActionBarSubtitle(poll.question.text)
                if (poll.answers.none { it.answerId == selectedAnswerId }) {
                    selectedAnswerId = poll.answers.firstOrNull()?.answerId ?: return@appSubscribe
                }
                bindAnswers()
                val count = answerCount(selectedAnswerId)
                if (loadedCount < 0 || count != previousCount) selectAnswer(selectedAnswerId)
            }
    }

    private fun bindAnswers() {
        val poll = poll ?: return
        answersAdapter?.items = poll.answers.map { answer ->
            AnswerItem(
                answer,
                poll.results?.answerCounts?.firstOrNull { it.id == answer.answerId }?.count ?: 0,
                answer.answerId == selectedAnswerId,
            )
        }
    }

    private fun selectAnswer(answerId: Int) {
        selectedAnswerId = answerId
        loadedCount = -1
        bindAnswers()
        request?.unsubscribe()
        val api = RestAPI.reactNativeApiOrNull
        if (api == null) {
            showStatus(R.string.poll_results_failed, retry = true)
            return
        }
        resultsAdapter?.setData(listOf(ManageReactionsResultsAdapter.LoadingItem()))
        binding?.pollDetailsStatus?.visibility = View.GONE
        binding?.manageReactionsResultsRecycler?.visibility = View.VISIBLE
        fetchVoters(answerId, linkedMapOf())
    }

    private fun fetchVoters(answerId: Int, users: LinkedHashMap<Long, com.discord.api.user.User>) {
        val api = RestAPI.reactNativeApiOrNull ?: return
        request = api.getPollVoters(channelId, messageId, answerId, after = users.keys.lastOrNull())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                if (selectedAnswerId != answerId) return@subscribe
                response.users.forEach { users[it.id] = it }
                val expected = answerCount(answerId)
                if (response.users.size == 100 && users.size < expected) {
                    fetchVoters(answerId, users)
                    return@subscribe
                }
                loadedCount = expected
                if (users.isEmpty()) {
                    resultsAdapter?.setData(emptyList())
                    showStatus(R.string.poll_no_votes, retry = false)
                    return@subscribe
                }
                val guildId = StoreStream.getChannels().getChannel(channelId)?.guildId ?: 0L
                val members = StoreStream.getGuilds().getMembers()[guildId].orEmpty()
                resultsAdapter?.setData(users.values.map { user ->
                    ManageReactionsResultsAdapter.ReactionUserItem(
                        user = CoreUser(user),
                        channelId = channelId,
                        messageId = messageId,
                        emoji = MessageReactionEmoji(null, "", false),
                        canDelete = false,
                        guildMember = members[user.id],
                    )
                })
                binding?.pollDetailsStatus?.visibility = View.GONE
                binding?.manageReactionsResultsRecycler?.visibility = View.VISIBLE
            }, {
                loadedCount = 0
                resultsAdapter?.setData(emptyList())
                showStatus(R.string.poll_results_failed, retry = true)
            })
    }

    private fun showStatus(message: Int, retry: Boolean) {
        val binding = binding ?: return
        binding.manageReactionsResultsRecycler.visibility = View.GONE
        binding.pollDetailsStatus.visibility = View.VISIBLE
        binding.pollDetailsStatusText.setText(message)
        binding.pollDetailsRetry.visibility = if (retry) View.VISIBLE else View.GONE
        binding.pollDetailsRetry.setOnClickListener {
            selectAnswer(selectedAnswerId)
        }
    }

    private fun answerCount(answerId: Int): Int = poll?.results?.answerCounts
        ?.firstOrNull { it.id == answerId }?.count ?: 0

    override fun onDestroyView() {
        request?.unsubscribe()
        request = null
        answersAdapter = null
        resultsAdapter?.unsubscribeFromUpdates()
        resultsAdapter = null
        binding = null
        super.onDestroyView()
    }

    private data class AnswerItem(
        val answer: PollAnswer,
        val count: Int,
        val selected: Boolean,
    )

    private class AnswersAdapter(private val onSelected: (Int) -> Unit) :
        RecyclerView.Adapter<AnswersAdapter.Holder>() {
        var items: List<AnswerItem> = emptyList()
            set(value) {
                field = value
                // ponytail: polls cap this static tab strip at 10 rows; diffing costs more than rebinding.
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetPollDetailsAnswerBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.pollDetailsAnswerEmoji.setPollEmoji(item.answer.pollMedia.emoji)
            holder.binding.pollDetailsAnswerText.text = holder.itemView.context.getString(
                R.string.poll_answer_tab,
                item.answer.pollMedia.text.orEmpty(),
                item.count,
            )
            holder.binding.pollDetailsAnswerSelected.visibility =
                if (item.selected) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onSelected(item.answer.answerId) }
        }

        class Holder(val binding: WidgetPollDetailsAnswerBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_ANSWER_ID = "com.discord.intent.extra.EXTRA_ANSWER_ID"

        fun launch(context: Context, channelId: Long, messageId: Long, answerId: Int = 1) {
            AppScreenRouter.launch(
                context,
                WidgetPollDetails::class.java,
                Intent()
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
                    .putExtra(EXTRA_ANSWER_ID, answerId),
            )
        }
    }
}
