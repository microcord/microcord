package com.discord.widgets.settings

import android.view.View
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsMediaBinding
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
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
        val binding = WidgetSettingsMediaBinding.bind(view)

        binding.attachmentsToggle.apply {
            bind(settings.getIsAttachmentMediaInline(), settings::setIsAttachmentMediaInline)
            setSubtext(
                SettingsStrings.render(
                    context,
                    R.string.inline_attachment_media_help,
                    "maxSize" to PREVIEW_MAX_SIZE_MB,
                ),
            )
        }
        binding.compressionToggle
            .bind(settings.getIsAutoImageCompressionEnabled(), settings::setIsAutoImageCompressionEnabled)
        binding.compressionToggleSubtext.apply {
            // Reference opens the premium upsell sheet from the `{nitroLink}` hook. `widgets/settings/premium`
            // has not been reconstructed, so the link falls through to `UriHandler`.
            text = SettingsStrings.render(context, R.string.image_compression_nitro_upsell, "nitroLink" to NITRO_URL)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        binding.settingsTextImagesEmbedsToggle.bind(settings.getIsEmbedMediaInlined(), settings::setIsEmbedMediaInlined)
        binding.settingsTextImagesLinksToggle.bind(settings.getIsRenderEmbedsEnabled(), settings::setIsRenderEmbedsEnabled)
        binding.settingsTextImagesSyncToggle
            .bind(settings.getIsSyncTextAndImagesEnabled(), settings::setIsSyncTextAndImagesEnabled)
        binding.stickersSuggestions.bind(settings.getIsStickerSuggestionsEnabled(), settings::setIsStickerSuggestionsEnabled)
        binding.settingsTextImagesDescriptionsToggle
            .bind(UserSettings.viewImageDescriptions.get(), UserSettings.viewImageDescriptions::set)
        binding.settingsTextImagesStickerAutocompleteToggle
            .bind(UserSettings.includeStickersInAutocomplete.get(), UserSettings.includeStickersInAutocomplete::set)
        binding.settingsTextImagesReactionsToggle.bind(UserSettings.renderReactions.get(), UserSettings.renderReactions::set)
        binding.settingsTextImagesEmoticonsToggle.bind(UserSettings.convertEmoticons.get(), UserSettings.convertEmoticons::set)

        // RN keeps this as its own "Swiping Right to Left" radio under Text & Images.
        val details = binding.settingsTextImagesSwipeChannelDetails
        val reply = binding.settingsTextImagesSwipeReply
        val swipe = RadioManager(listOf(details, reply))
        swipe.setChecked(if (UserSettings.swipeToReply.get()) reply else details)
        details.setContainerClickListener { swipe.setChecked(details); UserSettings.swipeToReply.set(false) }
        reply.setContainerClickListener { swipe.setChecked(reply); UserSettings.swipeToReply.set(true) }

        listOf(
            binding.settingsTextImagesDisplayHeader,
            binding.settingsTextImagesPreviewHeader,
            binding.settingsTextImagesCompressionHeader,
            binding.settingsTextImagesStickersHeader,
            binding.settingsTextImagesEmojiHeader,
            binding.settingsTextImagesSwipeHeader,
            binding.settingsTextImagesSyncHeader,
        ).forEach { ViewCompat.setAccessibilityHeading(it, true) }
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
