package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import androidx.core.view.children
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetSettingsDesignSystemPageBinding
import com.discord.widgets.hubs.RadioSelectorItem
import com.discord.widgets.hubs.RadioSelectorItems
import com.discord.widgets.hubs.WidgetRadioSelectorBottomSheet
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.button.MaterialButton

class WidgetSettingsDesignSystemPage : AppFragment(R.layout.widget_settings_design_system_page) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetSettingsDesignSystemPageBinding.bind(view)
        val page = Page.entries[mostRecentIntent.getIntExtra(ARG_PAGE, 0)]
        setActionBarTitle(page.title)
        setActionBarDisplayHomeAsUpEnabled()
        if (page == Page.EVERYTHING) {
            Page.entries.mapNotNull { it.view?.invoke(binding) }.forEach { it.visibility = View.VISIBLE }
        } else {
            requireNotNull(page.view).invoke(binding).visibility = View.VISIBLE
        }

        binding.designPageFormSwitch.setChecked(true, false)
        binding.designPageFormCheckbox.setChecked(true, false)
        binding.designPageFormRadio.setChecked(true, false)
        binding.designPageAlertModalButton.setOnClickListener { showDialog("Alert Modal", "This is an alert modal example.") }
        binding.designPageModalButton.setOnClickListener { showDialog("Modal", "This is a standard modal example.") }
        binding.designPageToastButton.setOnClickListener { AppToast.show(requireContext(), "Toast notification") }
        binding.designPageContextButton.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add("Edit")
                menu.add("Copy")
                menu.add("Delete")
                setOnMenuItemClickListener { AppToast.show(requireContext(), it.title); true }
            }.show()
        }
        binding.designPageSheetButton.setOnClickListener {
            WidgetRadioSelectorBottomSheet.show(
                parentFragmentManager,
                RadioSelectorItems("Sheets", listOf(RadioSelectorItem(0, "First option", true), RadioSelectorItem(1, "Second option", false))),
            ) { AppToast.show(requireContext(), "Selected option ${it + 1}") }
        }
        setupHaptics(binding)
    }

    private fun setupHaptics(binding: WidgetSettingsDesignSystemPageBinding) {
        binding.designPageHaptics.children.filterIsInstance<MaterialButton>().forEach { button ->
            val type = button.tag.toString()
            button.isHapticFeedbackEnabled = true
            button.setOnClickListener {
                if (type.startsWith("pattern:")) vibratePattern(type.substringAfter(':'))
                else it.performHapticFeedback(HAPTICS.getValue(type))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibratePattern(name: String) {
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val timings = PATTERNS.getValue(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
            vibrator.vibrate(timings, -1)
        }
    }

    private fun showDialog(title: String, body: String) = WidgetNoticeDialog.show(
        fragmentManager = parentFragmentManager,
        headerText = title,
        bodyText = body,
        okText = getString(R.string.okay),
        cancelText = getString(R.string.cancel),
    )

    enum class Page(val title: String, val view: ((WidgetSettingsDesignSystemPageBinding) -> View)?) {
        EVERYTHING("Everything", null),
        TEXT("Text", { it.designPageText }),
        BUTTON("Button", { it.designPageButton }),
        BUTTON_GROUP("Button Group", { it.designPageButtonGroup }),
        ROW_BUTTON("Row Button", { it.designPageRowButton }),
        TABLE_ROW("Table Row", { it.designPageTableRow }),
        ALERT_MODAL("Alert Modal", { it.designPageAlertModal }),
        SHADOWS("Shadows", { it.designPageShadows }),
        SEGMENTED_CONTROL("Segmented Control", { it.designPageSegmentedControl }),
        TABS("Tabs", { it.designPageTabs }),
        BACKDROP("Backdrop", { it.designPageBackdrop }),
        TOOLTIP("Tooltip", { it.designPageTooltip }),
        COACHMARK("Coachmark", { it.designPageCoachmark }),
        FORM_PRIMITIVES("Form Primitives", { it.designPageFormPrimitives }),
        TEXT_INPUT("Text Input", { it.designPageTextInput }),
        PILE("Pile", { it.designPagePile }),
        HAPTICS("Haptics", { it.designPageHaptics }),
        SHEETS("Sheets", { it.designPageSheets }),
        STACK("Stack", { it.designPageStack }),
        CONTEXT_MENU("Context Menu", { it.designPageContextMenu }),
        TOAST("Toast", { it.designPageToast }),
        MODAL("Modal", { it.designPageModal }),
        BACKGROUND_BLUR("Background Blur View", { it.designPageBackgroundBlur }),
        EXPERIMENTAL_BUTTONS("Experimental Buttons", { it.designPageExperimentalButtons }),
        LEGACY_BUTTON("Legacy Button", { it.designPageLegacyButton }),
    }

    companion object {
        private const val ARG_PAGE = "design_system_page"
        private val HAPTICS = mapOf(
            "IMPACT_LIGHT" to HapticFeedbackConstants.KEYBOARD_TAP,
            "IMPACT_MEDIUM" to HapticFeedbackConstants.VIRTUAL_KEY,
            "IMPACT_HEAVY" to HapticFeedbackConstants.LONG_PRESS,
            "NOTIFICATION_ERROR" to HapticFeedbackConstants.REJECT,
            "DRAG_AND_DROP_START" to HapticFeedbackConstants.DRAG_START,
            "DRAG_AND_DROP_END" to HapticFeedbackConstants.GESTURE_END,
            "DRAG_AND_DROP_MOVE" to HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
            "SOFT" to HapticFeedbackConstants.CLOCK_TICK,
            "SELECTION" to HapticFeedbackConstants.SEGMENT_TICK,
            "RIGID" to HapticFeedbackConstants.CONTEXT_CLICK,
            "NOTIFICATION_SUCCESS" to HapticFeedbackConstants.CONFIRM,
            "NOTIFICATION_WARNING" to HapticFeedbackConstants.GESTURE_START,
            "CONFIRM" to HapticFeedbackConstants.CONFIRM,
            "REJECT" to HapticFeedbackConstants.REJECT,
            "GESTURE_START" to HapticFeedbackConstants.GESTURE_START,
            "GESTURE_END" to HapticFeedbackConstants.GESTURE_END,
            "SEGMENT_TICK" to HapticFeedbackConstants.SEGMENT_TICK,
            "SEGMENT_FREQUENT_TICK" to HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
            "TOGGLE_ON" to HapticFeedbackConstants.TOGGLE_ON,
            "TOGGLE_OFF" to HapticFeedbackConstants.TOGGLE_OFF,
            "CLOCK_TICK" to HapticFeedbackConstants.CLOCK_TICK,
            "CONTEXT_CLICK" to HapticFeedbackConstants.CONTEXT_CLICK,
            "KEYBOARD_PRESS" to HapticFeedbackConstants.KEYBOARD_TAP,
            "KEYBOARD_RELEASE" to HapticFeedbackConstants.KEYBOARD_RELEASE,
            "KEYBOARD_TAP" to HapticFeedbackConstants.KEYBOARD_TAP,
            "LONG_PRESS" to HapticFeedbackConstants.LONG_PRESS,
            "TEXT_HANDLE_MOVE" to HapticFeedbackConstants.TEXT_HANDLE_MOVE,
            "VIRTUAL_KEY" to HapticFeedbackConstants.VIRTUAL_KEY,
            "VIRTUAL_KEY_RELEASE" to HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
            "EFFECT_CLICK" to HapticFeedbackConstants.CONTEXT_CLICK,
            "EFFECT_DOUBLE_CLICK" to HapticFeedbackConstants.CONFIRM,
            "EFFECT_HEAVY_CLICK" to HapticFeedbackConstants.LONG_PRESS,
            "EFFECT_TICK" to HapticFeedbackConstants.CLOCK_TICK,
        )
        private val PATTERNS = mapOf(
            "success" to longArrayOf(0, 25, 35, 55, 25, 25),
            "error" to longArrayOf(0, 55, 35, 55, 35, 55),
            "warning" to longArrayOf(0, 55, 30, 25),
            "heartbeat" to longArrayOf(0, 25, 20, 55, 120, 25, 20, 55),
            "triple" to longArrayOf(0, 25, 30, 25, 30, 25),
            "notification" to longArrayOf(0, 25, 60, 55, 160, 25),
        )

        fun launch(context: Context, page: Page) = AppScreenRouter.launch(
            context,
            WidgetSettingsDesignSystemPage::class.java,
            Intent().putExtra(ARG_PAGE, page.ordinal),
        )
    }
}
