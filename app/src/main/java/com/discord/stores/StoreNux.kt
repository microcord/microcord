package com.discord.stores

import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The new-user-experience flags: one-shot hints and post-registration routing.
 *
 * Deliberately in-memory and never persisted — every flag describes something about *this* run
 * (we just registered, we just finished contact sync, we are about to show the boost hint for this
 * guild). Persisting them would resurrect a first-run flow on the next launch.
 *
 * A plain [Store], not a [StoreV2]: the state is one small value published on a subject, so there
 * is no snapshot/observation-deck machinery to justify.
 */
class StoreNux(private val dispatcher: Dispatcher) : Store() {

    /**
     * @param postRegister the user just created an account and has not been routed onward yet.
     * @param postRegisterWithInvite same, but the registration came from an invite link, so the
     *   invite should be joined rather than the "add a server" flow shown.
     * @param contactSyncCompleted contact sync finished during this run.
     * @param firstOpen this is the first time the app has been opened after install.
     * @param addGuildHint show the "add a server" hint on the guild list.
     * @param guildBoostHintGuildId the guild whose boost hint should be shown, or `null`.
     */
    data class NuxState(
        val postRegister: Boolean = false,
        val postRegisterWithInvite: Boolean = false,
        val contactSyncCompleted: Boolean = false,
        val firstOpen: Boolean = false,
        val addGuildHint: Boolean = false,
        val guildBoostHintGuildId: Long? = null,
    )

    private var nuxState = NuxState()
    private val nuxStateSubject = BehaviorSubject.create(nuxState)

    fun getNuxState(): Observable<NuxState> = nuxStateSubject.distinctUntilChanged()

    fun setPostRegister(value: Boolean) = updateNux { it.copy(postRegister = value) }

    fun setPostRegisterWithInvite(value: Boolean) = updateNux { it.copy(postRegisterWithInvite = value) }

    fun setContactSyncCompleted(value: Boolean) = updateNux { it.copy(contactSyncCompleted = value) }

    fun setFirstOpen(value: Boolean) = updateNux { it.copy(firstOpen = value) }

    fun setPremiumGuildHintGuildId(value: Long?) = updateNux { it.copy(guildBoostHintGuildId = value) }

    fun updateNux(updateFunction: (NuxState) -> NuxState) = dispatcher.schedule {
        nuxState = updateFunction(nuxState)
        publishNuxUpdated(nuxState)
    }

    fun clearNux() = dispatcher.schedule {
        nuxState = NuxState()
        publishNuxUpdated(nuxState)
    }

    /**
     * Selecting a different guild retires a pending boost hint, because the hint points at a
     * specific guild's boost bar. Selecting the *same* guild leaves it alone so the hint survives
     * a re-selection.
     */
    @StoreThread
    fun handleGuildSelected(guildId: Long) {
        if (nuxState.guildBoostHintGuildId == guildId) return
        nuxState = nuxState.copy(guildBoostHintGuildId = null)
        publishNuxUpdated(nuxState)
    }

    @StoreThread
    fun handleSamplePremiumGuildSelected(guildId: Long) {
        nuxState = nuxState.copy(guildBoostHintGuildId = guildId)
        publishNuxUpdated(nuxState)
    }

    private fun publishNuxUpdated(nuxState: NuxState) = nuxStateSubject.onNext(nuxState)
}
