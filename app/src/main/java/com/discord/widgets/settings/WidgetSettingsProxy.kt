package com.discord.widgets.settings

import android.view.View
import androidx.core.view.isVisible
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsProxyBinding
import com.discord.utilities.rest.ClientProxy
import com.discord.views.CheckedSetting
import com.google.android.material.snackbar.Snackbar
import rx.functions.Action1

/**
 * Manticord's proxy setting ([ClientProxy]). Reachable from the login screen as well as settings,
 * because a network that blocks Discord also blocks signing in.
 */
class WidgetSettingsProxy : AppFragment(R.layout.widget_settings_proxy) {
    private var selected: ClientProxy.Type? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.proxy_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        val binding = WidgetSettingsProxyBinding.bind(view)

        val current = ClientProxy.current()
        selected = current?.type
        binding.settingsProxyHost.editText?.setText(current?.host)
        binding.settingsProxyPort.editText?.setText(current?.port?.toString())

        val options = listOf<Pair<CheckedSetting, ClientProxy.Type?>>(
            binding.settingsProxyOff to null,
            binding.settingsProxyHttp to ClientProxy.Type.HTTP,
            binding.settingsProxySocks to ClientProxy.Type.SOCKS,
        )
        fun render() {
            options.forEach { (row, type) -> row.setChecked(type == selected, false) }
            binding.settingsProxyAddress.isVisible = selected != null
        }
        options.forEach { (row, type) ->
            row.setOnCheckedListener(Action1 { checked -> if (checked) selected = type; render() })
        }
        render()

        binding.settingsProxySave.setOnClickListener {
            val config = selected?.let { type -> readConfig(binding, type) ?: return@setOnClickListener }
            ClientProxy.save(config)
            Snackbar.make(binding.root, R.string.user_settings_restart_app_mobile, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
                .show()
        }
    }

    /** The typed address, or `null` after flagging whichever field is unusable. */
    private fun readConfig(binding: WidgetSettingsProxyBinding, type: ClientProxy.Type): ClientProxy.Config? {
        val host = binding.settingsProxyHost.editText?.text?.toString()?.trim().orEmpty()
        val port = binding.settingsProxyPort.editText?.text?.toString()?.toIntOrNull()
        binding.settingsProxyHost.error = if (host.isEmpty()) getString(R.string.proxy_invalid_host) else null
        binding.settingsProxyPort.error =
            if (port == null || port !in 1..ClientProxy.MAX_PORT) getString(R.string.proxy_invalid_port) else null
        if (host.isEmpty() || port == null || port !in 1..ClientProxy.MAX_PORT) return null
        return ClientProxy.Config(type, host, port)
    }
}
