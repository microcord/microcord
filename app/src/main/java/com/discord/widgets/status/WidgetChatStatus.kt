package com.discord.widgets.status

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import com.discord.R
import com.discord.app.AppFragment
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.application.Unread
import com.discord.stores.StoreMessageAck
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * The "N new messages since <date>" banner pinned above the chat list, with its jump and
 * mark-as-read affordances.
 *
 * The banner only appears while the unread marker is still *valid*: the channel's last ack has to
 * be older than the newest message the client knows about. Once the user catches up, that validity
 * stream completes, so the banner stays hidden until a different channel is selected — which is
 * what stops it from flickering back in while the user is reading.
 */
class WidgetChatStatus : AppFragment(R.layout.widget_chat_status) {

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.get()
            .ui(this)
            .appSubscribe(WidgetChatStatus::class.java) { configureUI(it) }
    }

    @MainThread
    private fun configureUI(data: Model) {
        val view = view ?: return
        view.findViewById<LinearLayout>(R.id.chat_status_unread_messages).apply {
            visibility = if (data.unreadVisible) View.VISIBLE else View.GONE
            setOnClickListener {
                StoreStream.getMessagesLoader().jumpToMessage(data.unreadChannelId, data.unreadMessageId)
            }
        }
        view.findViewById<TextView>(R.id.chat_status_unread_messages_text).text =
            getUnreadMessageText(data.isUnreadEstimate, data.unreadCount, data.unreadMessageId)
        view.findViewById<View>(R.id.chat_status_unread_messages_mark).setOnClickListener {
            StoreStream.getReadStates().markAsRead(data.unreadChannelId)
        }
    }

    private fun getUnreadMessageText(isEstimate: Boolean, count: Int, messageId: Long): CharSequence {
        val context = requireContext()
        val timestamp = TimeUtils.renderUtcDate(TimeUtils.parseSnowflake(messageId), context)
        return if (isEstimate) {
            context.i18nFormat(
                R.string.new_messages_estimated,
                "count" to count.toString(),
                "timestamp" to timestamp,
            )
        } else {
            val countPlural = context.i18nPlural(R.plurals.new_messages_count, count, "count" to count)
            context.i18nFormat(R.string.new_messages, "count" to countPlural, "timestamp" to timestamp)
        }
    }

    internal data class Model(
        val unreadVisible: Boolean,
        val unreadMessageId: Long,
        val unreadChannelId: Long,
        val unreadCount: Int,
        val isUnreadEstimate: Boolean,
    ) {
        companion object {
            /** Cap on the rendered count. A channel whose history is detached is capped lower. */
            const val ATTACHED_COUNT_CAP = 50
            const val DETACHED_COUNT_CAP = 25

            /** Debounce applied to both halves of the ack-validity comparison. */
            private const val ACK_VALIDITY_DEBOUNCE_MS = 200L

            /** The banner may not re-render more than once per this window. */
            private const val EMIT_THROTTLE_MS = 500L

            fun get(): Observable<Model> {
                val unreadValid = StoreStream.getChannelsSelected().observeId()
                    .distinctUntilChanged()
                    .switchMap { selectedChannelId -> observeUnreadValid(selectedChannelId) }
                    .distinctUntilChanged()

                return Observable.combineLatest(
                    unreadValid,
                    StoreStream.getMessages().getAllDetached(),
                    StoreStream.getReadStates().getUnreadMarkerForSelectedChannel(),
                ) { isUnreadValid, detachedChannels, unread ->
                    createModel(isUnreadValid, detachedChannels, unread)
                }
                    .leadingEdgeThrottle(EMIT_THROTTLE_MS, TimeUnit.MILLISECONDS)
                    .computationLatest()
                    .distinctUntilChanged()
            }

            /**
             * `false` until the client can prove the channel holds messages newer than its last
             * ack, then `true` while that holds. The stream completes on the first `false` after
             * that point; a new channel selection resubscribes it.
             *
             * The validity check and unread projection must read the same authoritative ledger.
             */
            private fun observeUnreadValid(channelId: Long): Observable<Boolean> {
                val lastAckOlderThanMostRecent = Observable.combineLatest(
                    StoreStream.getMessageAck().observeForChannel(channelId)
                        .debounce(ACK_VALIDITY_DEBOUNCE_MS, TimeUnit.MILLISECONDS),
                    StoreStream.getMessagesMostRecent().observeRecentMessageIds()
                        .map { mostRecentIds -> mostRecentIds[channelId] ?: 0L }
                        .debounce(ACK_VALIDITY_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                        .take(1),
                ) { ack: StoreMessageAck.Ack?, mostRecentIdSnapshot: Long ->
                    (ack?.messageId ?: 0L) < mostRecentIdSnapshot
                }
                    .takeUntil { isValid -> !isValid }

                return Observable.concat(Observable.just(false), lastAckOlderThanMostRecent)
            }

            fun createModel(
                isUnreadValid: Boolean,
                detachedChannels: Set<Long>,
                unread: Unread,
            ): Model {
                val marker = unread.marker
                val cap = if (detachedChannels.contains(marker.channelId)) DETACHED_COUNT_CAP else ATTACHED_COUNT_CAP
                return Model(
                    unreadVisible = isUnreadValid && unread.count > 0 && marker.channelId > 0,
                    unreadMessageId = marker.messageId ?: 0L,
                    unreadChannelId = marker.channelId,
                    unreadCount = unread.count.coerceIn(0, cap),
                    isUnreadEstimate = unread.count >= cap,
                )
            }
        }
    }
}
