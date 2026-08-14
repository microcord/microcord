package com.discord.widgets.settings

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsDesignSystemsBinding

class WidgetSettingsThemeComponentGallery : AppFragment(R.layout.widget_settings_design_systems) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetSettingsDesignSystemsBinding.bind(view)
        setActionBarTitle(R.string.design_systems)
        setActionBarDisplayHomeAsUpEnabled()

        listOf(
            binding.designSystemEverything to WidgetSettingsDesignSystemPage.Page.EVERYTHING,
            binding.designSystemText to WidgetSettingsDesignSystemPage.Page.TEXT,
            binding.designSystemButton to WidgetSettingsDesignSystemPage.Page.BUTTON,
            binding.designSystemButtonGroup to WidgetSettingsDesignSystemPage.Page.BUTTON_GROUP,
            binding.designSystemRowButton to WidgetSettingsDesignSystemPage.Page.ROW_BUTTON,
            binding.designSystemTableRow to WidgetSettingsDesignSystemPage.Page.TABLE_ROW,
            binding.designSystemAlertModal to WidgetSettingsDesignSystemPage.Page.ALERT_MODAL,
            binding.designSystemShadows to WidgetSettingsDesignSystemPage.Page.SHADOWS,
            binding.designSystemSegmentedControl to WidgetSettingsDesignSystemPage.Page.SEGMENTED_CONTROL,
            binding.designSystemTabs to WidgetSettingsDesignSystemPage.Page.TABS,
            binding.designSystemBackdrop to WidgetSettingsDesignSystemPage.Page.BACKDROP,
            binding.designSystemTooltip to WidgetSettingsDesignSystemPage.Page.TOOLTIP,
            binding.designSystemCoachmark to WidgetSettingsDesignSystemPage.Page.COACHMARK,
            binding.designSystemFormPrimitives to WidgetSettingsDesignSystemPage.Page.FORM_PRIMITIVES,
            binding.designSystemTextInput to WidgetSettingsDesignSystemPage.Page.TEXT_INPUT,
            binding.designSystemPile to WidgetSettingsDesignSystemPage.Page.PILE,
            binding.designSystemHaptics to WidgetSettingsDesignSystemPage.Page.HAPTICS,
            binding.designSystemSheets to WidgetSettingsDesignSystemPage.Page.SHEETS,
            binding.designSystemStack to WidgetSettingsDesignSystemPage.Page.STACK,
            binding.designSystemContextMenu to WidgetSettingsDesignSystemPage.Page.CONTEXT_MENU,
            binding.designSystemToast to WidgetSettingsDesignSystemPage.Page.TOAST,
            binding.designSystemModal to WidgetSettingsDesignSystemPage.Page.MODAL,
            binding.designSystemBackgroundBlur to WidgetSettingsDesignSystemPage.Page.BACKGROUND_BLUR,
            binding.designSystemExperimentalButtons to WidgetSettingsDesignSystemPage.Page.EXPERIMENTAL_BUTTONS,
            binding.designSystemLegacyButton to WidgetSettingsDesignSystemPage.Page.LEGACY_BUTTON,
        ).forEach { (row, page) ->
            row.setOnClickListener { WidgetSettingsDesignSystemPage.launch(requireContext(), page) }
        }
    }
}
