package com.discord.widgets.settings

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import rx.Observable
import rx.functions.Action1

/**
 * "Accessibility" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsAccessibility`.
 *
 * The screen has one non-obvious rule: reduced motion **overrides** the animated-emoji, autoplay-GIF
 * and sticker-animation settings without changing their stored values. While it is on, those rows
 * render as off/animate-on-interaction, are made non-interactive with an explanation toast
 * (`accessibility_reduced_motion_settings_override` / `stickers_auto_play_help_disabled`) and the
 * stored value is left alone, so turning reduced motion back off restores the user's own choices.
 *
 * Every render therefore comes from [Model], which observes the *raw* stored settings
 * (`useReducedMotion = false`) plus the reduced-motion flag. A sticker radio tap only writes to
 * `StoreUserSettings`; the re-render arrives from the store, so the tap cannot desynchronise the
 * override state.
 */
class WidgetSettingsAccessibility : AppFragment(R.layout.widget_settings_accessibility) {

    /** Reference `WidgetSettingsAccessibility.Model`. */
    data class Model(
        val reducedMotionEnabled: Boolean,
        val allowAnimatedEmoji: Boolean,
        val autoPlayGifs: Boolean,
        val currentStickerAnimationSettings: Int,
    ) {
        companion object {
            fun get(): Observable<Model> {
                val settings = StoreStream.getUserSettings()
                return Observable.combineLatest(
                    StoreStream.getAccessibility().observeReducedMotionEnabled(),
                    settings.observeIsAnimatedEmojisEnabled(false),
                    settings.observeIsAutoPlayGifsEnabled(false),
                    settings.observeStickerAnimationSettings(false),
                ) { reducedMotion, animatedEmoji, autoPlayGifs, stickerAnimation ->
                    Model(reducedMotion, animatedEmoji, autoPlayGifs, stickerAnimation)
                }
            }
        }
    }

    private var stickersAnimationRadioManager: RadioManager? = null

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setActionBarTitle(R.string.accessibility)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        val root = view ?: return
        val accessibility = StoreStream.getAccessibility()
        val settings = StoreStream.getUserSettings()

        root.findViewById<CheckedSetting>(R.id.settings_accessibility_reduced_motion_switch)
            .setOnCheckedListener(Action1 { accessibility.setReducedMotionEnabled(it) })
        root.findViewById<LinkifiedTextView>(R.id.settings_accessibility_reduced_motion_description).apply {
            text = SettingsStrings.render(
                context,
                R.string.accessibility_prefers_reduced_motion_description,
                "helpdeskArticle" to REDUCED_MOTION_HELP_URL,
            )
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_animate_emoji_switch)
            .setOnCheckedListener(Action1 { settings.setIsAnimatedEmojisEnabled(it) })
        root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_autoplay_gif_switch)
            .setOnCheckedListener(Action1 { settings.setIsAutoPlayGifsEnabled(it) })

        stickersAnimationRadioManager = RadioManager(stickerRadios(root))

        listOf(
            R.id.settings_accessibility_reduced_motion_header,
            R.id.settings_accessibility_images_header,
            R.id.settings_accessibility_stickers_header,
        ).forEach { id -> ViewCompat.setAccessibilityHeading(root.findViewById<TextView>(id), true) }

        Model.get().ui(this).subscribe(::configureUI)
    }

    override fun onDestroyView() {
        stickersAnimationRadioManager = null
        super.onDestroyView()
    }

    private fun configureUI(model: Model) {
        val root = view ?: return
        val emoji = root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_animate_emoji_switch)
        val gifs = root.findViewById<CheckedSetting>(R.id.settings_accessibility_allow_autoplay_gif_switch)
        val radios = stickerRadios(root)

        root.findViewById<CheckedSetting>(R.id.settings_accessibility_reduced_motion_switch)
            .setChecked(model.reducedMotionEnabled, false)

        if (model.reducedMotionEnabled) {
            emoji.setDisabledTooltip(R.string.accessibility_reduced_motion_settings_override)
            emoji.setChecked(false, false)
            radios.forEach { it.setDisabledTooltip(R.string.stickers_auto_play_help_disabled) }
            stickersAnimationRadioManager?.setChecked(
                if (model.currentStickerAnimationSettings != ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE) {
                    root.findViewById(R.id.stickers_animate_on_interaction)
                } else {
                    root.findViewById(R.id.stickers_never_animate)
                },
            )
            gifs.setChecked(false, false)
            gifs.setDisabledTooltip(R.string.accessibility_reduced_motion_settings_override)
        } else {
            emoji.setChecked(model.allowAnimatedEmoji, false)
            configureStickerAnimationRadio(
                model.currentStickerAnimationSettings,
                root.findViewById(R.id.stickers_always_animate),
                ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE,
            )
            configureStickerAnimationRadio(
                model.currentStickerAnimationSettings,
                root.findViewById(R.id.stickers_animate_on_interaction),
                ModelUserSettings.STICKER_ANIMATION_ANIMATE_ON_INTERACTION,
            )
            configureStickerAnimationRadio(
                model.currentStickerAnimationSettings,
                root.findViewById(R.id.stickers_never_animate),
                ModelUserSettings.STICKER_ANIMATION_NEVER_ANIMATE,
            )
            gifs.setChecked(model.autoPlayGifs, false)
            emoji.setContainerClickListener { emoji.setChecked(!emoji.isChecked, true) }
            gifs.setContainerClickListener { gifs.setChecked(!gifs.isChecked, true) }
        }
    }

    private fun configureStickerAnimationRadio(
        currentStickerAnimationSettings: Int,
        radio: CheckedSetting,
        stickerAnimationSetting: Int,
    ) {
        radio.setContainerClickListener {
            StoreStream.getUserSettings().setStickerAnimationSettings(stickerAnimationSetting)
        }
        if (currentStickerAnimationSettings == stickerAnimationSetting) {
            stickersAnimationRadioManager?.setChecked(radio)
        }
    }

    private fun stickerRadios(root: View): List<CheckedSetting> = listOf(
        root.findViewById(R.id.stickers_always_animate),
        root.findViewById(R.id.stickers_animate_on_interaction),
        root.findViewById(R.id.stickers_never_animate),
    )

    private companion object {
        const val REDUCED_MOTION_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360040613412"
    }
}
