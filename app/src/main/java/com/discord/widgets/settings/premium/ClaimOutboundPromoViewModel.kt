package com.discord.widgets.settings.premium

import androidx.annotation.MainThread
import com.discord.api.premium.ClaimedOutboundPromotion
import com.discord.app.AppViewModel
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class ClaimOutboundPromoViewModel(
    private var claimStatus: ClaimStatus,
    private val claimPromotion: (Long) -> Observable<ClaimedOutboundPromotion> = { promotionId ->
        RestAPI.apiOrNull?.claimOutboundPromotion(promotionId)
            ?: Observable.error(IllegalStateException("REST API is unavailable"))
    },
) : AppViewModel<ClaimOutboundPromoViewModel.ViewState>(ViewState.ClaimInProgress) {
    sealed class Event {
        data class Claimed(val claimedStatus: ClaimStatus.Claimed) : Event()
        data class CopyCode(val code: String) : Event()
        data class OpenRedemptionUrl(val url: String) : Event()
        data object Dismiss : Event()
    }

    sealed class ViewState {
        data object ClaimInProgress : ViewState()
        data object ClaimFailed : ViewState()
        data class Claimed(val claimedStatus: ClaimStatus.Claimed) : ViewState()
    }

    private val events = PublishSubject.create<Event>()

    init {
        when (val status = claimStatus) {
            is ClaimStatus.Claimed -> updateViewState(ViewState.Claimed(status))
            is ClaimStatus.Unclaimed -> claimPromo(status.promoId)
        }
    }

    private fun claimPromo(promoId: Long) {
        claimPromotion(promoId)
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                ClaimOutboundPromoViewModel::class.java,
                errorHandler = { updateViewState(ViewState.ClaimFailed) },
            ) { claimedPromotion ->
                val claimed = claimedPromotion.toClaimStatus()
                claimStatus = claimed
                updateViewState(ViewState.Claimed(claimed))
                events.onNext(Event.Claimed(claimed))
            }
    }

    fun observeEvents(): Observable<Event> = events

    @MainThread fun copyClicked() = withClaimed { events.onNext(Event.CopyCode(it.code)) }
    @MainThread fun redeemClicked() = withClaimed { events.onNext(Event.OpenRedemptionUrl(it.link)) }
    @MainThread fun maybeLaterClicked() = events.onNext(Event.Dismiss)
    @MainThread fun failureCloseClicked() = events.onNext(Event.Dismiss)

    private inline fun withClaimed(block: (ClaimStatus.Claimed) -> Unit) {
        (claimStatus as? ClaimStatus.Claimed)?.let(block)
    }

    private fun ClaimedOutboundPromotion.toClaimStatus() = ClaimStatus.Claimed(
        promoId = promotion.id,
        code = code,
        body = promotion.outboundRedemptionModalBody,
        link = redemptionLink,
        redeemBy = redeemByDate,
    )
}
