package com.discord.widgets.message_requests

import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isMessageRequestChannel
import com.discord.api.channel.isSpamMessageRequestChannel
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetMessageRequestItemBinding
import com.discord.databinding.WidgetMessageRequestsBinding
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.stores.StoreNavigation
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui

class WidgetMessageRequests : AppFragment(R.layout.widget_message_requests) {
    private var _binding: WidgetMessageRequestsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = Adapter(::open, { update(it, true) }, { update(it, false) })
    private var requests = emptyList<Item>()
    private val previews = HashMap<Long, Message?>()
    private val loadingPreviews = HashSet<Long>()
    private val loadingActions = HashSet<Long>()
    private val dismissed = HashSet<Long>()
    private var showingSpam = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetMessageRequestsBinding.bind(view)
        setActionBarTitle(R.string.message_requests)
        setActionBarDisplayHomeAsUpEnabled(
            iconRes = R.drawable.ic_close_24dp,
            iconAccessibilityLabel = R.string.close,
        )?.setNavigationOnClickListener { activity?.finish() }
        actionBarTitleLayout?.layoutParams = Toolbar.LayoutParams(
            Toolbar.LayoutParams.WRAP_CONTENT,
            Toolbar.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        binding.messageRequestsList.adapter = adapter
        binding.messageRequestsTabs.setup()
        binding.messageRequestsTabs.setOnSegmentSelectedChangeListener { index ->
            showingSpam = index == 1
            binding.messageRequestsTabs.setSelectedIndex(index)
            render()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getChannels().observeChannelsForGuild(PRIVATE_CHANNELS_ID).map { channels ->
            channels.values.asSequence()
                .filter { it.isMessageRequestChannel() || it.isSpamMessageRequestChannel() }
                .filterNot { it.id in dismissed }
                .map { channel ->
                    Item(
                        channel,
                        StoreStream.getMessages().getMessages(channel.id).maxByOrNull(Message::id)
                            ?: previews[channel.id],
                        channel.id in loadingActions,
                    )
                }
                .sortedByDescending { it.channel.lastMessageId }
                .toList()
        }.computationLatest().ui(this).appSubscribe(javaClass) {
            requests = it
            render()
            it.filter { item -> item.message == null }.forEach(::loadPreview)
        }
    }

    private fun render() {
        val spam = showingSpam
        val items = requests.filter { if (spam) it.channel.isSpamMessageRequestChannel() else it.channel.isMessageRequestChannel() }
        binding.messageRequestsList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.messageRequestsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.messageRequestsEmptyText.setText(
            if (spam) R.string.message_requests_spam_empty else R.string.message_requests_empty,
        )
        adapter.submitList(
            if (items.isEmpty()) emptyList()
            else listOf<Row>(Row.Header(items.size, spam)) + items.map(Row::Request),
        )
    }

    private fun loadPreview(item: Item) {
        if (!loadingPreviews.add(item.channel.id)) return
        val api = RestAPI.apiOrNull ?: run {
            loadingPreviews.remove(item.channel.id)
            return
        }
        api.getChannelMessages(item.channel.id, limit = 1)
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                javaClass,
                errorHandler = { loadingPreviews.remove(item.channel.id) },
            ) { messages ->
                loadingPreviews.remove(item.channel.id)
                previews[item.channel.id] = messages.firstOrNull()?.let(::Message)
                requests = requests.map { current ->
                    if (current.channel.id == item.channel.id) current.copy(message = previews[item.channel.id]) else current
                }
                render()
            }
    }

    private fun open(item: Item) {
        StoreStream.getChannelsSelected().observeId()
            .filter { it == item.channel.id }
            .take(1)
            .ui(this)
            .appSubscribe(javaClass) {
                StoreStream.getNavigation().setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
                activity?.finish()
            }
        ChannelSelector.getInstance().selectChannel(item.channel)
    }

    private fun update(item: Item, accepted: Boolean) {
        if (!loadingActions.add(item.channel.id)) return
        renderLoading()
        val api = RestAPI.apiOrNull ?: run {
            loadingActions.remove(item.channel.id)
            renderLoading()
            return
        }
        val request = api.setMessageRequestAccepted(item.channel.id, accepted)
        request.restSubscribeOn(false).ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                loadingActions.remove(item.channel.id)
                renderLoading()
                AppToast.show(requireContext(), R.string.message_requests_action_failed)
            },
            onCompleted = {
                loadingActions.remove(item.channel.id)
                dismissed += item.channel.id
                requests = requests.filterNot { it.channel.id == item.channel.id }
                render()
            },
            onTerminated = {
                if (item.channel.id !in dismissed && loadingActions.remove(item.channel.id)) renderLoading()
            },
            onNext = {},
        )
    }

    private fun renderLoading() {
        requests = requests.map { it.copy(loading = it.channel.id in loadingActions) }
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    data class Item(
        val channel: Channel,
        val message: Message?,
        val loading: Boolean,
    )

    private sealed interface Row {
        data class Header(val count: Int, val spam: Boolean) : Row
        data class Request(val item: Item) : Row
    }

    private class Adapter(
        private val open: (Item) -> Unit,
        private val accept: (Item) -> Unit,
        private val reject: (Item) -> Unit,
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {
        override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) HEADER else REQUEST

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == HEADER) {
                HeaderHolder(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.widget_message_requests_header,
                        parent,
                        false,
                    ) as android.widget.TextView,
                )
            } else {
                Holder(
                    WidgetMessageRequestItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                    open,
                    accept,
                    reject,
                )
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Request -> (holder as Holder).bind(row.item)
            }
        }

        class HeaderHolder(private val text: android.widget.TextView) : RecyclerView.ViewHolder(text) {
            fun bind(header: Row.Header) {
                text.text = text.context.getString(
                    if (header.spam) R.string.message_requests_spam_count else R.string.message_requests_pending_count,
                    header.count,
                )
            }
        }

        class Holder(
            private val binding: WidgetMessageRequestItemBinding,
            private val open: (Item) -> Unit,
            private val accept: (Item) -> Unit,
            private val reject: (Item) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: Item) {
                IconUtils.setIcon(binding.messageRequestAvatar, item.channel, R.dimen.avatar_size_standard)
                binding.messageRequestName.text = item.channel.getDisplayNameOrDefault(binding.root.context)
                binding.messageRequestTimestamp.text = item.channel.lastMessageId.takeIf { it > 0 }?.let {
                    DateUtils.getRelativeTimeSpanString(
                        SnowflakeUtils.toTimestamp(it),
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    )
                }
                val context = binding.root.context
                binding.messageRequestPreview.text = when {
                    !item.message?.content.isNullOrBlank() -> item.message?.content
                    item.message?.hasStickers() == true -> context.getString(R.string.sticker)
                    !item.message?.attachments.isNullOrEmpty() -> context.getString(R.string.message_requests_attachment)
                    item.message?.hasEmbeds() == true -> context.getString(R.string.embed_links)
                    else -> context.getString(R.string.message_requests_preview_unavailable)
                }
                val spam = item.channel.isSpamMessageRequestChannel()
                binding.messageRequestAccept.contentDescription = context.getString(
                    if (spam) R.string.message_requests_not_spam else R.string.message_requests_accept,
                )
                binding.messageRequestReject.contentDescription = context.getString(
                    if (spam) R.string.message_requests_report_spam else R.string.message_requests_ignore,
                )
                binding.root.setOnClickListener { open(item) }
                binding.messageRequestAccept.setOnClickListener { accept(item) }
                binding.messageRequestReject.setOnClickListener { reject(item) }
                binding.messageRequestAccept.isEnabled = !item.loading
                binding.messageRequestReject.isEnabled = !item.loading
                binding.messageRequestAccept.alpha = if (item.loading) 0.5f else 1f
                binding.messageRequestReject.alpha = binding.messageRequestAccept.alpha
            }
        }

        companion object {
            private const val HEADER = 0
            private const val REQUEST = 1
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(old: Row, new: Row) = when {
                    old is Row.Header && new is Row.Header -> true
                    old is Row.Request && new is Row.Request -> old.item.channel.id == new.item.channel.id
                    else -> false
                }
                override fun areContentsTheSame(old: Row, new: Row) = old == new
            }
        }
    }

    companion object {
        private const val PRIVATE_CHANNELS_ID = 0L
        fun show(context: Context) = AppScreenRouter.launch(context, WidgetMessageRequests::class.java)
    }
}
