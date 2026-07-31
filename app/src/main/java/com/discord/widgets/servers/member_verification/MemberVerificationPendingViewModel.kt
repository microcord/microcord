package com.discord.widgets.servers.member_verification

import com.discord.api.guild.GuildFeature
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class MemberVerificationPendingViewModel(
    private val guildId: Long,
    private val dialogState: DialogState? = null,
    private val restAPI: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<MemberVerificationPendingViewModel.ViewState>() {

    enum class DialogState { UPGRADE, PENDING, CANCEL, REJECTED, LEAVE }

    sealed class Event {
        object Error : Event()
        object Success : Event()
    }

    data class StoreState(
        val isFormOutdated: Boolean,
        val isPreviewEnabled: Boolean,
        val applicationStatus: ApplicationStatus,
        val rejectionReason: String?,
    )

    sealed class ViewState {
        data class Loaded(
            val dialogState: DialogState,
            val showPendingImage: Boolean,
            val showTertiaryButton: Boolean,
            val isPreviewEnabled: Boolean,
            val rejectionReason: String?,
        ) : ViewState()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable.computationLatest().ui(this)
            .appSubscribe(MemberVerificationPendingViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun updateDialogState(dialogState: DialogState) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val pending = dialogState == DialogState.PENDING
        updateViewState(
            loaded.copy(
                dialogState = dialogState,
                showPendingImage = pending,
                showTertiaryButton = pending && !loaded.isPreviewEnabled,
                isPreviewEnabled = false,
                rejectionReason = null,
            ),
        )
    }

    fun resetGuildJoinRequest() = request(restAPI.resetGuildJoinRequest(guildId))

    fun deleteGuildJoinRequest() = request(restAPI.deleteGuildJoinRequest(guildId))

    fun leaveGuild() = request(restAPI.leaveGuild(guildId))

    private fun request(request: Observable<*>) {
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = MemberVerificationPendingViewModel::class.java,
            errorHandler = { eventSubject.onNext(Event.Error) },
            onNext = { eventSubject.onNext(Event.Success) },
        )
    }

    private fun handleStoreState(storeState: StoreState) {
        reduce(storeState, dialogState)?.let(::updateViewState)
    }

    companion object {
        internal fun reduce(storeState: StoreState, requestedState: DialogState?): ViewState.Loaded? {
            val resolvedState = requestedState ?: when (storeState.applicationStatus) {
                ApplicationStatus.PENDING -> if (storeState.isPreviewEnabled) DialogState.CANCEL else DialogState.PENDING
                ApplicationStatus.REJECTED -> DialogState.REJECTED
                else -> null
            }
            val state = if (storeState.isFormOutdated) DialogState.UPGRADE else resolvedState ?: return null
            return ViewState.Loaded(
                dialogState = state,
                showPendingImage = true,
                showTertiaryButton = false,
                isPreviewEnabled = storeState.isPreviewEnabled,
                rejectionReason = storeState.rejectionReason,
            )
        }

        private fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuildMemberVerificationForm().observeMemberVerificationFormData(guildId),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuildJoinRequests().observeGuildJoinRequest(guildId),
        ) { formData, guild, joinRequest ->
            StoreState(
                isFormOutdated = formData?.form?.isFormOutdated() == true,
                isPreviewEnabled = guild?.hasFeature(GuildFeature.PREVIEW_ENABLED) == true,
                applicationStatus = joinRequest?.applicationStatus ?: ApplicationStatus.STARTED,
                rejectionReason = joinRequest?.rejectionReason,
            )
        }
    }
}
