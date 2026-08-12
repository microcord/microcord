package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads a new guild sticker, or renames and deletes an existing one.
 *
 * Creating needs a file, a name and a related emoji; editing carries none of them and only ever
 * PATCHes `name`/`description`/`tags`, because the image itself is immutable once uploaded.
 *
 * `tags` is not a keyword list despite the name — the API takes a single emoji, sent as the custom
 * emoji's id or the unicode surrogate, and every official client fills it that way.
 */
class WidgetServerSettingsStickerEdit :
    AppFragment(R.layout.widget_server_settings_sticker_edit) {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_ID)

    private val stickerId: Long
        get() = arguments?.getLong(EXTRA_STICKER_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_STICKER_ID, INVALID_ID)

    private val state = StatefulViews(
        R.id.sticker_edit_name,
        R.id.sticker_edit_description,
        STATE_ID_TAGS,
    )

    private lateinit var previewContainer: View
    private lateinit var preview: StickerView
    private lateinit var previewImage: ImageView
    private lateinit var previewPlaceholder: ImageView
    private lateinit var relatedEmoji: TextView
    private lateinit var nameInput: TextInputLayout
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var save: FloatingActionButton

    private var pickedImage: Uri? = null

    private val existing: Sticker?
        get() = stickerId.takeIf { it != INVALID_ID }
            ?.let { StoreStream.getGuildStickers().getStickersForGuild(guildId)[it] }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        previewContainer = view.findViewById(R.id.sticker_edit_preview_container)
        preview = view.findViewById(R.id.sticker_edit_preview)
        previewImage = view.findViewById(R.id.sticker_edit_preview_image)
        previewPlaceholder = view.findViewById(R.id.sticker_edit_preview_placeholder)
        relatedEmoji = view.findViewById(R.id.sticker_edit_related_emoji)
        nameInput = view.findViewById(R.id.sticker_edit_name)
        descriptionInput = view.findViewById(R.id.sticker_edit_description)
        save = view.findViewById(R.id.sticker_edit_save)

        val sticker = existing
        setActionBarTitle(
            if (sticker == null) R.string.guild_settings_action_filter_sticker_create
            else R.string.guild_settings_action_filter_sticker_update,
        )
        view.findViewById<TextView>(R.id.sticker_edit_requirements).text = SettingsStrings.render(
            view.context,
            R.string.guild_settings_stickers_upload_requirements_3,
        )

        nameInput.setText(state.get(nameInput.id, sticker?.name.orEmpty()))
        descriptionInput.setText(state.get(descriptionInput.id, sticker?.description.orEmpty()))
        state.addOptionalFields(descriptionInput)
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, nameInput, descriptionInput)

        configurePreview(sticker)
        configureRelatedEmoji(state.get(STATE_ID_TAGS, sticker?.tags.orEmpty()))
        relatedEmoji.setOnClickListener {
            WidgetEmojiPickerSheet.show(parentFragmentManager, forReaction = false) { picked ->
                configureRelatedEmoji(picked.id?.toString() ?: picked.name.orEmpty())
                state.configureSaveActionView(save)
            }
        }
        if (sticker == null) previewContainer.setOnClickListener { openMediaChooser() }

        state.configureSaveActionView(save)
        save.setOnClickListener { if (sticker == null) createSticker() else updateSticker(sticker) }
        if (sticker != null) configureDeleteMenu(sticker)
    }

    private fun configurePreview(sticker: Sticker?) {
        val local = pickedImage
        preview.visibility = if (sticker != null) View.VISIBLE else View.GONE
        previewImage.visibility = if (local != null) View.VISIBLE else View.GONE
        previewPlaceholder.visibility =
            if (sticker == null && local == null) View.VISIBLE else View.GONE
        sticker?.let(preview::d)
        local?.let { uri ->
            requireContext().contentResolver.openInputStream(uri)?.use {
                previewImage.setImageBitmap(BitmapFactory.decodeStream(it))
            }
        }
    }

    private fun configureRelatedEmoji(tags: String) {
        state.put(STATE_ID_TAGS, tags)
        relatedEmoji.text = when {
            tags.isEmpty() -> getString(R.string.guild_sticker_upload_related_emoji_placeholder)
            // A custom emoji's tag is its id, which is not renderable on its own.
            tags.all(Char::isDigit) -> ":${tags}:"
            else -> tags
        }
    }

    private fun configureDeleteMenu(sticker: Sticker) {
        setActionBarOptionsMenu(R.menu.menu_sticker_edit, onSelected = { item ->
            if (item.itemId == R.id.menu_sticker_delete) deleteSticker(sticker)
        })
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        pickedImage = uri
        configurePreview(null)
        state.configureSaveActionView(save)
    }

    private fun createSticker() {
        val uri = pickedImage ?: run {
            AppToast.show(this, R.string.guild_sticker_invalid_sticker)
            return
        }
        val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.size > WidgetServerSettingsStickers.MAX_STICKER_FILE_SIZE) {
            AppToast.show(this, R.string.guild_sticker_invalid_sticker)
            return
        }
        val name = nameInput.getTextOrEmpty().trim()
        val part = MultipartBody.Part.createFormData(
            "file",
            "$name.png",
            bytes.toRequestBody("image/png".toMediaType()),
        )
        RestAPI.api.postGuildSticker(
            guildId,
            name,
            descriptionInput.getTextOrEmpty().trim(),
            state.get(STATE_ID_TAGS, ""),
            part,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsStickerEdit::class.java, context) { finish() }
    }

    private fun updateSticker(sticker: Sticker) {
        RestAPI.api.patchGuildSticker(
            guildId,
            sticker.id,
            RestAPIParams.PatchGuildSticker(
                name = nameInput.getTextOrEmpty().trim(),
                description = descriptionInput.getTextOrEmpty().trim(),
                tags = state.get(STATE_ID_TAGS, sticker.tags),
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsStickerEdit::class.java, context) { finish() }
    }

    private fun deleteSticker(sticker: Sticker) {
        RestAPI.api.deleteGuildSticker(guildId, sticker.id)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsStickerEdit::class.java, context) { finish() }
    }

    private fun finish() {
        state.clear(clearDefaults = true)
        hideKeyboard()
        appActivity?.onBackPressed()
    }

    companion object {
        private const val EXTRA_GUILD_ID = "EXTRA_GUILD_ID"
        private const val EXTRA_STICKER_ID = "EXTRA_STICKER_ID"
        private const val INVALID_ID = -1L

        /** The emoji is not a view, so [StatefulViews] needs a key outside the id space. */
        private const val STATE_ID_TAGS = 90101

        fun launch(context: Context, guildId: Long, stickerId: Long?) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsStickerEdit::class.java,
                Intent()
                    .putExtra(EXTRA_GUILD_ID, guildId)
                    .putExtra(EXTRA_STICKER_ID, stickerId ?: INVALID_ID),
            )
        }
    }
}
