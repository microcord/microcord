package com.discord.app

import android.content.Context
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.theme.MaterialComponentsViewInflater

/**
 * Renders the emphasis and masked-link markup that recovered strings carry, for every `<TextView>`
 * the app inflates.
 *
 * Discord's strings are authored with `**bold**` and `[label](url)` runs. A screen that passes one
 * through [SettingsStrings] gets them; the ~60 that set `android:text` straight from a string
 * resource were printing the asterisks and brackets literally. Doing it here rather than at each
 * call site fixes them all at once, and keeps new layouts correct by default.
 *
 * Only the text the constructor read out of the XML is rewritten. Text assigned later — chat
 * messages, usernames, anything user-authored — is deliberately left alone, so a username
 * containing asterisks is not silently styled.
 *
 * Wired in through the theme's `viewInflaterClass`, so it also covers dialogs and bottom sheets,
 * which do not share [AppFragment]'s base class. It extends Material's inflater rather than
 * AppCompat's because that attribute is single-valued: replacing it with an AppCompat-only inflater
 * would undo the `<Button>` → `MaterialButton` substitutions the theme depends on.
 */
class DiscordViewInflater : MaterialComponentsViewInflater() {

    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView =
        super.createTextView(context, attrs).apply {
            val original = text ?: return@apply
            if (!hasMarkup(original)) return@apply
            val rendered = SettingsStrings.render(context, original)
            text = rendered
            if (rendered.hasLinks()) movementMethod = LinkMovementMethod.getInstance()
        }

    private companion object {
        fun hasMarkup(text: CharSequence) = text.contains("**") || text.contains("](")

        fun CharSequence.hasLinks() =
            this is android.text.Spanned && getSpans(0, length, ClickableSpan::class.java).isNotEmpty()
    }
}
