package com.discord.widgets.guildcommunicationdisabled.start

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * "You are timed out in this server" — the countdown behind the disabled chat input.
 *
 * Unlike every other view model here the store state alone is not enough: the remaining time has to
 * tick. The reference combines the store observable with a 1 Hz interval and republishes the *same*
 * store state on every tick, so [handleStoreState] recomputes `timeoutTimeLeftMs` against the clock
 * once a second. Reproduced exactly, including the "throw the tick away, keep the state" combiner.
 */
class GuildCommunicationDisabledBottomSheetViewModel(
    guildId: Long,
    userId: Long,
    observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    guildStore: StoreGuilds = StoreStream.getGuilds(),
    private val clock: Clock = ClockFactory.get(),
    storeStateObservable: Observable<StoreState> =
        observeStores(guildId, userId, observationDeck, guildStore),
) : AppViewModel<GuildCommunicationDisabledBottomSheetViewModel.ViewState>() {

    data class StoreState(
        val guild: Guild?,
        val guildMember: GuildMember?,
    )

    sealed class ViewState {
        /** The guild or the membership is gone; there is nothing to count down. */
        object Invalid : ViewState()

        data class Valid(
            val timeoutTimeLeftMs: Long,
            val guildName: String,
        ) : ViewState()
    }

    init {
        Observable.combineLatest(
            Observable.interval(0L, 1L, TimeUnit.SECONDS, rx.schedulers.Schedulers.computation()),
            storeStateObservable,
        ) { _, storeState -> storeState }
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val (guild, guildMember) = storeState
        if (guild == null || guildMember == null) {
            updateViewState(ViewState.Invalid)
            return
        }
        val until = guildMember.communicationDisabledUntil
        updateViewState(
            ViewState.Valid(
                timeoutTimeLeftMs = if (until != null) until.dateTimeMillis - clock.currentTimeMillis() else 0L,
                guildName = guild.name,
            ),
        )
    }

    companion object {
        fun observeStores(
            guildId: Long,
            userId: Long,
            observationDeck: ObservationDeck,
            guildStore: StoreGuilds,
        ): Observable<StoreState> =
            observationDeck.connectRx(guildStore) {
                StoreState(guildStore.getGuild(guildId), guildStore.getMember(guildId, userId))
            }
    }
}
