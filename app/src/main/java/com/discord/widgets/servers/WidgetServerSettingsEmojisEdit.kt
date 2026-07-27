package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Renames or deletes one custom emoji.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsEmojisEdit`. Everything it needs
 * arrives in the intent — guild, emoji id and current alias — so the screen has no store
 * subscription of its own; the emoji list is refreshed after a successful rename instead.
 *
 * Two behaviours reproduced deliberately:
 * - **The alias is sanitised, not validated.** Discord requires 2–32 characters of `[A-Za-z0-9_]`,
 *   and rather than rejecting bad input the reference strips every disallowed character and then
 *   pads with `_` until the name is at least two long. Typing `!` therefore saves as `__`.
 * - **Delete removes the row locally before the gateway confirms.** `StoreEmojiGuild.deleteEmoji`
 *   drops it optimistically so the list the user returns to is already correct.
 */
class WidgetServerSettingsEmojisEdit :
    AppFragment(R.layout.widget_server_settings_emojis_edit) {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_ID)

    private val emojiId: Long
        get() = arguments?.getLong(EXTRA_EMOJI_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_EMOJI_ID, INVALID_ID)

    private val emojiName: String
        get() = arguments?.getString(EXTRA_EMOJI_ALIAS)
            ?: mostRecentIntent.getStringExtra(EXTRA_EMOJI_ALIAS)
            ?: ""

    private val state = StatefulViews(R.id.server_settings_emojis_edit_alias)

    private lateinit var alias: TextInputLayout
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        alias = view.findViewById(R.id.server_settings_emojis_edit_alias)
        save = view.findViewById(R.id.server_settings_emojis_edit_alias_save)

        // `state.get` hands back the pending edit if there is one, so a rotation keeps what was
        // typed rather than snapping back to the name the intent carried.
        alias.setText(state.get(alias.id, emojiName))
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, alias)
        state.configureSaveActionView(save)
        save.setOnClickListener { handleSaveAlias() }

        configureToolbar(emojiName)
        configureMenu()

        // Entering the screen puts the cursor straight in the field; coming back from a rotation
        // does not, because the keyboard state is already whatever the user left it as.
        if (!isRecreated) showKeyboard(alias)
    }

    private fun configureToolbar(emojiName: String) {
        setActionBarTitle(R.string.emoji)
        setActionBarSubtitle(emojiName)
    }

    private fun configureMenu() {
        setActionBarOptionsMenu(
            R.menu.menu_server_settings_emojis_edit,
            onSelected = { menuItem ->
                if (menuItem.itemId == R.id.menu_server_settings_emoji_delete) deleteEmoji()
            },
        )
    }

    private fun handleSaveAlias() {
        val api = RestAPI.apiOrNull ?: return
        val sanitizedName = sanitizeEmojiName(alias.getTextOrEmpty())
        api.patchGuildEmoji(guildId, emojiId, RestAPIParams.PatchGuildEmoji(sanitizedName))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEmojisEdit::class.java, context) { _: ModelEmojiGuild ->
                onSaveSuccess(sanitizedName)
            }
    }

    private fun onSaveSuccess(sanitizedName: String) {
        hideKeyboard()
        configureToolbar(sanitizedName)
        // The list screen's store copy still holds the old name.
        StoreStream.getGuildEmojis().fetchGuildEmoji(guildId)
        // The sanitised name is the new baseline, so the save FAB hides and the field shows what
        // was actually stored rather than what was typed.
        state.put(alias.id, sanitizedName)
        alias.setText(state.get(alias.id, sanitizedName))
        AppToast.show(this, R.string.save_media_success_mobile)
    }

    private fun deleteEmoji() {
        val api = RestAPI.apiOrNull ?: return
        hideKeyboard()
        // Dropping pending edits first stops the unsaved-changes prompt from firing on the way out.
        state.clear()
        api.deleteGuildEmoji(guildId, emojiId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEmojisEdit::class.java, context) {
                StoreStream.getGuildEmojis().deleteEmoji(guildId, emojiId)
                appActivity?.onBackPressed()
            }
    }

    /**
     * Strips everything Discord disallows and pads to the two-character minimum.
     *
     * Faithful to the reference: an alias of nothing but punctuation becomes `__` rather than being
     * rejected.
     */
    private fun sanitizeEmojiName(name: String): String {
        var sanitized = DISALLOWED_EMOJI_NAME_CHARS.replace(name, "")
        while (sanitized.length < MIN_EMOJI_NAME_LENGTH) sanitized += "_"
        return sanitized
    }

    companion object {
        private const val EXTRA_GUILD_ID = "EXTRA_GUILD_ID"
        private const val EXTRA_EMOJI_ID = "EXTRA_EMOJI_ID"
        private const val EXTRA_EMOJI_ALIAS = "EXTRA_EMOJI_ALIAS"
        private const val INVALID_ID = -1L

        private const val MIN_EMOJI_NAME_LENGTH = 2

        /**
         * Anything outside `[A-Za-z0-9_]`, spelled out rather than as `\w` so the class cannot
         * widen under a Unicode-aware regex flag. This is the reference's own pattern.
         */
        private val DISALLOWED_EMOJI_NAME_CHARS = Regex("[^A-Za-z0-9_]")

        fun newInstance(guildId: Long, emojiId: Long, alias: String) =
            WidgetServerSettingsEmojisEdit().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_EMOJI_ID, emojiId)
                    putString(EXTRA_EMOJI_ALIAS, alias)
                }
            }

        fun launch(context: Context, guildId: Long, emojiId: Long, alias: String) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEmojisEdit::class.java,
                Intent()
                    .putExtra(EXTRA_GUILD_ID, guildId)
                    .putExtra(EXTRA_EMOJI_ID, emojiId)
                    .putExtra(EXTRA_EMOJI_ALIAS, alias),
            )
        }
    }
}
