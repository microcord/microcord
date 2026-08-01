package com.discord.widgets.servers

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RoleColorPickerDialog : AppDialog(R.layout.role_color_picker_dialog) {
    private var selectedColor = 0
    private val colorButtons = LinkedHashMap<Int, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedColor = savedInstanceState?.getInt(STATE_COLOR)
            ?: argumentsOrDefault.getInt(ARG_INITIAL_COLOR)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_COLOR, selectedColor)
        super.onSaveInstanceState(outState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val customWrap = view.findViewById<TextInputLayout>(R.id.role_color_custom_wrap)
        val custom = view.findViewById<TextInputEditText>(R.id.role_color_custom)
        val palette = resources.getIntArray(R.array.color_picker_palette).toMutableList().apply {
            if (selectedColor !in this) add(selectedColor)
        }
        val grid = view.findViewById<GridLayout>(R.id.role_color_presets)
        palette.forEach { color ->
            val button = MaterialButton(requireContext()).apply {
                val size = (48 * resources.displayMetrics.density).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(4, 4, 4, 4)
                }
                insetTop = 4
                insetBottom = 4
                cornerRadius = size
                backgroundTintList = ColorStateList.valueOf(color)
                contentDescription = String.format("#%06X", color and 0xFFFFFF)
                setOnClickListener {
                    selectedColor = color and 0xFFFFFF
                    custom.setText(String.format("#%06X", selectedColor))
                    renderSelection()
                }
            }
            colorButtons[color and 0xFFFFFF] = button
            grid.addView(button)
        }
        custom.setText(String.format("#%06X", selectedColor and 0xFFFFFF))
        custom.doAfterTextChanged {
            val parsed = parseColor(it?.toString())
            customWrap.error = if (it.isNullOrBlank() || parsed != null) null else "#RRGGBB"
            if (parsed != null) {
                selectedColor = parsed
                renderSelection()
            }
        }
        view.findViewById<MaterialButton>(R.id.role_color_reset).setOnClickListener { finish(0) }
        view.findViewById<MaterialButton>(R.id.role_color_cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.role_color_select).setOnClickListener {
            parseColor(custom.text?.toString())?.let(::finish) ?: run { customWrap.error = "#RRGGBB" }
        }
        renderSelection()
    }

    private fun renderSelection() {
        colorButtons.forEach { (color, button) ->
            button.strokeWidth = if (color == selectedColor) (3 * resources.displayMetrics.density).toInt() else 0
            button.strokeColor = ColorStateList.valueOf(Color.WHITE)
            button.isSelected = color == selectedColor
        }
    }

    private fun finish(color: Int) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_COLOR to (color and 0xFFFFFF)),
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "ROLE_COLOR_PICKER_RESULT"
        const val RESULT_COLOR = "RESULT_COLOR"
        private const val ARG_INITIAL_COLOR = "ARG_INITIAL_COLOR"
        private const val STATE_COLOR = "STATE_COLOR"

        internal fun parseColor(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
            return hex.toIntOrNull(16)
        }

        fun newInstance(initialColor: Int) = RoleColorPickerDialog().apply {
            arguments = Bundle().apply { putInt(ARG_INITIAL_COLOR, initialColor and 0xFFFFFF) }
        }

        fun show(fragmentManager: FragmentManager, initialColor: Int) {
            newInstance(initialColor).show(fragmentManager, RoleColorPickerDialog::class.java.name)
        }
    }
}
