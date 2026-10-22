package com.discord.widgets.chat.bookmarks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isPrivate
import com.discord.api.message.MessageTypes
import com.discord.api.permission.Permission
import com.discord.api.savedmessage.SavedMessage
import com.discord.app.AppActivity
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetBookmarkOptionsBinding
import com.discord.databinding.WidgetChatListAdapterItemTextBinding
import com.discord.databinding.WidgetChatListAdapterItemForwardedMessageBinding
import com.discord.databinding.WidgetMessageBookmarkCardBinding
import com.discord.databinding.WidgetMessageBookmarksBinding
import com.discord.models.message.Message
import com.discord.stores.StoreSavedMessages
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.intent.IntentUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.spans.SpoilerSpan
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemForwardedMessage
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.MediaMosaicEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.discord.widgets.user.WidgetUserMentions
import rx.Observable

/** Account-synced bookmarks, using the same capped, non-interactive message cards as RN. */
class WidgetMessageBookmarks : AppFragment(R.layout.widget_message_bookmarks) {
    private var binding: WidgetMessageBookmarksBinding? = null
    private var adapter: BookmarksAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.bookmark_actions_title)
        setActionBarDisplayHomeAsUpEnabled()
        binding = WidgetMessageBookmarksBinding.bind(view).also { bound ->
            adapter = BookmarksAdapter()
            bound.bookmarksList.layoutManager = LinearLayoutManager(requireContext())
            bound.bookmarksList.itemAnimator = null
            bound.bookmarksList.adapter = adapter
            bound.bookmarksRetry.setOnClickListener { StoreStream.getSavedMessages().fetch() }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Observable.combineLatest(
            StoreStream.getSavedMessages().observe(),
            StoreStream.getChannels().observeAllChannels(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getPermissions().observePermissionsForAllChannels(),
        ) { state, _, _, _ -> state }.ui(this)
            .appSubscribe(WidgetMessageBookmarks::class.java, onNext = ::render)
        StoreStream.getSavedMessages().fetch()
    }

    private fun render(state: StoreSavedMessages.State) {
        val bound = binding ?: return
        val bookmarks = state.bookmarks
        adapter?.submit(bookmarks)
        bound.bookmarksLoading.root.isVisible = state.loading
        bound.bookmarksRetry.isVisible = state.error != null
        bound.bookmarksEmpty.isVisible = bookmarks.isEmpty() && !state.loading && state.error == null
    }

    override fun onPause() {
        binding?.bookmarksList?.let { list ->
            repeat(list.childCount) { (list.getChildViewHolder(list.getChildAt(it)) as? CardHolder)?.previewAdapter?.onPause() }
        }
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.bookmarksList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private inner class BookmarksAdapter : RecyclerView.Adapter<CardHolder>() {
        private var entries = emptyList<SavedMessage>()

        init { setHasStableIds(true) }
        override fun getItemCount() = entries.size
        override fun getItemId(position: Int) = entries[position].saveData.messageId

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<SavedMessage>) {
            entries = next
            // The at-most-200 cards also depend on channel/permission snapshots, not just saved data.
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(
            WidgetMessageBookmarkCardBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(entries[position])
        override fun onViewDetachedFromWindow(holder: CardHolder) = holder.previewAdapter.onPause()
        override fun onViewRecycled(holder: CardHolder) = holder.release()
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            repeat(recyclerView.childCount) {
                (recyclerView.getChildViewHolder(recyclerView.getChildAt(it)) as? CardHolder)?.release()
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    private inner class CardHolder(private val card: WidgetMessageBookmarkCardBinding) : RecyclerView.ViewHolder(card.root) {
        val previewAdapter = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(
                card.bookmarkMessage, this@WidgetMessageBookmarks, parentFragmentManager,
                object : WidgetChatListAdapter.EventHandler {}, stickToBottom = false,
            ),
        ).apply { mentionMeMessageLevelHighlighting = false }

        init {
            card.bookmarkMessage.itemAnimator = null
            card.bookmarkMessage.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    previewText(view)?.apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) {
                    (card.bookmarkMessage.getChildViewHolder(view) as? FragmentLifecycleListener)?.onPause()
                }
            })
            ViewCompat.setAccessibilityDelegate(card.bookmarkJump, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val text = SpannableStringBuilder()
                    repeat(card.bookmarkMessage.childCount) { index ->
                        previewText(card.bookmarkMessage.getChildAt(index))?.text?.let { text.append(it).append('\n') }
                    }
                    // Use rendered spoiler spans, never the raw message behind an unrevealed spoiler.
                    text.getSpans(0, text.length, SpoilerSpan::class.java)
                        .sortedByDescending(text::getSpanStart).forEach { span ->
                            if (!span.isRevealed) text.replace(text.getSpanStart(span), text.getSpanEnd(span),
                                host.context.getString(R.string.spoiler_hidden_a11y_label))
                        }
                    info.contentDescription = host.context.getString(
                        R.string.bookmark_preview_description, card.bookmarkChannel.text, text,
                    )
                }
            })
        }

        private fun previewText(view: View) = when (card.bookmarkMessage.getChildViewHolder(view)) {
            is WidgetChatListAdapterItemMessage -> WidgetChatListAdapterItemTextBinding.bind(view).chatListAdapterItemText
            is WidgetChatListAdapterItemForwardedMessage -> WidgetChatListAdapterItemForwardedMessageBinding.bind(view).forwardedMessageContent
            else -> null
        }

        fun bind(saved: SavedMessage) {
            previewAdapter.onPause()
            val data = saved.saveData
            val channel = StoreStream.getChannels().getChannel(data.channelId)
            val permissions = StoreStream.getPermissions().getPermissionsByChannel()[data.channelId]
            val available = canViewBookmark(saved, channel, permissions)
            val guild = data.guildId?.let { StoreStream.getGuilds().getGuild(it) }
            card.bookmarkChannel.text = if (available) {
                channel?.getDisplayNameOrDefault(itemView.context) ?: itemView.context.getString(R.string.bookmark_unknown_channel)
            } else itemView.context.getString(R.string.bookmark_actions_title)
            card.bookmarkIcon.isVisible = available && (guild != null || channel?.isPrivate() == true)
            if (available && guild != null) IconUtils.setIcon(card.bookmarkIcon, guild, R.dimen.avatar_size_small)
            else if (available && channel != null) IconUtils.setIcon(card.bookmarkIcon, channel, R.dimen.avatar_size_small)
            else card.bookmarkIcon.setImageURI(null as String?)
            card.bookmarkUnavailable.isVisible = !available
            card.bookmarkPreview.isVisible = available
            card.bookmarkOptions.setOnClickListener { WidgetBookmarkOptions.show(parentFragmentManager, saved) }
            card.bookmarkJump.setOnClickListener { jumpToBookmark(itemView.context, saved) }
            if (!available) {
                previewAdapter.setData(emptyList())
                return
            }
            val message = bookmarkPreviewMessage(Message(requireNotNull(saved.message)))
            val displayChannel = channel ?: bookmarkPreviewChannel(saved, itemView.context.getString(R.string.bookmark_unknown_channel))
            val settings = StoreStream.getUserSettings()
            val rows = ChatListRows.messageRows(
                ChatListRows.State(
                    channel = displayChannel, guild = guild, permissions = permissions,
                    guildMembers = StoreStream.getGuilds().getMembers()[displayChannel.guildId].orEmpty(),
                    guildRoles = StoreStream.getGuilds().getRoles()[displayChannel.guildId].orEmpty(),
                    animateEmojis = false, autoPlayGifs = false,
                    renderEmbeds = settings.getIsRenderEmbedsEnabled() && settings.getIsEmbedMediaInlined(),
                ),
                message,
            ).filter { it is MessageEntry || it is AttachmentEntry || it is MediaMosaicEntry || it is EmbedEntry || it is StickerEntry || it is ForwardedMessageEntry }
            previewAdapter.data = WidgetUserMentions.Model(
                userId = StoreStream.getUsers().getMe().id,
                guildId = displayChannel.guildId, guild = guild,
                channelNames = StoreStream.getChannels().getChannelNames(), list = rows,
                channelId = displayChannel.id,
            )
        }

        fun release() {
            previewAdapter.onPause()
            previewAdapter.dispose()
            previewAdapter.setData(emptyList())
        }
    }

    companion object {
        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetMessageBookmarks::class.java, Intent())
    }
}

class WidgetBookmarkOptions : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_bookmark_options

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetBookmarkOptionsBinding.bind(view)
        val channelId = argumentsOrDefault.getLong("channel_id")
        val messageId = argumentsOrDefault.getLong("message_id")
        val store = StoreStream.getSavedMessages()
        val saved = store.getState().entries["$channelId-$messageId"] ?: run { dismiss(); return }
        val channel = StoreStream.getChannels().getChannel(channelId)
        binding.bookmarkJump.isVisible = canViewBookmark(saved, channel, StoreStream.getPermissions().getPermissionsByChannel()[channelId])
        binding.bookmarkJump.setOnClickListener {
            jumpToBookmark(requireContext(), saved)
            dismiss()
        }
        binding.bookmarkRemove.setOnClickListener {
            binding.bookmarkRemove.isEnabled = false
            store.remove(channelId, messageId).toCompletable().andThen(Observable.just(Unit))
                .restSubscribeOn(retry = false).ui(this)
                .appSubscribe(
                    WidgetBookmarkOptions::class.java, context = context,
                    onTerminated = { binding.bookmarkRemove.isEnabled = true },
                    onNext = {
                        AppToast.show(requireContext(), R.string.bookmark_action_removed)
                        dismiss()
                    },
                )
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager, saved: SavedMessage) = WidgetBookmarkOptions().apply {
            arguments = Bundle().apply {
                putLong("channel_id", saved.saveData.channelId)
                putLong("message_id", saved.saveData.messageId)
            }
        }.show(fragmentManager, WidgetBookmarkOptions::class.java.name)
    }
}

internal fun canViewBookmark(saved: SavedMessage, channel: Channel?, permissions: Long?): Boolean =
    saved.message != null && (channel == null || channel.type == 10000 || channel.isPrivate() ||
        PermissionUtils.can(Permission.VIEW_CHANNEL, permissions))

internal fun bookmarkPreviewMessage(message: Message): Message = message.copy(
    type = when (message.type) {
        MessageTypes.REPLY, MessageTypes.CHAT_INPUT_COMMAND, MessageTypes.CONTEXT_MENU_COMMAND -> MessageTypes.DEFAULT
        else -> message.type
    },
    referencedMessage = null, messageReference = message.messageReference?.takeIf { it.type == 1 },
    interaction = null, interactionMetadata = null, activity = null, thread = null, components = null,
)

private fun jumpToBookmark(context: Context, saved: SavedMessage) {
    val data = saved.saveData
    val channel = StoreStream.getChannels().getChannel(data.channelId)
    if (!canViewBookmark(saved, channel, StoreStream.getPermissions().getPermissionsByChannel()[data.channelId])) return
    if (IntentUtils.consumeExternalRoutingIntent(
        IntentUtils.RouteBuilders.selectChannel(data.channelId, data.guildId ?: 0L, data.messageId), context,
    )) {
        context.startActivity(Intent(context, AppActivity.Main::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

// RN falls back to ChannelTypes.UNKNOWN (10000) when a saved message outlives its cached channel.
internal fun bookmarkPreviewChannel(saved: SavedMessage, name: String) = Channel(
    id = saved.saveData.channelId, guildId = saved.saveData.guildId ?: 0L, type = 10000, name = name,
    topic = null, lastMessageId = 0, ownerId = 0, recipientIds = null, recipients = null,
    position = 0, permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
    originChannelId = 0, applicationId = 0, nicks = null, nsfw = false, parentId = 0,
    memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null, rtcRegion = null,
    flags = null, guildHashes = null, availableTags = null, appliedTags = null, threadMetadata = null,
    messageCount = null, memberCount = null, memberIdsPreview = null, member = null,
)
