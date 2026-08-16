package com.discord.widgets.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppFragment
import com.discord.databinding.DialogPluginTextSettingBinding
import com.discord.databinding.WidgetSettingsPluginBinding
import com.discord.plugins.PluginRuntime
import com.discord.plugins.PluginUiRegistry
import com.discord.views.CheckedSetting
import com.discord.widgets.hubs.RadioSelectorItem
import com.discord.widgets.hubs.RadioSelectorItems
import com.discord.widgets.hubs.WidgetRadioSelectorBottomSheet
import dev.mantikafasi.manticord.plugins.api.PluginSetting
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginSettings
import com.discord.utilities.rx.ui

class WidgetSettingsPlugin : AppFragment(R.layout.widget_settings_plugin) {
    private val pluginId get() = requireArguments().getString(ARG_PLUGIN_ID).orEmpty()
    private val mode get() = Mode.valueOf(requireArguments().getString(ARG_MODE) ?: Mode.INFO.name)
    private var customSettingsView: View? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val plugin = PluginRuntime.plugin(pluginId) ?: return
        val manifest = PluginRuntime.manifest(pluginId) ?: return
        val settings = PluginRuntime.settings(pluginId) ?: return
        val binding = WidgetSettingsPluginBinding.bind(view)
        setActionBarTitle(manifest.name)
        setActionBarSubtitle(if (mode == Mode.INFO) "Info" else "Settings")
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        render(binding, plugin, settings)
        settings.observeChanges().ui(this).subscribe { render(binding, plugin, settings) }
    }

    override fun onDestroyView() {
        customSettingsView = null
        super.onDestroyView()
    }

    private fun render(
        binding: WidgetSettingsPluginBinding,
        plugin: ManticordPlugin,
        settings: PluginSettings,
    ) {
        binding.pluginSettingsContainer.removeAllViews()
        val inflater = LayoutInflater.from(binding.root.context)
        val entries = runCatching { plugin.settingsSchema?.entries.orEmpty() }.getOrElse {
            addErrorRow(binding, inflater, it)
            emptyList()
        }
        val hasCustomSettingsPage = runCatching { plugin.settingsPage != null }.getOrDefault(false)

        if (mode == Mode.INFO) {
            addSection(binding, inflater, "Plugin info")
            addRow(binding, inflater, "Plugin ID", pluginId)
            addRow(binding, inflater, "Version", PluginRuntime.manifest(pluginId)?.versionName.orEmpty())
            PluginRuntime.manifest(pluginId)?.description?.takeIf(String::isNotBlank)?.let {
                addRow(binding, inflater, "Description", it)
            }
            addSection(binding, inflater, "Added content")
            val content = PluginUiRegistry.content(pluginId)
            content.forEach { item ->
                addRow(
                    binding,
                    inflater,
                    item.type,
                    listOf(item.title, item.detail).filter(String::isNotBlank).joinToString(" · "),
                )
            }
            entries.forEach { entry ->
                val title = when (entry) {
                    is PluginSetting.Section -> entry.title
                    is PluginSetting.Toggle -> entry.title
                    is PluginSetting.Text -> entry.title
                    is PluginSetting.Choice -> entry.title
                    is PluginSetting.Action -> entry.title
                    else -> entry.key.orEmpty()
                }
                addRow(binding, inflater, "Setting", title)
            }
            if (hasCustomSettingsPage) addRow(binding, inflater, "Custom settings page", "Provided")
            if (content.isEmpty() && entries.isEmpty() && !hasCustomSettingsPage) {
                addRow(binding, inflater, "No added content", "This plugin did not register any UI or commands.")
            }
            return
        }

        if (entries.isNotEmpty() || hasCustomSettingsPage) addSection(binding, inflater, "Settings")

        entries.take(MAX_SCHEMA_ENTRIES).forEach { entry ->
            when (entry) {
                is PluginSetting.Section -> binding.pluginSettingsContainer.addView(
                    inflater.inflate(R.layout.widget_settings_plugin_section, binding.pluginSettingsContainer, false)
                        .apply { (this as TextView).text = entry.title },
                )
                is PluginSetting.Toggle -> binding.pluginSettingsContainer.addView(
                    inflater.inflate(R.layout.widget_settings_plugin_toggle, binding.pluginSettingsContainer, false)
                        .apply {
                            this as CheckedSetting
                            setText(entry.title)
                            setSubtext(entry.description)
                            setChecked(settings.getBoolean(entry.key, entry.default), false)
                            setContainerClickListener {
                                val checked = !isChecked
                                setChecked(checked, true)
                                settings.putBoolean(entry.key, checked)
                            }
                        },
                )
                is PluginSetting.Text -> addRow(binding, inflater, entry.title, entry.description) {
                    PluginTextSettingDialog.show(
                        childFragmentManager,
                        pluginId,
                        entry.key,
                        entry.title,
                        settings.getString(entry.key, entry.default),
                    )
                }
                is PluginSetting.Choice -> addRow(
                    binding,
                    inflater,
                    entry.title,
                    entry.options.firstOrNull {
                        it.value == settings.getString(entry.key, entry.default)
                    }?.label.orEmpty(),
                ) {
                    val current = settings.getString(entry.key, entry.default)
                    WidgetRadioSelectorBottomSheet.show(
                        childFragmentManager,
                        RadioSelectorItems(
                            entry.title,
                            entry.options.mapIndexed { index, option ->
                                RadioSelectorItem(index, option.label, option.value == current)
                            },
                        ),
                    ) { selected ->
                        settings.putString(entry.key, entry.options[selected].value)
                        childFragmentManager.findFragmentByTag(WidgetRadioSelectorBottomSheet::class.java.name)
                            ?.let { (it as WidgetRadioSelectorBottomSheet).dismiss() }
                    }
                }
                is PluginSetting.Action -> addRow(binding, inflater, entry.title, entry.description) {
                    runCatching(entry.onClick)
                }
            }
        }
        if (entries.size > MAX_SCHEMA_ENTRIES) {
            addRow(binding, inflater, "Settings truncated", "Only the first $MAX_SCHEMA_ENTRIES entries are shown.") {}
        }
        val custom = customSettingsView ?: runCatching {
            plugin.settingsPage?.createView(requireContext(), binding.pluginSettingsContainer)
        }.onFailure { addErrorRow(binding, inflater, it) }.getOrNull()?.also { customSettingsView = it }
        custom?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            binding.pluginSettingsContainer.addView(it)
        }
    }

    private fun addErrorRow(
        binding: WidgetSettingsPluginBinding,
        inflater: LayoutInflater,
        failure: Throwable,
    ) = addRow(
        binding,
        inflater,
        "Plugin settings error",
        failure.message ?: failure::class.java.simpleName,
    ) {}

    private fun addSection(
        binding: WidgetSettingsPluginBinding,
        inflater: LayoutInflater,
        title: String,
    ) {
        binding.pluginSettingsContainer.addView(
            inflater.inflate(R.layout.widget_settings_plugin_section, binding.pluginSettingsContainer, false)
                .apply { (this as TextView).text = title },
        )
    }

    private fun addRow(
        binding: WidgetSettingsPluginBinding,
        inflater: LayoutInflater,
        title: String,
        description: String,
        action: (() -> Unit)? = null,
    ) {
        binding.pluginSettingsContainer.addView(
            inflater.inflate(R.layout.widget_settings_plugin_row, binding.pluginSettingsContainer, false).apply {
                this as TextView
                text = if (description.isBlank()) title else "$title\n$description"
                action?.let { callback -> setOnClickListener { callback() } }
            },
        )
    }

    companion object {
        private const val ARG_PLUGIN_ID = "plugin_id"
        private const val ARG_MODE = "mode"
        private const val MAX_SCHEMA_ENTRIES = 100

        fun create(pluginId: String, mode: Mode) = WidgetSettingsPlugin().apply {
            arguments = Bundle().apply {
                putString(ARG_PLUGIN_ID, pluginId)
                putString(ARG_MODE, mode.name)
            }
        }
    }

    enum class Mode { INFO, SETTINGS }
}

class PluginTextSettingDialog : AppDialog(R.layout.dialog_plugin_text_setting) {
    override fun onStart() {
        super.onStart()
        val margin = (resources.displayMetrics.density * 16).toInt()
        dialog?.window?.setLayout(
            resources.displayMetrics.widthPixels - margin * 2,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = DialogPluginTextSettingBinding.bind(view)
        val args = requireArguments()
        binding.pluginTextTitle.text = args.getString(ARG_TITLE)
        binding.pluginTextInput.setText(args.getString(ARG_VALUE).orEmpty())
        binding.pluginTextCancel.setOnClickListener { dismiss() }
        binding.pluginTextSave.setOnClickListener {
            PluginRuntime.settings(args.getString(ARG_PLUGIN_ID).orEmpty())
                ?.putString(args.getString(ARG_KEY).orEmpty(), binding.pluginTextInput.text?.toString().orEmpty())
            dismiss()
        }
    }

    companion object {
        private const val ARG_PLUGIN_ID = "plugin_id"
        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_VALUE = "value"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            pluginId: String,
            key: String,
            title: String,
            value: String,
        ) = PluginTextSettingDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PLUGIN_ID, pluginId)
                putString(ARG_KEY, key)
                putString(ARG_TITLE, title)
                putString(ARG_VALUE, value)
            }
            show(fragmentManager, PluginTextSettingDialog::class.java.name)
        }
    }
}
