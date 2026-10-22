package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.databinding.WidgetSettingsPluginBrowserBinding
import com.discord.databinding.WidgetSettingsPluginBrowserItemBinding
import com.discord.plugins.PluginIndex
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter
import dev.mantikafasi.manticord.plugins.api.PLUGIN_API_LEVEL
import java.io.File

/**
 * Lists the plugins in the official index and installs or updates them.
 *
 * Installing downloads the APK, checks it against the index (bytes, package name and signer), approves
 * that signer, and hands the file to Android's installer; the plugin then loads like any other.
 */
class WidgetSettingsPluginBrowser : AppFragment(R.layout.widget_settings_plugin_browser) {
    private var binding: WidgetSettingsPluginBrowserBinding? = null
    private var entries: List<PluginIndex.Entry> = emptyList()
    private var installing: String? = null
    private val adapter = SimpleRecyclerAdapter<PluginIndex.Entry, EntryHolder> { inflater, parent ->
        EntryHolder(inflater, parent)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetSettingsPluginBrowserBinding.bind(view).also { this.binding = it }
        setActionBarTitle(R.string.plugins_browse)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        binding.pluginBrowserList.adapter = adapter
        binding.pluginBrowserStatus.setOnClickListener { load() }
        load()
    }

    /** Installed versions change while the system installer is on top. */
    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        adapter.setData(entries)
    }

    override fun onDestroyView() {
        binding?.pluginBrowserList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun load() {
        showStatus(R.string.plugin_browser_loading)
        PluginIndex.fetch().ui(this).subscribe({ loaded ->
            entries = loaded.sortedBy { it.name?.lowercase() }
            showStatus(if (entries.isEmpty()) R.string.plugin_browser_empty else null)
            adapter.setData(entries)
        }, {
            showStatus(R.string.plugin_browser_failed)
        })
    }

    private fun showStatus(text: Int?) {
        val status = binding?.pluginBrowserStatus ?: return
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) status.setText(text)
    }

    private fun install(entry: PluginIndex.Entry) {
        val context = context ?: return
        if (!canInstallPackages(context)) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        installing = entry.packageName
        adapter.setData(entries)
        PluginIndex.download(context.applicationContext, entry).ui(this).subscribe({ file ->
            installing = null
            adapter.setData(entries)
            openInstaller(context, file)
        }, {
            installing = null
            adapter.setData(entries)
            AppToast.show(context, R.string.plugin_browser_install_failed)
        })
    }

    private fun openInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun canInstallPackages(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private inner class EntryHolder(inflater: LayoutInflater, parent: ViewGroup) :
        SimpleRecyclerAdapter.ViewHolder<PluginIndex.Entry>(
            WidgetSettingsPluginBrowserItemBinding.inflate(inflater, parent, false).root,
        ) {
        private val item = WidgetSettingsPluginBrowserItemBinding.bind(itemView)

        override fun bind(data: PluginIndex.Entry) {
            item.pluginBrowserItemTitle.text = data.name
            item.pluginBrowserItemMeta.text = listOfNotNull(
                data.authors?.takeIf { it.isNotEmpty() }?.joinToString(),
                data.versionName,
            ).joinToString(" · ")
            val installed = PluginIndex.installedVersion(itemView.context, requireNotNull(data.packageName))
            val foreign = installed != null && PluginIndex.hasForeignSigner(itemView.context, data)
            val description = listOfNotNull(
                data.description?.takeIf { it.isNotBlank() },
                itemView.context.getString(R.string.plugin_browser_foreign_signer).takeIf { foreign },
            ).joinToString("\n\n")
            item.pluginBrowserItemDescription.text = description
            item.pluginBrowserItemDescription.visibility = if (description.isEmpty()) View.GONE else View.VISIBLE

            val compatible = data.pluginApiMinimum <= PLUGIN_API_LEVEL
            val action = item.pluginBrowserItemAction
            val upToDate = installed != null && (foreign || installed >= data.versionCode)
            val label = when {
                upToDate -> R.string.plugin_browser_uninstall
                !compatible -> R.string.plugin_browser_incompatible
                installed == null -> R.string.plugin_browser_install
                else -> R.string.plugin_browser_update
            }
            action.setText(itemView.context.getString(label))
            action.setBackgroundColor(
                ColorCompat.getColor(itemView.context, if (upToDate) R.color.status_red_500 else R.color.brand_500),
            )
            action.isEnabled = (upToDate || compatible) && installing == null
            action.setIsLoading(installing == data.packageName)
            action.setOnClickListener {
                if (upToDate) PluginIndex.uninstall(itemView.context, data.packageName) else install(data)
            }
        }
    }
}
