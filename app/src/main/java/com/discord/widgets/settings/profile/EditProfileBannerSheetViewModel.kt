package com.discord.widgets.settings.profile

import androidx.annotation.MainThread
import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremiumTier2
import rx.Observable

class EditProfileBannerSheetViewModel(
    private val defaultBannerColorHex: String,
    private val initialColorPreviewHex: String?,
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<EditProfileBannerSheetViewModel.ViewState>(null) {

    var upsellViewedTracked = false

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(EditProfileBannerSheetViewModel::class.java, onNext = ::handleStoreState)
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        updateViewState(nextViewState(viewState, storeState, initialColorPreviewHex, defaultBannerColorHex))
    }

    @MainThread
    fun updateColorPreview(colorHex: String?) {
        val state = viewState ?: return
        updateViewState(state.copy(bannerColorHex = colorHex ?: defaultBannerColorHex))
    }

    data class StoreState(val user: MeUser, val userProfile: UserProfile)

    data class ViewState(val bannerColorHex: String, val showPremiumUpsell: Boolean)

    companion object {
        private fun observeStoreState(): Observable<StoreState> = StoreStream.getUsers().observeMe()
            .flatMap { user ->
                StoreStream.getUserProfiles().observeUserProfile(user.id)
                    .map { profile -> StoreState(user, profile) }
            }

        internal fun nextViewState(
            current: ViewState?,
            storeState: StoreState,
            initialColorPreviewHex: String?,
            defaultBannerColorHex: String,
        ) = ViewState(
            bannerColorHex = current?.bannerColorHex ?: initialColorPreviewHex ?: defaultBannerColorHex,
            showPremiumUpsell = !storeState.user.isPremiumTier2,
        )
    }
}
