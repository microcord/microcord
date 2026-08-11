package com.discord.stores

import com.discord.models.domain.ModelMemberVerificationForm
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The membership-screening rules a community guild requires before letting someone in.
 *
 * One [MemberVerificationFormData] per guild, holding both the fetch state and the form, so a
 * screen can render "loading", "this guild has no rules" and "here are the rules" from a single
 * value. The fetch de-duplicates on [FetchStates.FETCHING] only — a previous failure *or* success
 * is re-fetched, because the rules can be edited by moderators while the user is looking at them.
 */
class StoreGuildMemberVerificationForm(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /** Whether the user is already in the guild, which decides whether to ask for its record. */
    private val isMember: (guildId: Long) -> Boolean = { false },
    /** Hands the partial guild to the store that renders applications for non-members. */
    private val onGuildReceived: (com.discord.api.guild.Guild) -> Unit = {},
) : StoreV2() {

    /**
     * Declaration order is `FETCHING, FAILED, SUCCEEDED` — taken from the `$VALUES` array in the
     * reference's static initialiser, not from the alphabetised order the Kotlin decompiler prints.
     */
    enum class FetchStates { FETCHING, FAILED, SUCCEEDED }

    data class MemberVerificationFormData(
        val fetchState: FetchStates,
        val form: ModelMemberVerificationForm?,
    )

    private val memberVerificationFormState = HashMap<Long, MemberVerificationFormData>()
    private var memberVerificationFormSnapshot: Map<Long, MemberVerificationFormData> = emptyMap()

    fun getMemberVerificationFormData(guildId: Long): MemberVerificationFormData? =
        memberVerificationFormSnapshot[guildId]

    fun observeMemberVerificationFormData(guildId: Long): Observable<MemberVerificationFormData?> =
        observationDeck.connectRx(this) { getMemberVerificationFormData(guildId) }.distinctUntilChanged()

    /**
     * @param inviteCode the invite the user arrived on, when one is known. The server refuses to
     *   serve the form to an applicant it has already rejected unless the invite is presented, so
     *   omitting it makes reapplying impossible.
     */
    @JvmOverloads
    fun fetchMemberVerificationForm(guildId: Long, inviteCode: String? = null) = dispatcher.schedule {
        if (memberVerificationFormState[guildId]?.fetchState == FetchStates.FETCHING) return@schedule
        handleMemberVerificationFormFetchStart(guildId)
        val api = restApi() ?: return@schedule handleMemberVerificationFormFetchFailed(guildId)
        api.getGuildMemberVerificationForm(guildId, withGuild = !isMember(guildId), inviteCode = inviteCode)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildMemberVerificationForm::class.java,
                errorHandler = {
                    dispatcher.schedule { handleMemberVerificationFormFetchFailed(guildId) }
                },
            ) { form ->
                dispatcher.schedule { handleMemberVerificationFormFetchSuccess(guildId, form) }
            }
    }

    @StoreThread
    internal fun handleMemberVerificationFormFetchStart(guildId: Long) {
        memberVerificationFormState[guildId] = MemberVerificationFormData(FetchStates.FETCHING, null)
        markChanged()
    }

    @StoreThread
    internal fun handleMemberVerificationFormFetchFailed(guildId: Long) {
        memberVerificationFormState[guildId] = MemberVerificationFormData(FetchStates.FAILED, null)
        markChanged()
    }

    @StoreThread
    internal fun handleMemberVerificationFormFetchSuccess(
        guildId: Long,
        memberVerificationForm: ModelMemberVerificationForm,
    ) {
        memberVerificationFormState[guildId] =
            MemberVerificationFormData(FetchStates.SUCCEEDED, memberVerificationForm)
        memberVerificationForm.guild?.let(onGuildReceived)
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        memberVerificationFormState.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        memberVerificationFormSnapshot = HashMap(memberVerificationFormState)
    }
}
