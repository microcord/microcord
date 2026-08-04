package com.discord.widgets.guilds

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.doAfterTextChanged
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.databinding.WidgetGuildFolderSettingsBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener

class WidgetGuildFolderSettings : AppFragment(R.layout.widget_guild_folder_settings) {

    private val viewModel by appViewModels {
        WidgetGuildFolderSettingsViewModel(
            arguments?.getLong(EXTRA_GUILD_FOLDER_ID)
                ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_FOLDER_ID, -1L),
        )
    }
    private var _binding: WidgetGuildFolderSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetGuildFolderSettingsBinding.bind(view).also { _binding = it }
        setActionBarTitle(R.string.server_folder_settings)
        setActionBarDisplayHomeAsUpEnabled()
        binding.editGuildFolderName.editText?.doAfterTextChanged { viewModel.setName(it.toString()) }
        binding.guildFolderSettingsColor.setOnClickListener { launchColorPicker() }
        binding.guildFolderSettingsSave.setOnClickListener { viewModel.saveFolder() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildFolderSettings::class.java, onNext = ::configureUI)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildFolderSettings::class.java, onNext = ::handleEvent)
    }

    private fun configureUI(state: WidgetGuildFolderSettingsViewModel.ViewState) {
        val binding = _binding ?: return
        val loaded = state as? WidgetGuildFolderSettingsViewModel.ViewState.Loaded ?: return
        val text = loaded.formState.name.orEmpty()
        if (binding.editGuildFolderName.editText?.text?.toString() != text) {
            binding.editGuildFolderName.editText?.setText(text)
            binding.editGuildFolderName.editText?.setSelection(text.length)
        }
        val color = loaded.formState.color?.let { it or -0x1000000 }
            ?: ColorCompat.getThemedColor(binding.guildFolderSettingsColorDisplay, R.attr.color_brand)
        val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.drawable_circle_brand)?.mutate()
        drawable?.let {
            binding.guildFolderSettingsColorDisplay.background =
                DrawableCompat.wrap(it).also { wrapped -> DrawableCompat.setTint(wrapped, color) }
        }
        binding.guildFolderColorName.text = String.format("#%06X", color and 0xFFFFFF)
        binding.guildFolderSettingsSave.visibility = if (loaded.showSave) View.VISIBLE else View.GONE
    }

    private fun launchColorPicker() {
        val state = viewModel.viewState as? WidgetGuildFolderSettingsViewModel.ViewState.Loaded ?: return
        val current = state.formState.color?.let { it or -0x1000000 }
            ?: ColorCompat.getThemedColor(binding.guildFolderSettingsColorDisplay, R.attr.color_brand)
        val dialog = ColorPickerDialog.newBuilder()
            .setDialogTitle(R.string.guild_folder_color)
            .setColor(current)
            .setShowAlphaSlider(false)
            .setAllowPresets(true)
            .create()
            .apply {
                setColorPickerDialogListener(object : ColorPickerDialogListener {
                    override fun onColorSelected(dialogId: Int, color: Int) = viewModel.setColor(color and 0xFFFFFF)
                    override fun onDialogDismissed(dialogId: Int) = Unit
                })
            }
        dialog.show(parentFragmentManager, DIALOG_TAG_COLOR_PICKER)
        parentFragmentManager.executePendingTransactions()
        styleColorPicker(dialog.dialog as? AlertDialog ?: return, dialog)
    }

    private fun styleColorPicker(dialog: AlertDialog, colorPicker: ColorPickerDialog) {
        val background = ContextCompat.getDrawable(requireContext(), R.drawable.rounded_rectangle_8dp_radius)
            ?.mutate()?.also {
                DrawableCompat.setTint(it, ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundPrimary))
            }
        dialog.window?.setBackgroundDrawable(background)
        val decor = dialog.window?.decorView ?: return
        configureDialogActions(dialog, colorPicker)
        val applyColors = { styleDialogText(decor) }
        decor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyColors() }
        applyColors()
    }

    private fun configureDialogActions(dialog: AlertDialog, colorPicker: ColorPickerDialog) {
        val select = dialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
        val custom = dialog.getButton(DialogInterface.BUTTON_NEUTRAL) ?: return
        val panel = select.parent as? LinearLayout ?: return
        val reset = AppCompatButton(requireContext()).apply {
            text = getString(R.string.reset)
            setOnClickListener {
                val color = ColorCompat.getThemedColor(requireContext(), R.attr.color_brand)
                viewModel.setColor(color and 0xFFFFFF)
                colorPicker.dismiss()
            }
        }
        panel.removeAllViews()
        panel.setPadding(12.dp, 8.dp, 12.dp, 12.dp)
        panel.addView(custom, actionLayoutParams())
        panel.addView(reset, actionLayoutParams())
        panel.addView(select, actionLayoutParams())
        styleDialogButton(
            custom,
            R.drawable.drawable_button_grey,
            inset = true,
            backgroundColor = ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundAccent),
        )
        styleDialogButton(reset, null)
        styleDialogButton(select, R.drawable.drawable_uikit_brand_500_button)
    }

    private fun actionLayoutParams() = LinearLayout.LayoutParams(0, 48.dp, 1f).apply {
        marginStart = 4.dp
        marginEnd = 4.dp
    }

    private fun styleDialogButton(
        button: Button?,
        backgroundRes: Int?,
        inset: Boolean = false,
        backgroundColor: Int? = null,
    ) {
        button ?: return
        button.background = backgroundRes?.let { ContextCompat.getDrawable(requireContext(), it)?.mutate() }?.let { drawable ->
            backgroundColor?.let { DrawableCompat.setTint(drawable, it) }
            if (inset) {
                InsetDrawable(
                    drawable,
                    resources.getDimensionPixelSize(R.dimen.uikit_btn_inset_horizontal_material),
                    resources.getDimensionPixelSize(R.dimen.uikit_btn_inset_vertical_material),
                    resources.getDimensionPixelSize(R.dimen.uikit_btn_inset_horizontal_material),
                    resources.getDimensionPixelSize(R.dimen.uikit_btn_inset_vertical_material),
                )
            } else {
                drawable
            }
        }
        button.setTextColor(
            if (backgroundRes == null) {
                ColorCompat.getThemedColor(requireContext(), R.attr.colorTextLink)
            } else {
                ContextCompat.getColor(requireContext(), R.color.white)
            },
        )
        button.isAllCaps = false
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.stateListAnimator = null
    }

    private fun styleDialogText(view: View) {
        if (view is TextView && view !is Button) {
            view.setTextColor(ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary))
        }
        if (view is ViewGroup) repeat(view.childCount) { styleDialogText(view.getChildAt(it)) }
    }

    private fun handleEvent(event: WidgetGuildFolderSettingsViewModel.Event) {
        when (event) {
            is WidgetGuildFolderSettingsViewModel.Event.UpdateFolderSettingsFailure ->
                AppToast.show(requireContext(), event.failureMessageStringRes)
            is WidgetGuildFolderSettingsViewModel.Event.UpdateFolderSettingsSuccess -> {
                AppToast.show(requireContext(), event.successMessageStringRes)
                hideKeyboard()
                appActivity?.onBackPressedDispatcher?.onBackPressed()
            }
        }
    }

    companion object {
        private const val EXTRA_GUILD_FOLDER_ID = "com.discord.intent.extra.EXTRA_GUILD_FOLDER_ID"
        private const val DIALOG_TAG_COLOR_PICKER = "DIALOG_TAG_COLOR_PICKER"

        fun create(context: Context, folderId: Long) = AppScreenRouter.launch(
            context,
            WidgetGuildFolderSettings::class.java,
            Intent().putExtra(EXTRA_GUILD_FOLDER_ID, folderId),
        )
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
