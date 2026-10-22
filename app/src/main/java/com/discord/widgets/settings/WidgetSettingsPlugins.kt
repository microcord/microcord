package com.discord.widgets.settings

import dev.mantikafasi.manticord.plugins.api.PLUGIN_API_LEVEL
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsPluginsBinding
import com.discord.databinding.WidgetSettingsPluginsItemBinding
import com.discord.plugins.PluginIndex
import com.discord.plugins.PluginLoadingPolicy
import com.discord.plugins.PluginRuntime
import com.discord.plugins.PluginSnapshot
import com.discord.plugins.PluginState
import com.discord.plugins.PluginMetrics
import com.discord.plugins.ExternalPluginDescriptor
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.utilities.rx.ui
import com.discord.utilities.color.ColorCompat
import rx.Observable
import rx.schedulers.Schedulers

class WidgetSettingsPlugins : AppFragment(R.layout.widget_settings_plugins) {
    private var _binding: WidgetSettingsPluginsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = PluginAdapter(
        ::togglePlugin,
        { openPlugin(it, WidgetSettingsPlugin.Mode.INFO) },
        { openPlugin(it, WidgetSettingsPlugin.Mode.SETTINGS) },
        { row -> row.packageName?.let { PluginIndex.uninstall(requireContext(), it) } },
    )
    private var externalPlugins: List<ExternalPluginDescriptor> = emptyList()
    private var loadMetrics = PluginMetrics()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsPluginsBinding.bind(view)
        setActionBarTitle(R.string.plugins)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        binding.pluginsList.adapter = adapter
        binding.pluginsMaster.apply {
            setChecked(PluginLoadingPolicy.isEnabled(), false)
            setContainerClickListener {
                val enabled = !isChecked
                setChecked(enabled, true)
                PluginRuntime.setLoadingEnabled(requireContext(), enabled)
                render()
            }
        }
        binding.pluginsBrowse.setOnClickListener {
            parentFragmentManager.commit {
                replace(id, WidgetSettingsPluginBrowser())
                addToBackStack(WidgetSettingsPluginBrowser::class.java.name)
            }
        }
        render()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Re-listed on resume, so a plugin just installed from the browser shows up.
        Observable.fromCallable { PluginRuntime.externalPlugins(requireContext().applicationContext) }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe {
                externalPlugins = it
                render()
            }
        PluginRuntime.observeSnapshots().ui(this).subscribe { render() }
        PluginRuntime.observeMetrics().ui(this).subscribe {
            loadMetrics = it
            render()
        }
    }

    override fun onDestroyView() {
        binding.pluginsList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        if (_binding == null) return
        val snapshots = PluginRuntime.snapshots()
        val byPackage = snapshots.mapNotNull { snapshot -> snapshot.sourcePackage?.let { it to snapshot } }.toMap()
        val rows = buildList {
            externalPlugins.forEach { descriptor ->
                val snapshot = byPackage[descriptor.packageName]
                val packageEnabled = PluginLoadingPolicy.isPackageEnabled(descriptor.packageName)
                val signerApproved = PluginLoadingPolicy.approvedSigner(descriptor.packageName) ==
                    descriptor.signerSha256
                val appCompatible = PLUGIN_API_LEVEL >= descriptor.manifest.manticordVersionMinimum
                add(
                    Row(
                        packageName = descriptor.packageName,
                        pluginId = snapshot?.manifest?.id,
                        title = snapshot?.manifest?.name ?: descriptor.label,
                        status = snapshot?.statusText() ?: when {
                            packageEnabled && !signerApproved -> "Signer changed · approval required"
                            !appCompatible -> "Incompatible Microcord version"
                            PluginRuntime.externalFailure(descriptor.packageName) != null ->
                                "Failed · ${PluginRuntime.externalFailure(descriptor.packageName)}"
                            packageEnabled -> "Loading"
                            else -> "Disabled"
                        },
                        subtitle = "${descriptor.packageName}\nSigner ${shortFingerprint(descriptor.signerSha256)}",
                        enabled = packageEnabled && signerApproved,
                        canOpen = snapshot?.state == PluginState.RUNNING,
                        icon = descriptor.icon,
                        signerSha256 = descriptor.signerSha256,
                    ),
                )
            }
            snapshots.filter { it.sourcePackage == null }.forEach { snapshot ->
                add(
                    Row(
                        null,
                        snapshot.manifest.id,
                        snapshot.manifest.name,
                        snapshot.statusText(),
                        snapshot.manifest.description,
                        PluginLoadingPolicy.isPluginEnabled(snapshot.manifest.id),
                        snapshot.state == PluginState.RUNNING,
                        null,
                        null,
                    ),
                )
            }
        }
        adapter.rows = rows
        binding.pluginsMetrics.text = getString(
            R.string.plugins_metrics,
            snapshots.count { it.state == PluginState.RUNNING },
            loadMetrics.discoveryDurationMs,
            loadMetrics.resolutionDurationMs,
            loadMetrics.totalStartupDurationMs?.let { "$it ms" } ?: "running",
        )
        binding.pluginsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun togglePlugin(row: Row) {
        val packageName = row.packageName
        if (packageName == null) {
            row.pluginId?.let { PluginRuntime.setPluginEnabled(requireContext(), it, !row.enabled) }
            render()
            return
        }
        if (row.enabled) {
            PluginRuntime.setPackageEnabled(requireContext(), packageName, false)
            render()
            return
        }
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(row.title)
            .setMessage(R.string.plugins_external_warning)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.enable) {
                PluginRuntime.setPackageEnabled(requireContext(), packageName, true, row.signerSha256)
                render()
            }
            .show(parentFragmentManager)
    }

    private fun openPlugin(row: Row, mode: WidgetSettingsPlugin.Mode) {
        val pluginId = row.pluginId ?: return
        if (!row.enabled || !row.canOpen) return
        val fragment = WidgetSettingsPlugin.create(pluginId, mode)
        parentFragmentManager.commit {
            replace(id, fragment)
            addToBackStack(WidgetSettingsPlugin::class.java.name)
        }
    }

    private fun PluginSnapshot.statusText(): String = buildString {
        append(manifest.versionName)
        append(" · ")
        append(state.name.lowercase().replaceFirstChar(Char::uppercase))
        if (codeLoadDurationMs > 0L) append(" · code ").append(codeLoadDurationMs).append(" ms")
        startDurationMs?.let { append(" · start ").append(it).append(" ms") }
        error?.let { append(" · ").append(it) }
    }

    private fun shortFingerprint(value: String): String = when {
        value.length <= 20 -> value
        else -> value.take(11) + "…" + value.takeLast(5)
    }

    private data class Row(
        val packageName: String?,
        val pluginId: String?,
        val title: String,
        val status: String,
        val subtitle: String,
        val enabled: Boolean,
        val canOpen: Boolean,
        val icon: Drawable?,
        val signerSha256: String?,
    )

    private class PluginAdapter(
        private val onToggle: (Row) -> Unit,
        private val onInfo: (Row) -> Unit,
        private val onSettings: (Row) -> Unit,
        private val onUninstall: (Row) -> Unit,
    ) : RecyclerView.Adapter<PluginAdapter.Holder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetSettingsPluginsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        inner class Holder(private val binding: WidgetSettingsPluginsItemBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row) {
                binding.pluginItemTitle.text = row.title
                binding.pluginItemStatus.text = row.status
                binding.pluginItemSubtitle.text = row.subtitle
                if (row.icon != null) {
                    ImageViewCompat.setImageTintList(binding.pluginItemIcon, null)
                    binding.pluginItemIcon.setImageDrawable(row.icon)
                } else {
                    binding.pluginItemIcon.setImageResource(R.drawable.ic_security_24dp)
                    ImageViewCompat.setImageTintList(
                        binding.pluginItemIcon,
                        ColorStateList.valueOf(
                            ColorCompat.getThemedColor(binding.root.context, R.attr.colorInteractiveNormal),
                        ),
                    )
                }
                binding.pluginItemSwitch.setOnCheckedChangeListener(null)
                binding.pluginItemSwitch.isChecked = row.enabled
                binding.pluginItemSwitch.isEnabled = row.packageName != null || row.pluginId != null
                binding.pluginItemSwitch.setOnCheckedChangeListener { _, checked ->
                    if (checked != row.enabled) onToggle(row)
                }
                binding.pluginItemInfo.visibility = if (row.canOpen) View.VISIBLE else View.GONE
                binding.pluginItemSettings.visibility = if (row.canOpen) View.VISIBLE else View.GONE
                binding.pluginItemInfo.setOnClickListener { onInfo(row) }
                binding.pluginItemSettings.setOnClickListener { onSettings(row) }
                binding.pluginItemUninstall.visibility = if (row.packageName != null) View.VISIBLE else View.GONE
                binding.pluginItemUninstall.setOnClickListener { onUninstall(row) }
            }
        }
    }
}
