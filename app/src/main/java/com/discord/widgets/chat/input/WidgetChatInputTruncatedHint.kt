package com.discord.widgets.chat.input

import android.text.TextUtils
import androidx.fragment.app.Fragment
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
 * [previousMaxLines] is the value to restore. It is captured at construction and refreshed through
 * [setMaxLines] rather than read back from the view, because while the hint is truncated the view's
 * own `maxLines` is the clamp, not the real value.
 */
class WidgetChatInputTruncatedHint(val editText: FlexEditText) {

    private var hint: CharSequence? = null
    private var hintIsTruncated = false
    private var previousMaxLines: Int = editText.maxLines

    /**
     * Tells the hint what the composer's real line cap is.
     *
     * The reference receives this through a `onMaxLinesChangedListener` on its own fork of
     * `FlexEditText`; the published FlexInput has no such hook, so the composer calls this instead
     * whenever it changes the cap.
     */
    fun setMaxLines(maxLines: Int) {
        if (!hintIsTruncated) previousMaxLines = maxLines
    }

    fun setHint(hint: CharSequence) {
        this.hint = hint
        syncHint()
    }

    /** Re-evaluates on every edit; [Fragment] scoping detaches the watcher with the view. */
    fun addBindedTextWatcher(fragment: Fragment) {
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
