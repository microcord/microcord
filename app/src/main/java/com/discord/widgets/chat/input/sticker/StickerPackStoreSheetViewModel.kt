package com.discord.widgets.chat.input.sticker

import com.discord.api.premium.PremiumTier
import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.stickers.StickerStore
import com.discord.widgets.stickers.StickerStores
import rx.Observable

class StickerPackStoreSheetViewModel(
    private val sticker: Sticker,
    private val stickerStore: StickerStore = StickerStores.stickers,
    storeUser: StoreUser = StoreStream.getUsers(),
    storeStateObservable: Observable<StoreState> = observeStoreState(stickerStore, storeUser, sticker),
) : AppViewModel<StickerPackStoreSheetViewModel.ViewState>(ViewState.Loading) {
    data class StoreState(
        val stickerPack: StickerStore.StickerPackState,
        val meUser: MeUser,
        val stickerAnimationSettings: Int,
    )

    sealed class ViewState {
        object Loading : ViewState()

        data class Loaded(
            val stickerPack: ModelStickerPack,
            val meUserPremiumTier: PremiumTier,
            val stickerAnimationSettings: Int,
        ) : ViewState()
    }

    init {
        sticker.packId?.let(stickerStore::fetchStickerPack)
        storeStateObservable.computationLatest().ui(this).subscribe { state ->
            val loaded = state.stickerPack as? StickerStore.StickerPackState.Loaded ?: return@subscribe
            updateViewState(
                ViewState.Loaded(
                    loaded.stickerPack,
                    state.meUser.premiumTier,
                    state.stickerAnimationSettings,
                ),
            )
        }
    }

    companion object {
        fun observeStoreState(
            stickerStore: StickerStore,
            storeUser: StoreUser,
            sticker: Sticker,
        ): Observable<StoreState> {
            val packId = sticker.packId ?: return Observable.empty()
            return Observable.combineLatest(
                stickerStore.observeStickerPack(packId),
                storeUser.observeMe(),
                StoreStream.getUserSettings().observeStickerAnimationSettings(),
                ::StoreState,
            )
        }
    }
}
