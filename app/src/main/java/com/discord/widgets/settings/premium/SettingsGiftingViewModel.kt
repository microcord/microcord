package com.discord.widgets.settings.premium

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.premium.ClaimedOutboundPromotion
import com.discord.api.premium.OutboundPromotion
import com.discord.app.AppComponent
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelEntitlement
import com.discord.models.domain.ModelGift
import com.discord.stores.StoreEntitlements
import com.discord.stores.StoreOutboundPromotions
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremium
import rx.Observable
import rx.subjects.PublishSubject

class SettingsGiftingViewModel(
    private val entitlements: StoreEntitlements = StoreStream.getEntitlements(),
    storeObservable: Observable<StoreState> = observeStores(),
) : AppViewModel<SettingsGiftingViewModel.ViewState>(ViewState.Loading) {
    data class StoreState(
        val entitlementState: StoreEntitlements.State,
        val myResolvedGifts: List<ModelGift>,
        val outboundPromoState: StoreOutboundPromotions.State,
        val isUserPremium: Boolean,
    )

    data class GiftAndPromoData(
        val entitlementState: StoreEntitlements.State,
        val resolvingGiftState: ResolvingGiftState,
        val myPurchasedGifts: Map<Long, List<ModelGift>>,
        val outboundPromoState: StoreOutboundPromotions.State,
        val claimedOutboundPromotions: List<ClaimedOutboundPromotion>,
    )

    sealed class OutboundPromoData {
        data object Loading : OutboundPromoData()
        data class Loaded(val outboundPromos: List<OutboundPromoItem>) : OutboundPromoData()
    }

    sealed class ResolvingGiftState { data object NotResolving : ResolvingGiftState(); data object Resolving : ResolvingGiftState(); data object Error : ResolvingGiftState() }
    sealed class Event { data class ShowPromoBottomSheet(val content: String) : Event(); data class ShowPromoDialog(val claimStatus: ClaimStatus) : Event() }
    sealed class GiftPurchaseEvent {
        data object StartGiftPurchase : GiftPurchaseEvent()
        data object NotInProgress : GiftPurchaseEvent()
        data class ErrorGiftPurchase(@StringRes val message: Int) : GiftPurchaseEvent()
        data class CompleteGiftPurchase(val skuName: String, val newGiftCode: String) : GiftPurchaseEvent()
    }
    data class OutboundPromoItem(
        val claimStatus: ClaimStatus,
        val id: Long,
        val title: String,
        val terms: String,
        val imageUrl: String,
    )
    sealed class ViewState {
        data object Loading : ViewState()
        data object Failure : ViewState()
        data class Loaded(
            val myEntitlements: Map<Long, List<ModelEntitlement>>,
            val resolvingGiftState: ResolvingGiftState,
            val expandedSkuOrPlanIds: Set<Long>,
            val outboundPromos: List<OutboundPromoItem>,
            val myPurchasedGifts: Map<Long, List<ModelGift>>,
            val lastCopiedCode: String?,
        ) : ViewState()
    }

    private val events = PublishSubject.create<Event>()
    private val purchaseEvents = PublishSubject.create<GiftPurchaseEvent>()
    private var onGiftCodeResolved: (String) -> Unit = {}

    init {
        entitlements.fetchMyGiftEntitlements()
        storeObservable.ui(this).appSubscribe(javaClass) { state -> handleStoreState(state) }
    }

    private fun handleStoreState(state: StoreState) {
        val entitlementState = state.entitlementState
        if (entitlementState is StoreEntitlements.State.Failure) return updateViewState(ViewState.Failure)
        if (entitlementState !is StoreEntitlements.State.Loaded || state.outboundPromoState is StoreOutboundPromotions.State.Loading) {
            return updateViewState(ViewState.Loading)
        }
        val claimed = if (state.isUserPremium) fetchClaimedPromotions() else Observable.just(emptyList())
        claimed.onErrorReturn { emptyList() }.ui(this).appSubscribe(javaClass) { claimedPromos ->
            val current = viewState as? ViewState.Loaded
            updateViewState(
                ViewState.Loaded(
                    entitlementState.giftableEntitlements,
                    current?.resolvingGiftState ?: ResolvingGiftState.NotResolving,
                    current?.expandedSkuOrPlanIds.orEmpty(),
                    promos((state.outboundPromoState as? StoreOutboundPromotions.State.Loaded)?.validActivePromotions.orEmpty(), claimedPromos),
                    state.myResolvedGifts.groupBy { it.subscriptionPlanId ?: it.skuId },
                    current?.lastCopiedCode,
                ),
            )
        }
    }

    @MainThread fun handleSkuClicked(skuId: Long, planId: Long?) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val key = planId ?: skuId
        val expanded = loaded.expandedSkuOrPlanIds.toMutableSet()
        if (!expanded.add(key)) expanded.remove(key) else fetchPurchasedGifts(skuId, planId)
        updateViewState(loaded.copy(expandedSkuOrPlanIds = expanded))
    }

    private fun fetchPurchasedGifts(skuId: Long, planId: Long?) {
        val api = RestAPI.apiOrNull ?: return
        api.resolveSkuIdGift(skuId, planId).restSubscribeOn(false).ui(this).appSubscribe(javaClass) { gifts ->
            val loaded = viewState as? ViewState.Loaded ?: return@appSubscribe
            updateViewState(loaded.copy(myPurchasedGifts = loaded.myPurchasedGifts + ((planId ?: skuId) to gifts)))
        }
    }

    fun redeemGiftCode(giftCode: String, appComponent: AppComponent) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(resolvingGiftState = ResolvingGiftState.Resolving))
        val api = RestAPI.apiOrNull ?: return updateViewState(loaded.copy(resolvingGiftState = ResolvingGiftState.Error))
        api.resolveGiftCode(giftCode, true, true).restSubscribeOn(false).ui(appComponent)
            .appSubscribe(javaClass, errorHandler = { updateViewState(loaded.copy(resolvingGiftState = ResolvingGiftState.Error)) }) { gift ->
                updateViewState(loaded.copy(resolvingGiftState = ResolvingGiftState.NotResolving))
                if (!gift.isClaimedByMe()) onGiftCodeResolved(gift.code)
            }
    }

    @MainThread fun handleCopyClicked(giftCode: String) = (viewState as? ViewState.Loaded)?.let { updateViewState(it.copy(lastCopiedCode = giftCode)) }
    @MainThread fun handleClaimedPromo(claimed: ClaimStatus.Claimed) = (viewState as? ViewState.Loaded)?.let { loaded ->
        updateViewState(loaded.copy(outboundPromos = loaded.outboundPromos.map { if (it.id == claimed.promoId) it.copy(claimStatus = claimed) else it }))
    }
    fun handlePromoButtonClicked(item: OutboundPromoItem) = events.onNext(Event.ShowPromoDialog(item.claimStatus))
    fun handlePromoMoreDetailsClicked(item: OutboundPromoItem) = events.onNext(Event.ShowPromoBottomSheet(item.terms))
    fun observeEvents(): Observable<Event> = events
    fun observeGiftPurchaseEvents(): Observable<GiftPurchaseEvent> = purchaseEvents
    fun setOnGiftCodeResolved(onGiftCodeResolved: (String) -> Unit) { this.onGiftCodeResolved = onGiftCodeResolved }

    private fun fetchClaimedPromotions(): Observable<List<ClaimedOutboundPromotion>> =
        RestAPI.apiOrNull?.getClaimedOutboundPromotions() ?: Observable.just(emptyList())

    private fun promos(active: List<OutboundPromotion>, claimed: List<ClaimedOutboundPromotion>): List<OutboundPromoItem> =
        (active.associate { promo -> promo.id to promo.toItem() } + claimed.associate { promo -> promo.promotion.id to promo.toItem() }).values.toList()

    private fun OutboundPromotion.toItem() = OutboundPromoItem(
        ClaimStatus.Unclaimed(id, endDate), id, outboundTitle, outboundTermsAndConditions, promoImageUrl(id),
    )
    private fun ClaimedOutboundPromotion.toItem() = OutboundPromoItem(
        ClaimStatus.Claimed(promotion.id, code, promotion.outboundRedemptionModalBody, redemptionLink, redeemByDate),
        promotion.id, promotion.outboundTitle, promotion.outboundTermsAndConditions, promoImageUrl(promotion.id),
    )
    private fun promoImageUrl(id: Long) = "${IconUtils.CDN_HOST}/promotions/$id/logo-dark?size=256"

    companion object {
        private fun observeStores(): Observable<StoreState> {
            val promoState = RestAPI.apiOrNull?.getAllActiveOutboundPromotions()?.map<StoreOutboundPromotions.State> {
                StoreOutboundPromotions.State.Loaded(it, 0)
            }?.onErrorReturn { StoreOutboundPromotions.State.Failed }
                ?: Observable.just(StoreOutboundPromotions.State.Failed)
            val entitlements: Observable<StoreEntitlements.State> =
                StoreStream.getEntitlements().observeEntitlementState()
            val gifts: Observable<List<ModelGift>> = Observable.just(emptyList())
            val promotions: Observable<StoreOutboundPromotions.State> = Observable.concat(
                Observable.just(StoreOutboundPromotions.State.Loading),
                promoState,
            )
            val premium: Observable<Boolean> = StoreStream.getUsers().observeMe().map { it.isPremium }
            return Observable.combineLatest(
                entitlements,
                gifts,
                promotions,
                premium,
            ) { entitlementState: StoreEntitlements.State, giftList: List<ModelGift>, promotionState: StoreOutboundPromotions.State, isPremium: Boolean ->
                StoreState(entitlementState, giftList, promotionState, isPremium)
            }
        }
    }
}
