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

    /**
     * @property isMember whether the user is actually in the guild. A guild that screens members
     *   manually admits nobody until approval, so withdrawing there deletes an application, while
     *   in a rules-gated guild the user is already a pending *member* and has a server to leave.
     */
    data class StoreState(
        val isFormOutdated: Boolean,
        val isMember: Boolean,
        val isPendingMember: Boolean,
        val applicationStatus: ApplicationStatus,
        val rejectionReason: String?,
    )

    sealed class ViewState {
        data class Loaded(
            val dialogState: DialogState,
            val showPendingImage: Boolean,
            val showTertiaryButton: Boolean,
            val isMember: Boolean,
            val isPendingMember: Boolean,
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
                // Only a member has a server to leave, so only they get the third button.
                showTertiaryButton = pending && loaded.isMember,
                rejectionReason = null,
            ),
        )
    }

    fun resetGuildJoinRequest() = request(restAPI.resetGuildJoinRequest(guildId))

    fun deleteGuildJoinRequest() = request(restAPI.deleteGuildJoinRequest(guildId))

    /**
     * Clears a rejected application so a fresh one can be started.
     *
     * A pending member's application is *deleted*, because the membership it hangs off still
     * exists; an applicant who never became a member has theirs *restarted* instead. Sending the
     * wrong one leaves the old rejection in place and the new form refuses to submit.
     */
    fun reapply() {
        val loaded = viewState as? ViewState.Loaded
        if (loaded?.isPendingMember == true) deleteGuildJoinRequest() else resetGuildJoinRequest()
    }

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
                ApplicationStatus.PENDING -> DialogState.PENDING
                ApplicationStatus.REJECTED -> DialogState.REJECTED
                else -> null
            }
            val state = if (storeState.isFormOutdated) DialogState.UPGRADE else resolvedState ?: return null
            return ViewState.Loaded(
                dialogState = state,
                showPendingImage = true,
                showTertiaryButton = state == DialogState.PENDING && storeState.isMember,
                isMember = storeState.isMember,
                isPendingMember = storeState.isPendingMember,
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
                // Presence in the guild store *is* membership: a manual-approval guild never sends
                // a GUILD_CREATE to someone whose application has not been approved.
                isMember = guild != null,
                isPendingMember = StoreStream.getGuilds()
                    .getMember(guildId, StoreStream.getUsers().getMe().id)?.pending == true,
                applicationStatus = joinRequest?.applicationStatus ?: ApplicationStatus.STARTED,
                rejectionReason = joinRequest?.rejectionReason,
            )
        }
    }
}
