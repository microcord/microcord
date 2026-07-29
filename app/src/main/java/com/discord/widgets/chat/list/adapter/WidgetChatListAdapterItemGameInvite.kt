package com.discord.widgets.chat.list.adapter

import com.discord.R
import com.discord.api.activity.Activity
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.widgets.channels.list.items.CollapsedUser
import com.discord.widgets.chat.list.ViewEmbedGameInvite
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GameInviteEntry
import rx.Observable
import rx.Subscription

/**
 * A rich-presence game invite ("Join", "Spectate", "Ask to Join").
 *
 * The card itself is [ViewEmbedGameInvite]; this row's job is to keep it fed. The invite is only
 * live while the author is still in the party it names, so the row tracks the author's presence and
 * re-binds whenever their activity changes — an invite whose party has ended must visibly go dead
 * rather than stay tappable.
 *
 * The action button reports through the adapter rather than acting directly, because joining a party
 * is an account-level action the hosting screen owns.
 */
class WidgetChatListAdapterItemGameInvite(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_game_invite, adapter) {

    private val gameInviteView: ViewEmbedGameInvite = itemView.findViewById(R.id.item_game_invite)

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as GameInviteEntry

        gameInviteView.onActionButtonClick = { _, model ->
            model.activity?.let { activity ->
                adapter.onUserActivityAction(
                    entry.authorId,
                    entry.messageId,
                    entry.activity.type,
                    activity,
                    entry.application,
                )
            }
        }

        observeModel(entry).ui().appSubscribe(
            errorClass = WidgetChatListAdapterItemGameInvite::class.java,
            subscriptionHandler = { modelSubscription = it },
        ) { model -> gameInviteView.bind(model, adapter.clock) }
    }

    /** Builds the card's model from the invite, the author's live presence, and party members. */
    private fun observeModel(entry: GameInviteEntry): Observable<ViewEmbedGameInvite.Model> =
        Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getPresences().observeApplicationActivity(entry.authorId, entry.application.id),
            StoreStream.getGameParty().observeUsersForPartyId(entry.activity.partyId),
        ) { me: MeUser, activity: Activity?, users ->
            ViewEmbedGameInvite.Model(
                meUser = me,
                creatorId = entry.authorId,
                creationTime = TimeUtils.parseSnowflake(entry.messageId),
                application = entry.application,
                messageActivity = entry.activity,
                activity = activity,
                users = createPartyUsers(users, activity),
            )
        }.computationLatest().distinctUntilChanged()

    private fun createPartyUsers(
        users: Map<Long, User>,
        activity: Activity?,
    ): List<CollapsedUser> {
        val partyUsers = users.values.mapTo(ArrayList()) { CollapsedUser(it) }
        val maxSize = with(PresenceUtils) { activity?.party?.maxSize } ?: 0L
        for (index in users.size until minOf(MAX_VISIBLE_USERS.toLong(), maxSize).toInt()) {
            partyUsers += CollapsedUser.createEmptyUser(
                if (index == MAX_VISIBLE_USERS - 1) maxSize - MAX_VISIBLE_USERS else 0,
            )
        }
        return partyUsers
    }

    private companion object {
        const val MAX_VISIBLE_USERS = 4
    }
}
