package com.discord.widgets.guildcommunicationdisabled.start

import android.content.Context
import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Drives [WidgetDisableGuildCommunication] — "time this member out for …".
 *
 * The screen has nothing to load beyond the member being moderated, so the state machine is only
 * two-valued: [ViewState.Valid] once the guild resolves, [ViewState.Invalid] when it does not (the
 * moderator left the guild, or the deep link named a guild we are not in). The selected duration
 * lives in the view state rather than the view so it survives a store-driven rebuild — see
 * [handleStoreState], which carries the previous selection forward.
 */
class DisableGuildCommunicationViewModel(
    private val userId: Long,
    private val guildId: Long,
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    userStore: StoreUser = StoreStream.getUsers(),
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    private val api: RestAPIInterface = RestAPI.api,
    storeStateObservable: Observable<StoreState> =
        observeStores(userId, guildId, observationDeck, userStore, guildsStore),
) : AppViewModel<DisableGuildCommunicationViewModel.ViewState>() {

    data class StoreState(
        val user: User?,
        val guild: Guild?,
    )

    sealed class ViewState {
        /** No such guild: the screen cannot act and renders nothing actionable. */
        object Invalid : ViewState()

        data class Valid(
            val user: User?,
            val guildId: Long,
            val selectedDurationOption: TimeDurationDisabledCommunication,
        ) : ViewState()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val previous = viewState as? ViewState.Valid
        val guild = storeState.guild
        updateViewState(
            if (guild == null) {
                ViewState.Invalid
            } else {
                ViewState.Valid(
                    user = storeState.user,
                    guildId = guild.id,
                    // Keep whatever the moderator already picked; only a first build defaults.
                    selectedDurationOption = previous?.selectedDurationOption
                        ?: TimeDurationDisabledCommunication.SECONDS_60,
                )
            },
        )
    }

    fun onDurationLengthSelected(duration: TimeDurationDisabledCommunication) {
        val current = viewState
        if (current is ViewState.Valid) {
            updateViewState(current.copy(selectedDurationOption = duration))
        }
    }

    /**
     * `PATCH /guilds/{guild}/members/{user}` with the computed `communication_disabled_until`.
     *
     * [context] is weak because the request outlives the screen often enough to matter; the error
     * toast is only shown while the screen is still around, which is what the reference does.
     * [onFinished] runs on success only.
     */
    fun onDisableCommunicationConfirm(
        context: WeakReference<Context>,
        reason: String?,
        onFinished: () -> Unit,
    ) {
        val current = viewState
        if (current !is ViewState.Valid) return
        val (timestamp, _) = GuildCommunicationDisabledDateUtils
            .getFutureTimestamp(current.selectedDurationOption.durationMs)
        api.disableGuildCommunication(
            guildId = guildId,
            userId = userId,
            body = RestAPIParams.DisableGuildCommunication(timestamp),
            reason = reason,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = DisableGuildCommunicationViewModel::class.java,
                context = context.get(),
            ) { onFinished() }
    }

    companion object {
        fun observeStores(
            userId: Long,
            guildId: Long,
            observationDeck: ObservationDeck,
            userStore: StoreUser,
            guildsStore: StoreGuilds,
        ): Observable<StoreState> =
            observationDeck.connectRx(userStore, guildsStore) {
                StoreState(userStore.getUsers()[userId], guildsStore.getGuild(guildId))
            }
    }
}
