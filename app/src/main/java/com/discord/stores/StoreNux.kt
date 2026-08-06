package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The new-user-experience flags: one-shot hints and post-registration routing.
 *
 * One-shot legacy hints remain in memory. The current app's new-user type is persisted until the
 * flow completes so onboarding resumes after a reconnect or process restart.
 *
 * A plain [Store], not a [StoreV2]: the state is one small value published on a subject, so there
 * is no snapshot/observation-deck machinery to justify.
 */
class StoreNux(
    private val dispatcher: Dispatcher,
    private val preferences: SharedPreferences? = null,
) : Store() {

    enum class NewUserType(val wireValue: Int) {
        MARKETING_UNCLAIMED(0),
        INVITE_UNCLAIMED(1),
        ORGANIC_REGISTERED(2),
        ORGANIC_REGISTERED_GUILD_TEMPLATE(3),
    }

    enum class NewUserStep(val bit: Int) {
        AVATAR(1),
        NOTIFICATIONS(1 shl 1),
        CONTACT_SYNC(1 shl 2),
        DISCOVERABILITY(1 shl 3),
        INVITE(1 shl 4),
    }

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
        val newUserType: NewUserType? = null,
        val completedNewUserSteps: Set<NewUserStep> = emptySet(),
    )

    private var nuxState = NuxState()
    private val nuxStateSubject = BehaviorSubject.create(nuxState)

    fun getNuxState(): Observable<NuxState> = nuxStateSubject.distinctUntilChanged()

    fun getNuxStateSnapshot(): NuxState = nuxState

    override fun init(context: Context) {
        nuxState = nuxState.copy(
            newUserType = storage.getInt(NEW_USER_TYPE_KEY, NO_NEW_USER_TYPE)
                .takeUnless { it == NO_NEW_USER_TYPE }
                ?.let { value -> NewUserType.entries.firstOrNull { it.wireValue == value } },
            completedNewUserSteps = storage.getInt(COMPLETED_STEPS_KEY, 0).let { mask ->
                NewUserStep.entries.filterTo(mutableSetOf()) { mask and it.bit != 0 }
            },
        )
        publishNuxUpdated(nuxState)
    }

    fun setPostRegister(value: Boolean) = updateNux { it.copy(postRegister = value) }

    fun setPostRegisterWithInvite(value: Boolean) = updateNux { it.copy(postRegisterWithInvite = value) }

    fun setContactSyncCompleted(value: Boolean) = updateNux { it.copy(contactSyncCompleted = value) }

    fun setFirstOpen(value: Boolean) = updateNux { it.copy(firstOpen = value) }

    fun setPremiumGuildHintGuildId(value: Long?) = updateNux { it.copy(guildBoostHintGuildId = value) }

    fun setNewUserType(value: NewUserType?) = dispatcher.schedule {
        storage.edit().apply {
            if (value == null) {
                remove(NEW_USER_TYPE_KEY)
                remove(COMPLETED_STEPS_KEY)
            } else {
                putInt(NEW_USER_TYPE_KEY, value.wireValue)
                putInt(COMPLETED_STEPS_KEY, 0)
            }
        }.apply()
        nuxState = nuxState.copy(newUserType = value, completedNewUserSteps = emptySet())
        publishNuxUpdated(nuxState)
    }

    fun completeNewUserStep(step: NewUserStep) = dispatcher.schedule {
        val completed = nuxState.completedNewUserSteps + step
        storage.edit().putInt(COMPLETED_STEPS_KEY, completed.fold(0) { mask, item -> mask or item.bit }).apply()
        nuxState = nuxState.copy(completedNewUserSteps = completed)
        publishNuxUpdated(nuxState)
    }

    fun updateNux(updateFunction: (NuxState) -> NuxState) = dispatcher.schedule {
        nuxState = updateFunction(nuxState)
        publishNuxUpdated(nuxState)
    }

    fun clearNux() = dispatcher.schedule {
        storage.edit().remove(NEW_USER_TYPE_KEY).remove(COMPLETED_STEPS_KEY).apply()
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

    private val storage: SharedPreferences get() = preferences ?: prefs

    private companion object {
        const val NEW_USER_TYPE_KEY = "CACHE_KEY_NEW_USER_TYPE"
        const val COMPLETED_STEPS_KEY = "CACHE_KEY_NEW_USER_COMPLETED_STEPS"
        const val NO_NEW_USER_TYPE = -1
    }
}
