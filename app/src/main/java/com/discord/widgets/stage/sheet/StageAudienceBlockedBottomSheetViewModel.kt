package com.discord.widgets.stage.sheet

import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserRelationships
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.stage.StageStoreAccess
import com.discord.widgets.voice.model.CallModel
import rx.Observable

/**
 * Backs the "people you blocked are in this Stage" confirmation.
 *
 * Only *connected* blocked accounts count — someone blocked who merely has permission to speak but
 * is not in the room is irrelevant to the decision being made.
 */
class StageAudienceBlockedBottomSheetViewModel(
    private val channelId: Long,
    storeStateObservable: Observable<StoreState> = observeStores(channelId),
) : AppViewModel<StageAudienceBlockedBottomSheetViewModel.ViewState>() {

    /** Wire value of the "blocked" relationship type. */
    private companion object {
        const val RELATIONSHIP_TYPE_BLOCKED = 2

        fun observeStores(
            channelId: Long,
            userRelationshipsStore: StoreUserRelationships = StoreStream.getUserRelationships(),
            stageChannelsStore: StoreStageChannels = StageStoreAccess.requireStageChannels(),
        ): Observable<StoreState> = Observable.combineLatest(
            CallModel.get(channelId),
            userRelationshipsStore.observeForType(RELATIONSHIP_TYPE_BLOCKED),
            stageChannelsStore.observeStageRolesByChannel(channelId),
        ) { callModel, blocked, stageRoles ->
            StoreState(channelId, callModel, blocked.keys, stageRoles.orEmpty())
        }
    }

    data class StoreState(
        val channelId: Long,
        val callModel: CallModel?,
        val blockedUsers: Set<Long>,
        val stageRoles: Map<Long, StageRoles>,
    )

    sealed class ViewState {
        /** The channel went away underneath the sheet; the sheet dismisses itself. */
        object Invalid : ViewState()

        data class Loaded(
            val channel: Channel,
            val listItems: List<StageAudienceBlockedBottomSheetAdapter.ListItem>,
        ) : ViewState()
    }

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(StageAudienceBlockedBottomSheetViewModel::class.java) {
                handleStoreState(it)
            }
    }

    private fun handleStoreState(storeState: StoreState) {
        val callModel = storeState.callModel
        if (callModel == null) {
            updateViewState(ViewState.Invalid)
            return
        }

        val blockedParticipants = callModel.participants
            .filter { (userId, voiceUser) ->
                voiceUser.isConnected && storeState.blockedUsers.contains(userId)
            }
            .map { (userId, voiceUser) ->
                StageAudienceBlockedBottomSheetAdapter.ListItem.Participant(
                    participant = voiceUser,
                    isSpeaker = storeState.stageRoles[userId]?.isSpeaker == true,
                )
            }

        updateViewState(
            ViewState.Loaded(
                channel = callModel.channel,
                listItems = buildList {
                    add(
                        StageAudienceBlockedBottomSheetAdapter.ListItem
                            .Header(blockedParticipants.size),
                    )
                    addAll(blockedParticipants)
                },
            ),
        )
    }
}
