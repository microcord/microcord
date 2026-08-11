package com.discord.widgets.settings

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting
import rx.functions.Action1

/**
 * "Text & Images" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsMedia`.
 *
 * Reference passes `appActivity` to `setIsAttachmentMediaInline`, `setIsEmbedMediaInlined`,
 * `setIsRenderEmbedsEnabled` and `setIsSyncTextAndImagesEnabled` so the store can surface the
 * "restart required" notice; `StoreUserSettings` in this project only exposes the one-argument
 * overloads (it is being rewritten to regain its server-write half), so those are used here.
 */
class WidgetSettingsMedia : AppFragment(R.layout.widget_settings_media) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.text_and_images)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        val settings = StoreStream.getUserSettings()

        view.findViewById<CheckedSetting>(R.id.attachments_toggle).apply {
            bind(settings.getIsAttachmentMediaInline(), settings::setIsAttachmentMediaInline)
            setSubtext(
                SettingsStrings.render(
                    context,
                    R.string.inline_attachment_media_help,
                    "maxSize" to PREVIEW_MAX_SIZE_MB,
                ),
            )
        }
        view.findViewById<CheckedSetting>(R.id.compression_toggle)
            .bind(settings.getIsAutoImageCompressionEnabled(), settings::setIsAutoImageCompressionEnabled)
        view.findViewById<LinkifiedTextView>(R.id.compression_toggle_subtext).apply {
            // Reference opens the premium upsell sheet from the `{nitroLink}` hook. `widgets/settings/premium`
            // has not been reconstructed, so the link falls through to `UriHandler`.
            text = SettingsStrings.render(context, R.string.image_compression_nitro_upsell, "nitroLink" to NITRO_URL)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        view.findViewById<CheckedSetting>(R.id.settings_text_images_embeds_toggle)
            .bind(settings.getIsEmbedMediaInlined(), settings::setIsEmbedMediaInlined)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_links_toggle)
            .bind(settings.getIsRenderEmbedsEnabled(), settings::setIsRenderEmbedsEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_sync_toggle)
            .bind(settings.getIsSyncTextAndImagesEnabled(), settings::setIsSyncTextAndImagesEnabled)
        view.findViewById<CheckedSetting>(R.id.stickers_suggestions)
            .bind(settings.getIsStickerSuggestionsEnabled(), settings::setIsStickerSuggestionsEnabled)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_descriptions_toggle)
            .bind(UserSettings.viewImageDescriptions.get(), UserSettings.viewImageDescriptions::set)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_sticker_autocomplete_toggle)
            .bind(UserSettings.includeStickersInAutocomplete.get(), UserSettings.includeStickersInAutocomplete::set)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_reactions_toggle)
            .bind(UserSettings.renderReactions.get(), UserSettings.renderReactions::set)
        view.findViewById<CheckedSetting>(R.id.settings_text_images_emoticons_toggle)
            .bind(UserSettings.convertEmoticons.get(), UserSettings.convertEmoticons::set)

        listOf(
            R.id.settings_text_images_display_header,
            R.id.settings_text_images_preview_header,
            R.id.settings_text_images_compression_header,
            R.id.settings_text_images_stickers_header,
            R.id.settings_text_images_emoji_header,
            R.id.settings_text_images_sync_header,
        ).forEach { id -> ViewCompat.setAccessibilityHeading(view.findViewById<TextView>(id), true) }
    }

    private companion object {
        const val PREVIEW_MAX_SIZE_MB = "10"
        const val NITRO_URL = "https://discord.com/settings/premium"
    }
}

/** Sets the current value without notifying, then installs [update] as the change listener. */
internal fun CheckedSetting.bind(value: Boolean, update: (Boolean) -> Unit) {
    setOnCheckedListener(null)
    setChecked(value, false)
    setOnCheckedListener(Action1(update))
}
