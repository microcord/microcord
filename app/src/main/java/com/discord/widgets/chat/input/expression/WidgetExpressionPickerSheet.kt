package com.discord.widgets.chat.input.expression

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import com.discord.app.AppBottomSheet

/**
 * Base class for the three full-screen expression search sheets (emoji, GIF, sticker).
 *
 * It carries the one behaviour they share and that the tray depends on: telling the tray when the
 * sheet was dismissed *without* a selection, so the tray can put the keyboard back. Sheets are
 * always fully expanded — a collapsed peek would hide the search field the sheet exists for.
 */
abstract class WidgetExpressionPickerSheet : AppBottomSheet() {

    private var onCancel: (() -> Unit)? = null

    fun setOnCancel(onCancel: (() -> Unit)? = null) {
        this.onCancel = onCancel
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onCancel?.invoke()
    }
}
