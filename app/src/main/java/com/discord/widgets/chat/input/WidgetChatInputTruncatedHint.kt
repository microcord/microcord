package com.discord.widgets.chat.input

import android.text.TextUtils
import com.discord.app.AppFragment
import com.discord.utilities.view.text.addBindedTextWatcher
import com.lytefast.flexinput.widget.FlexEditText

/**
 * Keeps the composer's placeholder on one ellipsised line without capping the composer itself.
 *
 * A channel hint such as "Message #a-very-long-channel-name" wraps the empty composer to two or
 * three lines and shoves the whole input bar up the screen. Clamping `maxLines` to 1 fixes that,
 * but clamping it permanently would also stop a long message from expanding — so the clamp is
 * applied only while the composer is empty and lifted the moment anything is typed.
 *
 * The reference's private FlexInput fork reports `maxLines` changes directly. Public FlexInput does
 * not, so layout changes refresh the value to restore while the real line cap is active.
 */
class WidgetChatInputTruncatedHint(val editText: FlexEditText) {

    private var hint: CharSequence? = null
    private var hintIsTruncated = false
    private var previousMaxLines: Int = editText.maxLines

    init {
        editText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!hintIsTruncated) previousMaxLines = editText.maxLines
        }
    }

    fun setHint(hint: CharSequence) {
        this.hint = hint
        syncHint()
    }

    fun addBindedTextWatcher(fragment: AppFragment) {
        editText.addBindedTextWatcher(fragment) { syncHint() }
    }

    private fun syncHint() {
        hintIsTruncated = editText.length() == 0
        if (hintIsTruncated) {
            editText.maxLines = 1
            editText.ellipsize = TextUtils.TruncateAt.END
            editText.hint = hint
        } else {
            editText.maxLines = previousMaxLines
            editText.ellipsize = null
            // Clearing the hint while there is text avoids it flashing behind a partial deletion.
            editText.hint = null
        }
    }
}
