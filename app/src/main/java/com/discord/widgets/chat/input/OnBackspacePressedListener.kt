package com.discord.widgets.chat.input

/**
 * Backspace pressed inside an expression picker.
 *
 * The picker has no text of its own — the key has to delete from whatever composer is hosting it —
 * so the host installs this rather than the picker reaching for an `EditText`.
 */
fun interface OnBackspacePressedListener {
    fun onBackspacePressed()
}
