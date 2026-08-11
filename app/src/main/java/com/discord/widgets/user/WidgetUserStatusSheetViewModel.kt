package com.discord.widgets.user

import com.discord.api.activity.Activity
import com.discord.api.presence.ClientStatus
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.stores.StoreUserSettings
import com.discord.stores.UserSettings
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.user.profile.UserStatusPresenceCustomView
import rx.Observable

class WidgetUserStatusSheetViewModel(
    val storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
    val storePresences: StoreUserPresence = StoreStream.getPresences(),
    val restAPI: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeStoreState(storePresences),
    private val placeholderEmoji: () -> ModelEmojiUnicode = ::getPlaceholderEmoji,
) : AppViewModel<WidgetUserStatusSheetViewModel.ViewState>(ViewState.Uninitialized) {

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetUserStatusSheetViewModel::class.java, onNext = ::handleStoreState)
    }

    fun clearCustomStatus() {
        storeUserSettings.updateCustomStatus(null)
            .restSubscribeOn()
            .appSubscribe(WidgetUserStatusSheetViewModel::class.java) {}
    }

    fun setStatus(status: ClientStatus) = UserSettings.status.set(status)

    private fun handleStoreState(storeState: StoreState) {
        val activity = storeState.customStatusActivity
        val customStatus = if (activity == null) {
            UserStatusPresenceCustomView.ViewState.WithPlaceholder(placeholderEmoji())
        } else {
            UserStatusPresenceCustomView.ViewState.WithStatus(
                activity.emoji?.let {
                    UserStatusPresenceCustomView.Emoji(it.id, it.name, it.animated)
                },
                activity.state,
            )
        }
        updateViewState(ViewState.Loaded(customStatus))
    }

    data class StoreState(val customStatusActivity: Activity?)

    sealed class ViewState {
        object Uninitialized : ViewState()
        data class Loaded(
            val customStatusViewState: UserStatusPresenceCustomView.ViewState,
        ) : ViewState()
    }

    companion object {
        private val placeholderNames = arrayOf(
            "grinning",
            "grimacing",
            "grin",
            "joy",
            "smiley",
            "smile",
            "thinking",
            "flushed",
            "eggplant",
        )

        internal fun observeStoreState(storePresences: StoreUserPresence): Observable<StoreState> =
            storePresences.observeLocalPresence().map { presence ->
                StoreState(with(PresenceUtils) { presence.customStatusActivity })
            }

        private fun getPlaceholderEmoji(): ModelEmojiUnicode {
            val emojis = StoreStream.getEmojis().unicodeEmojisNamesMap
            return emojis[placeholderNames.random()]
                ?: emojis.values.randomOrNull()
                ?: ModelEmojiUnicode(surrogates = "\uD83D\uDCAC")
        }
    }
}
