package com.discord.widgets.servers

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import com.discord.R
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener

class RoleColorPickerDialog : ColorPickerDialog(), ColorPickerDialogListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setColorPickerDialogListener(this)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.setButton(
            DialogInterface.BUTTON_NEUTRAL,
            getString(R.string.reset),
        ) { _, _ ->
            sendResult(0)
            dismiss()
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) = sendResult(color and 0xFFFFFF)

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun sendResult(color: Int) {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_COLOR to color))
    }

    companion object {
        const val REQUEST_KEY = "ROLE_COLOR_PICKER_RESULT"
        const val RESULT_COLOR = "RESULT_COLOR"

        fun newInstance(initialColor: Int): RoleColorPickerDialog {
            val configured = ColorPickerDialog.newBuilder()
                .setDialogTitle(R.string.role_color)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setColor(initialColor)
                .setShowAlphaSlider(false)
                .setAllowPresets(false)
                .create()
            return RoleColorPickerDialog().apply { arguments = configured.arguments }
        }
    }
}
