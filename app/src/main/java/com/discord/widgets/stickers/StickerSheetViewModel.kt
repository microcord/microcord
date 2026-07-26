package com.discord.widgets.stickers

import androidx.annotation.MainThread
import com.discord.api.premium.PremiumTier
import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Drives [WidgetStickerSheet] — the popout for a *standard* (Discord-published) sticker tapped in a
 * message.
 *
 * The sheet only renders once its pack has loaded, so [ViewState] is not published until then and
 * the sheet shows its inflated-but-empty layout for that window.
 */
class StickerSheetViewModel(
    private val sticker: Sticker,
    private val stickerStore: StickerStore = StickerStores.stickers,
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val location: String?,
    storeStateObservable: Observable<StoreState> = observeStoreState(stickerStore, storeUser, sticker),
) : AppViewModel<StickerSheetViewModel.ViewState>() {

    data class StoreState(
        val stickerPack: StickerStore.StickerPackState,
        val meUser: MeUser,
    )

    data class ViewState(
        val stickerPack: ModelStickerPack,
        val sticker: Sticker,
        val meUserPremiumTier: PremiumTier,
        val isStickerPackEnabled: Boolean,
    ) {
        val canUsePremiumStickers: Boolean = meUserPremiumTier.grantsAccessToPremiumStickers()
    }

    /**
     * The reference fires `AnalyticsTracker.stickerPopoutOpened` exactly once per popout, keyed off
     * this flag. `utilities/analytics/AnalyticsTracker` does not exist in this project yet, so the
     * flag and [location] are kept — they are the only inputs that call needs — and the emission is
     * the one thing missing. Reported to the analytics wave.
     */
    private var hasFiredAnalytics = false

    init {
        fetchStickersData()
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    private fun fetchStickersData() {
        sticker.packId?.let(stickerStore::fetchStickerPack)
        stickerStore.fetchEnabledStickerDirectory()
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val loaded = storeState.stickerPack as? StickerStore.StickerPackState.Loaded ?: return
        handleLoadedStoreState(loaded.stickerPack, storeState.meUser)
    }

    @MainThread
    private fun handleLoadedStoreState(stickerPack: ModelStickerPack, meUser: MeUser) {
        if (!hasFiredAnalytics && location != null) {
            // StickerPurchaseLocation.getPopoutPurchaseLocation(stickerPack.canBePurchased()) is the
            // section the reference reports here. See the note on hasFiredAnalytics.
            hasFiredAnalytics = true
        }
        // "Enabled" means the pack is in the user's sticker directory, i.e. the pack is one they can
        // actually send from — which is what turns the sheet's button into "view pack" instead of a
        // store link.
        val isStickerPackEnabled = stickerStore.getEnabledStickerPacks()
            .any { pack -> pack.stickers.any { it.id == sticker.id } }
        updateViewState(ViewState(stickerPack, sticker, meUser.premiumTier, isStickerPackEnabled))
    }

    companion object {
        fun observeStoreState(
            stickerStore: StickerStore,
            storeUser: StoreUser,
            sticker: Sticker,
        ): Observable<StoreState> {
            val packId = requireNotNull(sticker.packId) {
                "A standard sticker always carries a packId; ${sticker.id} did not"
            }
            return Observable.combineLatest(
                stickerStore.observeStickerPack(packId),
                storeUser.observeMe(),
            ) { stickerPack, meUser -> StoreState(stickerPack, meUser) }
        }
    }
}

/**
 * Whether a Nitro tier includes the 300 Discord-published stickers.
 *
 * Belongs in `utilities/premium/PremiumUtils` (the reference's `grantsAccessToPremiumStickers`);
 * `UserUtils.canUsePremiumStickers` is the same predicate but only reachable from a `User`, and the
 * sticker sheet carries the bare tier. Fold the two together when `PremiumUtils` lands.
 */
internal fun PremiumTier.grantsAccessToPremiumStickers(): Boolean =
    this == PremiumTier.TIER_2 || this == PremiumTier.TIER_0
