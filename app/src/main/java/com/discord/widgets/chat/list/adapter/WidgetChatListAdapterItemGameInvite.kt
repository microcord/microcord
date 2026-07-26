package com.discord.widgets.chat.list.adapter

import com.discord.R
import com.discord.api.activity.Activity
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
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

    /**
     * Builds the card's model from the invite plus the author's live presence.
     *
     * NOTE: the reference also folds in `StoreGameParty.observeUsersForPartyId`, which supplies the
     * avatar pile of everyone already in the party (and therefore whether the viewer has joined).
     * `StoreGameParty` exists in this project but is not reachable from `StoreStream`, so the pile
     * renders empty until it is exposed. Everything else — liveness, join eligibility, the cover art
     * and the button state — is driven by real data.
     */
    private fun observeModel(entry: GameInviteEntry): Observable<ViewEmbedGameInvite.Model> =
        Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getPresences().observeApplicationActivity(entry.authorId, entry.application.id),
        ) { me: MeUser, activity: Activity? ->
            ViewEmbedGameInvite.Model(
                meUser = me,
                creatorId = entry.authorId,
                creationTime = TimeUtils.parseSnowflake(entry.messageId),
                application = entry.application,
                messageActivity = entry.activity,
                activity = activity,
                users = emptyList(),
            )
        }.computationLatest().distinctUntilChanged()
}
