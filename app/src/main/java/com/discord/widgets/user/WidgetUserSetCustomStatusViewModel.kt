package com.discord.widgets.user

import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import rx.Observable
import rx.subjects.PublishSubject

class WidgetUserSetCustomStatusViewModel(
    val storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
    val clock: Clock = ClockFactory.get(),
    storeObservable: Observable<StoreState> = observeStoreState(
        StoreStream.getUserSettings(),
        StoreStream.getEmojis(),
    ),
) : AppViewModel<WidgetUserSetCustomStatusViewModel.ViewState>(ViewState.Uninitialized) {

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetUserSetCustomStatusViewModel::class.java, onNext = ::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun clearStatusTextAndEmoji() = updateFormState { copy(emoji = null, text = "") }

    fun setExpiration(expiration: FormState.Expiration) =
        updateFormState { copy(expiration = expiration) }

    fun setStatusEmoji(emoji: Emoji) = updateFormState { copy(emoji = emoji) }

    fun setStatusText(text: String) = updateFormState { copy(text = text) }

    fun saveStatus() {
        val form = (viewState as? ViewState.Loaded)?.formState ?: return
        val text = form.text.trim().ifEmpty { null }
        val setting = if (text == null && form.emoji == null) {
            null
        } else {
            ModelCustomStatusSetting(
                text = text,
                emojiId = (form.emoji as? ModelEmojiCustom)?.id,
                emojiName = (form.emoji as? ModelEmojiUnicode)?.surrogates,
                expiresAt = expirationUtc(form.expiration, clock.currentTimeMillis()),
            )
        }
        storeUserSettings.updateCustomStatus(setting)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetUserSetCustomStatusViewModel::class.java,
                errorHandler = {
                    eventSubject.onNext(Event.SetStatusFailure(R.string.default_failure_to_perform_action_message))
                },
            ) {
                eventSubject.onNext(Event.SetStatusSuccess(R.string.custom_status_update_success))
            }
    }

    private fun handleStoreState(storeState: StoreState) {
        if (viewState is ViewState.Loaded) return
        val setting = storeState.customStatusSetting
        val emojiKey = setting.emojiId?.toString() ?: setting.emojiName
        updateViewState(
            ViewState.Loaded(
                FormState(
                    emoji = if (setting == ModelCustomStatusSetting.CLEAR) null else storeState.emojiSet.emojiIndex[emojiKey],
                    text = if (setting == ModelCustomStatusSetting.CLEAR) "" else setting.text.orEmpty(),
                    expiration = DEFAULT_EXPIRATION,
                ),
            ),
        )
    }

    private fun updateFormState(transform: FormState.() -> FormState) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(formState = loaded.formState.transform()))
    }

    companion object {
        val DEFAULT_EXPIRATION = FormState.Expiration.TOMORROW

        internal fun expirationUtc(expiration: FormState.Expiration, now: Long): String? {
        val expiresAt = when (expiration) {
            FormState.Expiration.NEVER -> null
            FormState.Expiration.IN_30_MINUTES -> now + 30 * 60_000L
            FormState.Expiration.IN_1_HOUR -> now + 60 * 60_000L
            FormState.Expiration.IN_4_HOURS -> now + 4 * 60 * 60_000L
            FormState.Expiration.TOMORROW -> TimeUtils.toCalendar(now).run {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                timeInMillis
            }
        }
        return TimeUtils.toUTCDateTime(expiresAt)
        }

        internal fun observeStoreState(
            storeUserSettings: StoreUserSettings,
            storeEmoji: StoreEmoji,
        ): Observable<StoreState> = Observable.combineLatest(
            storeUserSettings.observeCustomStatus(),
            storeEmoji.getEmojiSet(StoreEmoji.EmojiContext.Global, false, false),
        ) { customStatus, emojiSet -> StoreState(customStatus, emojiSet) }.take(1)
    }

    data class FormState(
        val emoji: Emoji?,
        val text: String,
        val expiration: Expiration,
    ) {
        enum class Expiration { NEVER, IN_30_MINUTES, IN_1_HOUR, IN_4_HOURS, TOMORROW }
    }

    data class StoreState(
        val customStatusSetting: ModelCustomStatusSetting,
        val emojiSet: EmojiSet,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        data class Loaded(val formState: FormState) : ViewState() {
            val showStatusClear: Boolean get() = formState.emoji != null || formState.text.isNotEmpty()
        }
    }

    sealed class Event {
        data class SetStatusSuccess(@StringRes val successMessageStringRes: Int) : Event()
        data class SetStatusFailure(@StringRes val failureMessageStringRes: Int) : Event()
    }

}
