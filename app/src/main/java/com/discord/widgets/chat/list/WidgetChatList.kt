package com.discord.widgets.chat.list

import android.os.Bundle
import android.view.View
import androidx.core.view.ancestors
import androidx.core.view.doOnAttach
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.forums.WidgetForumPostTagsSheet
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreMessagesLoader
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.WidgetUrlActions
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemCallMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.model.WidgetChatListModel
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages

/** Owns the live message adapter and consumes the reference chat-list model. */
class WidgetChatList : AppFragment(R.layout.widget_chat_list), WidgetChatListActions.Host {

    interface Host {
        fun openCallMessage(channelId: Long, status: WidgetChatListAdapterItemCallMessage.CallStatus)
    }

    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels {
        AppFlexInputViewModel()
    }

    private var adapter: WidgetChatListAdapter? = null
    private var recycler: RecyclerView? = null
    private var defaultItemAnimator: RecyclerView.ItemAnimator? = null
    private var panels: OverlappingPanelsLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val list = view.findViewById<RecyclerView>(R.id.chat_list_recycler)
        recycler = list
        val existing = adapter
        if (existing == null) createAdapter(list) else configureExistingAdapter(existing, list)
        attachSwipeToReply(list)
        // The picker is shown from a chat row, so its result lands here rather than in the row.
        WidgetForumPostTagsSheet.registerForResult(this) { tags ->
            val draft = StoreStream.getThreadDraft()
            draft.setDraftState(draft.getDraftState().copy(appliedTags = tags))
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        adapter?.setHandlers()
        adapter?.onResume()
        WidgetChatListModel.get().ui(this)
            .appSubscribe(WidgetChatList::class.java, onNext = ::configureUI)
        StoreStream.getMessagesLoader().observeScrollTo().ui(this)
            .appSubscribe(WidgetChatList::class.java, onNext = ::scrollTo)
        defaultItemAnimator = recycler?.itemAnimator
    }

    override fun onPause() {
        super.onPause()
        adapter?.onPause()
        adapter?.disposeHandlers()
    }

    override fun onDestroyView() {
        panels?.childClaimsLeftSwipe = null
        panels = null
        recycler?.layoutManager = null
        recycler?.adapter = null
        adapter?.dispose()
        recycler = null
        super.onDestroyView()
    }

    private fun attachSwipeToReply(list: RecyclerView) {
        val swipe = SwipeToReply(requireContext(), messageAt = { holder -> messageAt(holder.bindingAdapterPosition) }) { message ->
            val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return@SwipeToReply
            messageActionCoordinator().reply(channel, message, StoreStream.getUsers().getMe().id)
        }
        ItemTouchHelper(swipe).attachToRecyclerView(list)
        // The home panels would take a leftward swipe to open the member list; one that starts on a
        // message replies instead. They are only reachable once the list is attached.
        list.doOnAttach { panels = it.ancestors.filterIsInstance<OverlappingPanelsLayout>().firstOrNull()?.also(::claimLeftSwipes) }
    }

    private fun claimLeftSwipes(panels: OverlappingPanelsLayout) {
        val list = recycler ?: return
        panels.childClaimsLeftSwipe = { event ->
            val location = IntArray(2)
            list.getLocationOnScreen(location)
            list.findChildViewUnder(event.rawX - location[0], event.rawY - location[1])
                ?.let { list.getChildViewHolder(it) }
                ?.let { holder -> messageAt(holder.bindingAdapterPosition) }
                ?.let(SwipeToReply::canReply) == true
        }
    }

    /** The message a chat row belongs to; null for rows that are not part of one. */
    private fun messageAt(position: Int): Message? {
        val entry = adapter?.takeIf { position in 0 until it.itemCount }?.getItem(position) ?: return null
        return when (entry) {
            is MessageEntry -> entry.message
            is AttachmentEntry -> entry.message
            is EmbedEntry -> entry.message
            is StickerEntry -> entry.message
            else -> null
        }
    }

    fun disableItemAnimations() {
        recycler?.itemAnimator = null
    }

    fun enableItemAnimations() {
        recycler?.itemAnimator = defaultItemAnimator
    }

    fun jumpToMessage(messageId: Long) {
        val channelId = adapter?.data?.channelId ?: return
        if (channelId > 0L && messageId > 0L) {
            StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
        }
    }

    fun scrollTo(messageId: Long) {
        val current = adapter
        if (current == null) StoreStream.getMessagesLoader().clearScrollTo()
        else current.scrollToMessageId(messageId) { StoreStream.getMessagesLoader().clearScrollTo() }
    }

    private fun createAdapter(recycler: RecyclerView) {
        val coordinator = requireNotNull(actionCoordinator) { "Chat message actions are not initialized" }
        val eventHandler = WidgetChatListAdapterEventsHandler(
            host = this,
            flexInputViewModel = flexInputViewModel,
            messageActionCoordinator = coordinator,
            openPins = { channelId -> WidgetChannelPinnedMessages.show(requireContext(), channelId) },
            openCall = { channelId, status -> requireChatHost().openCallMessage(channelId, status) },
            openUrlActions = { url -> WidgetUrlActions.launch(parentFragmentManager, url) },
        )
        val created = MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(
            WidgetChatListAdapter(recycler, this, parentFragmentManager, eventHandler),
        )
        recycler.addItemDecoration(ThreadSpineItemDecoration(requireContext(), created))
        created.layoutManager?.reverseLayout = false
        created.layoutManager?.stackFromEnd = true
        created.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        adapter = created
    }

    private fun configureExistingAdapter(adapter: WidgetChatListAdapter, recycler: RecyclerView) {
        adapter.fragmentManager = parentFragmentManager
        adapter.recycler = recycler
        MGRecyclerAdapter.configure<ChatListEntry, WidgetChatListAdapter>(adapter)
        adapter.layoutManager?.reverseLayout = false
        adapter.layoutManager?.stackFromEnd = true
    }

    private fun configureUI(model: WidgetChatListModel?) {
        recycler?.visibility = if (model == null) View.GONE else View.VISIBLE
        if (model != null) adapter?.data = model
    }

    private fun requireChatHost(): Host = (parentFragment as? Host) ?: (activity as? Host)
        ?: error("WidgetChatList host must implement Host")

    override fun messageActionCoordinator(): MessageActionCoordinator = requireNotNull(actionCoordinator)

    override fun messageForAction(channelId: Long, messageId: Long): Message? =
        StoreStream.getMessages().getMessage(channelId, messageId)

    override fun channelForAction(channelId: Long): Channel? =
        StoreStream.getChannels().getChannel(channelId)

    override fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator) {
        StoreStream.getChatInputEditing().start(message)
    }

    override fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator) {
        WidgetEmojiPickerSheet.show(childFragmentManager) { emoji -> coordinator.toggleReaction(message, emoji) }
    }

    companion object {
        @Volatile var actionCoordinator: MessageActionCoordinator? = null

        /** Kept only for the existing composition-root call; models now consume StoreStream directly. */
        @Suppress("UNUSED_PARAMETER")
        fun installStores(messages: StoreMessages, loader: StoreMessagesLoader) = Unit
    }
}
