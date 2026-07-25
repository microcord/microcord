package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import rx.Subscription
import rx.functions.Action1

private fun Fragment.bindToolbar(view: View, title: Int) {
    view.findViewById<Toolbar>(R.id.action_bar_toolbar).apply {
        setTitle(title)
        setNavigationOnClickListener { parentFragmentManager.popBackStack() }
    }
}

private fun CheckedSetting.bind(value: Boolean, update: (Boolean) -> Unit) {
    setOnCheckedListener(null)
    isChecked = value
    setOnCheckedListener(Action1(update))
}

class WidgetSettingsMedia : Fragment(R.layout.widget_settings_media) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindToolbar(view, R.string.text_and_images)
        val settings = StoreStream.getUserSettings()
        view.findViewById<CheckedSetting>(R.id.attachments_toggle)
            .bind(settings.getIsAttachmentMediaInline(), settings::setIsAttachmentMediaInline)
        view.findViewById<CheckedSetting>(R.id.compression_toggle)
            .bind(settings.getIsAutoImageCompressionEnabled(), settings::setIsAutoImageCompressionEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_embeds_toggle)
            .bind(settings.getIsEmbedMediaInlined(), settings::setIsEmbedMediaInlined)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_links_toggle)
            .bind(settings.getIsRenderEmbedsEnabled(), settings::setIsRenderEmbedsEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_sync_toggle)
            .bind(settings.getIsSyncTextAndImagesEnabled(), settings::setIsSyncTextAndImagesEnabled)
        view.findViewById<CheckedSetting>(R.id.stickers_suggestions)
            .bind(settings.getIsStickerSuggestionsEnabled(), settings::setIsStickerSuggestionsEnabled)
    }
}

class WidgetSettingsBehavior : Fragment(R.layout.widget_settings_behavior) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindToolbar(view, R.string.form_label_mobile_notifications_behavior)
        val settings = StoreStream.getUserSettings()
        view.findViewById<CheckedSetting>(R.id.settings_behavior_shift_send_toggle)
            .bind(settings.getIsShiftEnterToSendEnabled(), settings::setIsShiftEnterToSendEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_behavior_dev_mode_switch)
            .bind(settings.getIsDeveloperMode(), settings::setIsDeveloperMode)
        view.findViewById<CheckedSetting>(R.id.settings_behavior_browser_switch)
            .bind(settings.getIsChromeCustomTabsEnabled(), settings::setIsChromeCustomTabsEnabled)
    }
}

class WidgetSettingsAccessibility : Fragment(R.layout.widget_settings_accessibility) {
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindToolbar(view, R.string.accessibility)
        val accessibility = StoreStream.getAccessibility()
        val settings = StoreStream.getUserSettings()
        view.findViewById<CheckedSetting>(R.id.settings_accessibility_reduced_motion_switch)
            .bind(accessibility.isReducedMotionEnabled(), accessibility::setReducedMotionEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_autoplay_gif_switch)
            .bind(settings.getIsAutoPlayGifsEnabled(), settings::setIsAutoPlayGifsEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_animate_emoji_switch)
            .bind(settings.getIsAnimatedEmojisEnabled(), settings::setIsAnimatedEmojisEnabled)
        bindSticker(view, R.id.stickers_always_animate, ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE)
        bindSticker(view, R.id.stickers_animate_on_interaction, ModelUserSettings.STICKER_ANIMATION_ANIMATE_ON_INTERACTION)
        bindSticker(view, R.id.stickers_never_animate, ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE)
    }

    override fun onStart() {
        super.onStart()
        val settings = StoreStream.getUserSettings()
        subscription = StoreStream.getAccessibility().observeReducedMotionEnabled().subscribe { reduced ->
            render(
                reduced,
                settings.getIsAnimatedEmojisEnabled(),
                settings.getIsAutoPlayGifsEnabled(),
                settings.getStickerAnimationSettings(),
            )
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun bindSticker(view: View, id: Int, setting: Int) {
        view.findViewById<CheckedSetting>(id).setOnClickListener {
            val settings = StoreStream.getUserSettings()
            settings.setStickerAnimationSettings(setting)
            render(false, settings.getIsAnimatedEmojisEnabled(), settings.getIsAutoPlayGifsEnabled(), setting)
        }
    }

    private fun render(reduced: Boolean, emojis: Boolean, gifs: Boolean, stickers: Int) {
        val root = view ?: return
        root.findViewById<CheckedSetting>(R.id.settings_accessibility_reduced_motion_switch).isChecked = reduced
        root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_animate_emoji_switch).apply {
            isEnabled = !reduced
            isChecked = !reduced && emojis
        }
        root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_autoplay_gif_switch).apply {
            isEnabled = !reduced
            isChecked = !reduced && gifs
        }
        listOf(
            R.id.stickers_always_animate to ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE,
            R.id.stickers_animate_on_interaction to ModelUserSettings.STICKER_ANIMATION_ANIMATE_ON_INTERACTION,
            R.id.stickers_never_animate to ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE,
        ).forEach { (id, setting) ->
            root.findViewById<CheckedSetting>(id).apply {
                isEnabled = !reduced
                isChecked = setting == if (reduced) ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE else stickers
            }
        }
    }
}

class WidgetSettingsActivityStatus : Fragment(R.layout.widget_settings_activity_status) {
    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindToolbar(view, R.string.activity_status)
    }

    override fun onStart() {
        super.onStart()
        val settings = StoreStream.getUserSettings()
        subscription = settings.observeIsShowCurrentGameEnabled().distinctUntilChanged().subscribe { enabled ->
            view?.findViewById<CheckedSetting>(R.id.show_current_activity)
                ?.bind(enabled, settings::setIsShowCurrentGameEnabled)
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }
}
