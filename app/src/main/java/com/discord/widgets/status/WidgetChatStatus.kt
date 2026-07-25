package com.discord.widgets.status

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.stores.StoreReadStates
import com.discord.stores.StoreStream
import rx.Observable
import rx.Subscription

class WidgetChatStatus : Fragment(R.layout.widget_chat_status) {
    data class State(
        val channelId: Long = 0,
        val messageId: Long = 0,
        val unreadCount: Int = 0,
        val estimated: Boolean = false,
    )

    private var subscription: Subscription? = null
    private var currentState: State? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.chat_status_unread_messages).setOnClickListener {
            currentState?.takeIf { state -> state.channelId > 0 && state.messageId > 0 }?.let {
                onJumpToMessage?.invoke(it.channelId, it.messageId)
            }
        }
        view.findViewById<View>(R.id.chat_status_unread_messages_mark).setOnClickListener {
            currentState?.takeIf { state -> state.channelId > 0 }?.let {
                onMarkRead?.invoke(it.channelId) ?: StoreStream.getReadStates().markAsRead(it.channelId)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: defaultStateSource()).distinctUntilChanged().subscribe { state ->
            view?.post {
                if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(state)
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        currentState = null
        super.onStop()
    }

    private fun show(state: State) {
        currentState = state
        val visible = state.channelId > 0 && state.messageId > 0 && state.unreadCount > 0
        requireView().findViewById<View>(R.id.chat_status_unread_messages).apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            isClickable = onJumpToMessage != null
        }
        if (!visible) return
        val count = state.unreadCount.coerceIn(1, 50)
        val timestamp = DateUtils.getRelativeTimeSpanString(
            (state.messageId ushr 22) + DISCORD_EPOCH,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        val countText = if (state.estimated) count.toString() else
            resources.getQuantityString(R.plurals.new_messages_count, count, count)
        val template = getString(if (state.estimated) R.string.new_messages_estimated else R.string.new_messages)
        requireView().findViewById<TextView>(R.id.chat_status_unread_messages_text).text = template
            .replace("{count}", countText)
            .replace("{timestamp}", timestamp.toString())
    }

    private fun defaultStateSource(): Observable<State> = Observable.combineLatest(
        StoreStream.getChannelsSelected().observeSelectedChannel(),
        StoreStream.getReadStates().getUnreadMarkerForSelectedChannel(),
    ) { channel, unread -> state(channel?.id ?: 0, unread) }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onJumpToMessage: ((Long, Long) -> Unit)? = null
        @JvmField var onMarkRead: ((Long) -> Unit)? = null

        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private fun state(selectedChannelId: Long, unread: StoreReadStates.Unread): State {
            if (unread.marker.channelId != selectedChannelId) return State()
            val count = unread.count.coerceAtLeast(0)
            return State(selectedChannelId, unread.marker.messageId ?: 0, count.coerceAtMost(50), count >= 50)
        }
    }
}
