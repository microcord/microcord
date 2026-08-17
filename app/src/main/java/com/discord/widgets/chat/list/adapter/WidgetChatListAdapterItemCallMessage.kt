package com.discord.widgets.chat.list.adapter

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.views.ThemedColor
import com.discord.widgets.channels.list.items.CollapsedUser
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.views.VitoImageView
import rx.Observable
import rx.Subscription
import java.util.concurrent.TimeUnit

/**
 * The card a voice call leaves behind in a DM.
 *
 * A call message is unusual among chat rows in that it is *live*: while the call is still running
 * the card counts up in real time and lists whoever is currently in it, so it re-reads the voice
 * store rather than rendering the message it was built from. That subscription is torn down
 * whenever the row leaves the window or the screen goes away, which is what
 * [FragmentLifecycleListener] is for.
 */
class WidgetChatListAdapterItemCallMessage(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_call, adapter), FragmentLifecycleListener {

    private val icon: ImageView = itemView.findViewById(R.id.chat_list_adapter_item_call_icon)
    private val title: TextView = itemView.findViewById(R.id.chat_list_adapter_item_call_title)
    private val subtitle: TextView = itemView.findViewById(R.id.chat_list_adapter_item_call_subtitle)
    private val unjoinedOngoingSubtitle: View =
        itemView.findViewById(R.id.chat_list_adapter_item_call_unjoined_ongoing_subtitle)
    private val unjoinedCallDuration: TextView =
        itemView.findViewById(R.id.chat_list_adapter_item_unjoined_call_duration)
    private val participants: RecyclerView =
        itemView.findViewById(R.id.chat_list_adapter_item_call_participants)

    private val participantsAdapter = ParticipantsAdapter()
    private var chatListEntry: ChatListEntry? = null
    private var stateSubscription: Subscription? = null
    private var ongoingCallDurationSubscription: Subscription? = null

    init {
        participants.layoutManager =
            LinearLayoutManager(participants.context, RecyclerView.HORIZONTAL, false)
        participants.adapter = participantsAdapter
        participants.setHasFixedSize(false)
        itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = resetCurrentChatListEntry()
            override fun onViewDetachedFromWindow(v: View) = clearSubscriptions()
        })
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        configure(data)
    }

    override fun onPause() = clearSubscriptions()

    override fun onResume() = resetCurrentChatListEntry()

    private fun resetCurrentChatListEntry() {
        chatListEntry?.let(::configure)
    }

    private fun configure(data: ChatListEntry) {
        chatListEntry = data
        val messageEntry = data as MessageEntry
        clearSubscriptions()
        voiceParticipants(messageEntry.message.channelId)
            .map { State(it, messageEntry) }
            .computationLatest()
            .ui()
            .appSubscribe(
                WidgetChatListAdapterItemCallMessage::class.java,
                subscriptionHandler = { stateSubscription = it },
                onNext = ::handleState,
            )
    }

    private fun clearSubscriptions() {
        ongoingCallDurationSubscription?.unsubscribe()
        stateSubscription?.unsubscribe()
    }

    private fun handleState(state: State) {
        val message = state.messageEntry.message
        val callStatus = getCallStatus(message, state.voiceParticipants)
        val author = message.author ?: return

        configureSubtitle(state.messageEntry, callStatus)
        icon.setImageDrawable(getCallDrawable(callStatus))
        title.setText(getTitleString(callStatus))
        itemView.setOnClickListener {
            adapter.eventHandler.onCallMessageClicked(message.channelId, callStatus)
        }
        participantsAdapter.setData(
            createCallParticipantUsers(state.voiceParticipants, callStatus, CoreUser(author)),
        )
    }

    /**
     * A call is active until the server stamps it ended; "joined" means the viewer is one of the
     * people currently connected, or — once it is over — that they took part at all.
     */
    private fun getCallStatus(
        message: Message,
        voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
    ): CallStatus {
        val call = message.call ?: return CallStatus.INACTIVE_UNJOINED
        val myUserId = adapter.data.userId
        val connectedUserIds = voiceParticipants.filterValues { it.voiceState != null }.keys
        val isActive = call.endedTimestamp == null
        return when {
            isActive && connectedUserIds.contains(myUserId) -> CallStatus.ACTIVE_JOINED
            isActive -> CallStatus.ACTIVE_UNJOINED
            call.participants.contains(myUserId) -> CallStatus.INACTIVE_JOINED
            else -> CallStatus.INACTIVE_UNJOINED
        }
    }

    private fun configureSubtitle(messageEntry: MessageEntry, callStatus: CallStatus) {
        ongoingCallDurationSubscription?.unsubscribe()
        val message = messageEntry.message
        val startedAtMs = message.timestamp?.dateTimeMillis ?: 0L

        // While the call is running and the viewer is not in it, the duration lives in its own row
        // beside a "join" prompt rather than as the plain subtitle.
        val isUnjoinedOngoing = callStatus == CallStatus.ACTIVE_UNJOINED
        unjoinedOngoingSubtitle.visibility = if (isUnjoinedOngoing) View.VISIBLE else View.GONE
        subtitle.visibility = if (isUnjoinedOngoing) View.GONE else View.VISIBLE

        when (callStatus) {
            CallStatus.ACTIVE_UNJOINED, CallStatus.ACTIVE_JOINED -> {
                Observable.interval(0L, 1L, TimeUnit.SECONDS).ui().appSubscribe(
                    WidgetChatListAdapterItemCallMessage::class.java,
                    subscriptionHandler = { ongoingCallDurationSubscription = it },
                ) {
                    val elapsed = TimeUtils
                        .toFriendlyString(startedAtMs, adapter.clock.currentTimeMillis())
                        .toString()
                    subtitle.text = elapsed
                    unjoinedCallDuration.text = elapsed
                    // Digits are not the same width as colons, so the row would jitter every second
                    // without a floor computed from the string that is about to be shown.
                    unjoinedCallDuration.minWidth =
                        unjoinedCallDuration.paddingStart + minWidthPxForTime(elapsed)
                }
            }
            CallStatus.INACTIVE_JOINED, CallStatus.INACTIVE_UNJOINED -> {
                subtitle.text = subtitle.i18nFormat(
                    R.string.call_ended_description,
                    "duration" to TimeUtils.toFriendlyStringSimple(message.getCallDuration()),
                    "timestamp" to TimeUtils
                        .toReadableTimeString(itemView.context, startedAtMs, adapter.clock),
                )
            }
        }
    }

    /**
     * Once the call is over there is nobody connected to show, so the card falls back to the person
     * who started it. While it is running the pile shows who is actually in the call.
     */
    private fun createCallParticipantUsers(
        voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
        callStatus: CallStatus,
        messageAuthor: com.discord.models.user.User,
    ): List<CollapsedUser> {
        if (callStatus == CallStatus.INACTIVE_JOINED || callStatus == CallStatus.INACTIVE_UNJOINED) {
            return listOf(CollapsedUser(messageAuthor))
        }
        val connected = voiceParticipants.values.filter { it.voiceState != null }
        val shown = connected.take(MAX_USERS_SHOWN).map { CollapsedUser(it.user) }
        val remaining = connected.size - MAX_USERS_SHOWN
        return if (remaining > 0) shown + CollapsedUser.createEmptyUser(remaining.toLong()) else shown
    }

    private fun getCallDrawable(callStatus: CallStatus): Drawable? {
        val context = itemView.context
        val (drawableRes, color) = when (callStatus) {
            CallStatus.ACTIVE_UNJOINED, CallStatus.ACTIVE_JOINED ->
                R.drawable.ic_call_24dp to ContextCompat.getColor(context, R.color.status_green_600)
            CallStatus.INACTIVE_UNJOINED ->
                R.drawable.ic_call_disconnect_24dp to ContextCompat.getColor(context, R.color.status_red)
            CallStatus.INACTIVE_JOINED ->
                R.drawable.ic_call_24dp to ThemedColor.resolve(context, R.attr.colorInteractiveNormal)
        }
        val drawable = AppCompatResources.getDrawable(context, drawableRes) ?: return null
        val tinted = DrawableCompat.wrap(drawable).mutate()
        DrawableCompat.setTint(tinted, color)
        return tinted
    }

    private fun getTitleString(callStatus: CallStatus): Int = when (callStatus) {
        CallStatus.ACTIVE_UNJOINED, CallStatus.ACTIVE_JOINED -> R.string.ongoing_call
        CallStatus.INACTIVE_UNJOINED -> R.string.missed_call
        CallStatus.INACTIVE_JOINED -> R.string.call_ended
    }

    /** Colons are narrower than digits; the two are measured separately. */
    private fun minWidthPxForTime(time: String): Int {
        val colons = time.count { it == ':' }
        val density = itemView.resources.displayMetrics.density
        return ((colons * COLON_WIDTH_DP + (time.length - colons) * DIGIT_WIDTH_DP) * density).toInt()
    }

    /** Where the call card is in its life, from the viewer's point of view. */
    enum class CallStatus {
        ACTIVE_UNJOINED,
        ACTIVE_JOINED,
        INACTIVE_UNJOINED,
        INACTIVE_JOINED,
    }

    private data class State(
        val voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
        val messageEntry: MessageEntry,
    )

    private inner class ParticipantsAdapter : RecyclerView.Adapter<ParticipantViewHolder>() {
        private var users: List<CollapsedUser> = emptyList()

        fun setData(users: List<CollapsedUser>) {
            this.users = users
            notifyDataSetChanged()
        }

        override fun getItemCount() = users.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ParticipantViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_collapsed_voice_user_list_item, parent, false),
        )

        override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) =
            holder.bind(users[position])
    }

    private class ParticipantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: VitoImageView = itemView.findViewById(R.id.widget_collapsed_user_imageview)
        private val count: TextView = itemView.findViewById(R.id.widget_collapsed_user_count)

        fun bind(item: CollapsedUser) {
            if (item.emptySlot) {
                avatar.visibility = View.INVISIBLE
                count.visibility = View.VISIBLE
                count.text = "+${item.extraCount}"
            } else {
                avatar.visibility = View.VISIBLE
                count.visibility = View.GONE
                IconUtils.setIcon(avatar, item.user, R.dimen.avatar_size_medium)
            }
        }
    }

    companion object {
        /** The pile shows at most three faces before collapsing the rest into a "+N" bubble. */
        private const val MAX_USERS_SHOWN: Int = 3

        /**
         * Who is in the call. Reconstruction seam: `StoreVoiceParticipants` exists but is not
         * published by `StoreStream`, so until something installs a real source every call card
         * renders as if nobody were connected — which is also the correct rendering for a call that
         * has already ended. See `docs/parity-audit/followups.md`.
         */
        @JvmStatic
        var voiceParticipants: (Long) -> Observable<Map<Long, StoreVoiceParticipants.VoiceUser>> =
            { Observable.just(emptyMap()) }
    }
}

/** Measured from the reference's own layout constants. */
private const val COLON_WIDTH_DP = 3.047619f
private const val DIGIT_WIDTH_DP = 7.6190476f
