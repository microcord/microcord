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
import com.discord.i18n.i18nFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RoleColorPickerDialog : AppDialog(R.layout.role_color_picker_dialog) {
    /**
     * Every colour the caller is editing at once, in the order it wants them back.
     *
     * A role picks one, a server tag picks two, and a display-name gradient picks up to five. They
     * differ only in how many tabs sit above the grid, so one list covers all three.
     */
    private var colors: MutableList<Int> = mutableListOf(0)
    private var activeSlot = 0
    private val colorButtons = LinkedHashMap<Int, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: argumentsOrDefault
        colors = source.getIntArray(STATE_COLORS)?.toMutableList()
            ?: argumentsOrDefault.getIntArray(ARG_COLORS)?.toMutableList()
            ?: mutableListOf(
                argumentsOrDefault.getInt(ARG_INITIAL_COLOR),
                *listOfNotNull(
                    argumentsOrDefault.takeIf { it.containsKey(ARG_ACCENT_COLOR) }
                        ?.getInt(ARG_ACCENT_COLOR),
                ).toTypedArray(),
            )
        activeSlot = source.getInt(STATE_SLOT, 0).coerceIn(0, colors.lastIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntArray(STATE_COLORS, colors.toIntArray())
        outState.putInt(STATE_SLOT, activeSlot)
        super.onSaveInstanceState(outState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.role_color_title)
            .setText(argumentsOrDefault.getInt(ARG_TITLE, R.string.role_color))
        val customWrap = view.findViewById<TextInputLayout>(R.id.role_color_custom_wrap)
        val custom = view.findViewById<TextInputEditText>(R.id.role_color_custom)
        configureSlots(view, custom)
        // Any colour already in use joins the palette, so an existing choice is selectable rather
        // than only reachable through the hex field.
        val palette = resources.getIntArray(R.array.color_picker_palette).toMutableList().apply {
            colors.forEach { if (it !in this) add(it) }
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
        if (colors.size < 2) return
        slots.visibility = View.VISIBLE
        // Two slots are a primary and an accent by name; more than that are just numbered, because
        // no gradient stop past the second has a name a reader would recognise.
        colors.indices.forEach { index ->
            val label = when {
                colors.size > 2 ->
                    requireContext().i18nFormat(R.string.display_name_styles_color_slot, "number" to index + 1)
                index == 0 -> getString(R.string.guild_settings_tag_badge_color_primary)
                else -> getString(R.string.guild_settings_tag_badge_color_accent)
            }
            slots.addTab(slots.newTab().setText(label))
        }
        slots.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activeSlot = tab.position
                custom.setText(String.format("#%06X", activeColor() and 0xFFFFFF))
                renderSelection()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun activeColor() = colors.getOrElse(activeSlot) { 0 }

    private fun setActiveColor(color: Int) {
        colors[activeSlot] = color
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
        colors[activeSlot] = color and 0xFFFFFF
        val masked = colors.map { it and 0xFFFFFF }
        val result = bundleOf(
            RESULT_COLOR to masked.first(),
            RESULT_COLORS to masked.toIntArray(),
        )
        masked.getOrNull(1)?.let { result.putInt(RESULT_ACCENT_COLOR, it) }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "ROLE_COLOR_PICKER_RESULT"
        const val RESULT_COLOR = "RESULT_COLOR"
        const val RESULT_ACCENT_COLOR = "RESULT_ACCENT_COLOR"

        /** Every slot, in the order they were given; the only result a caller of [showMulti] needs. */
        const val RESULT_COLORS = "RESULT_COLORS"
        private const val ARG_INITIAL_COLOR = "ARG_INITIAL_COLOR"
        private const val ARG_ACCENT_COLOR = "ARG_ACCENT_COLOR"
        private const val ARG_COLORS = "ARG_COLORS"
        private const val ARG_TITLE = "ARG_TITLE"
        private const val STATE_COLORS = "STATE_COLORS"
        private const val STATE_SLOT = "STATE_SLOT"

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

        /** Edits [colors] as one set, one tab per entry, answering on [RESULT_COLORS]. */
        fun showMulti(
            fragmentManager: FragmentManager,
            colors: List<Int>,
            @StringRes titleRes: Int = R.string.role_color,
        ) {
            RoleColorPickerDialog().apply {
                arguments = Bundle().apply {
                    putIntArray(ARG_COLORS, colors.map { it and 0xFFFFFF }.toIntArray())
                    putInt(ARG_TITLE, titleRes)
                }
            }.show(fragmentManager, RoleColorPickerDialog::class.java.name)
        }
    }
}
