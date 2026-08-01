package com.discord.widgets.guilds

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
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
            ?: ColorCompat.getThemedColor(binding.guildFolderSettingsColorDisplay, R.attr.colorBackgroundAccent)
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
            ?: ColorCompat.getThemedColor(binding.guildFolderSettingsColorDisplay, R.attr.colorBackgroundAccent)
        ColorPickerDialog.newBuilder()
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
            .show(parentFragmentManager, DIALOG_TAG_COLOR_PICKER)
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
}
