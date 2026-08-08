package com.discord.widgets.servers

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RoleColorPickerDialog : AppDialog(R.layout.role_color_picker_dialog) {
    private var selectedColor = 0
    private var accentColor: Int? = null
    private var editingAccent = false
    private val colorButtons = LinkedHashMap<Int, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedColor = savedInstanceState?.getInt(STATE_COLOR)
            ?: argumentsOrDefault.getInt(ARG_INITIAL_COLOR)
        accentColor = (savedInstanceState ?: argumentsOrDefault)
            .takeIf { it.containsKey(ARG_ACCENT_COLOR) }?.getInt(ARG_ACCENT_COLOR)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_COLOR, selectedColor)
        accentColor?.let { outState.putInt(ARG_ACCENT_COLOR, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.role_color_title)
            .setText(argumentsOrDefault.getInt(ARG_TITLE, R.string.role_color))
        val customWrap = view.findViewById<TextInputLayout>(R.id.role_color_custom_wrap)
        val custom = view.findViewById<TextInputEditText>(R.id.role_color_custom)
        configureSlots(view, custom)
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
                    setActiveColor(color and 0xFFFFFF)
                    custom.setText(String.format("#%06X", activeColor()))
                    renderSelection()
                }
            }
            colorButtons[color and 0xFFFFFF] = button
            grid.addView(button)
        }
        custom.setText(String.format("#%06X", activeColor() and 0xFFFFFF))
        custom.doAfterTextChanged {
            val parsed = parseColor(it?.toString())
            customWrap.error = if (it.isNullOrBlank() || parsed != null) null else "#RRGGBB"
            if (parsed != null) {
                setActiveColor(parsed)
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

    /**
     * Which of the two colours the grid and hex field are editing. Shown only when the caller
     * supplied an accent, i.e. for artwork that actually has a second colour to set.
     */
    private fun configureSlots(view: View, custom: TextInputEditText) {
        val slots = view.findViewById<TabLayout>(R.id.role_color_slot)
        if (accentColor == null) return
        slots.visibility = View.VISIBLE
        slots.addTab(slots.newTab().setText(R.string.guild_settings_tag_badge_color_primary))
        slots.addTab(slots.newTab().setText(R.string.guild_settings_tag_badge_color_accent))
        slots.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                editingAccent = tab.position == 1
                custom.setText(String.format("#%06X", activeColor() and 0xFFFFFF))
                renderSelection()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun activeColor() = if (editingAccent) accentColor ?: 0 else selectedColor

    private fun setActiveColor(color: Int) {
        if (editingAccent) accentColor = color else selectedColor = color
    }

    private fun renderSelection() {
        val active = activeColor()
        colorButtons.forEach { (color, button) ->
            button.strokeWidth = if (color == active) (3 * resources.displayMetrics.density).toInt() else 0
            button.strokeColor = ColorStateList.valueOf(Color.WHITE)
            button.isSelected = color == active
        }
    }

    private fun finish(color: Int) {
        val result = bundleOf(RESULT_COLOR to (color and 0xFFFFFF))
        accentColor?.let { result.putInt(RESULT_ACCENT_COLOR, it and 0xFFFFFF) }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "ROLE_COLOR_PICKER_RESULT"
        const val RESULT_COLOR = "RESULT_COLOR"
        const val RESULT_ACCENT_COLOR = "RESULT_ACCENT_COLOR"
        private const val ARG_INITIAL_COLOR = "ARG_INITIAL_COLOR"
        private const val ARG_ACCENT_COLOR = "ARG_ACCENT_COLOR"
        private const val ARG_TITLE = "ARG_TITLE"
        private const val STATE_COLOR = "STATE_COLOR"

        internal fun parseColor(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
            return hex.toIntOrNull(16)
        }

        @JvmOverloads
        fun newInstance(
            initialColor: Int,
            @StringRes titleRes: Int = R.string.role_color,
            accentColor: Int? = null,
        ) = RoleColorPickerDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_INITIAL_COLOR, initialColor and 0xFFFFFF)
                putInt(ARG_TITLE, titleRes)
                accentColor?.let { putInt(ARG_ACCENT_COLOR, it and 0xFFFFFF) }
            }
        }

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            initialColor: Int,
            @StringRes titleRes: Int = R.string.role_color,
            accentColor: Int? = null,
        ) {
            newInstance(initialColor, titleRes, accentColor)
                .show(fragmentManager, RoleColorPickerDialog::class.java.name)
        }
    }
}
