package com.discord.widgets.settings

import android.view.View
import androidx.core.view.isVisible
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsBackendBinding
import com.discord.stores.StoreStream
import com.discord.utilities.rest.ClientBackend
import com.discord.utilities.rx.ui
import com.google.android.material.textfield.TextInputLayout
import rx.Observable
import rx.functions.Action1
import rx.schedulers.Schedulers

/**
 * Picks the server the app talks to ([ClientBackend]). Editable only while signed out, because a
 * session token must never reach a backend other than the one that issued it; reachable from the
 * login screen for exactly that reason.
 */
class WidgetSettingsBackend : AppFragment(R.layout.widget_settings_backend) {
    private var custom = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.backend_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        val binding = WidgetSettingsBackendBinding.bind(view)
        val endpoints = with(binding) {
            listOf(settingsBackendApi, settingsBackendGateway, settingsBackendCdn, settingsBackendMedia, settingsBackendWeb)
        }

        val saved = ClientBackend.stored()
        custom = saved != null
        saved?.let { fill(endpoints, it) }
        binding.settingsBackendInstance.editText?.setText(saved?.web)

        val editable = StoreStream.getAuthentication().getAuthState() == null
        binding.settingsBackendSignedIn.isVisible = !editable
        binding.settingsBackendSave.isVisible = editable
        listOf(binding.settingsBackendDiscord, binding.settingsBackendCustom).forEach { it.isEnabled = editable }
        (endpoints + binding.settingsBackendInstance).forEach { it.isEnabled = editable }
        binding.settingsBackendDiscover.isEnabled = editable

        fun render() {
            binding.settingsBackendDiscord.setChecked(!custom, false)
            binding.settingsBackendCustom.setChecked(custom, false)
            binding.settingsBackendFields.isVisible = custom
        }
        binding.settingsBackendDiscord.setOnCheckedListener(Action1 { checked -> if (checked) custom = false; render() })
        binding.settingsBackendCustom.setOnCheckedListener(Action1 { checked -> if (checked) custom = true; render() })
        render()

        binding.settingsBackendDiscover.setOnClickListener {
            val address = binding.settingsBackendInstance.editText?.text?.toString().orEmpty()
            binding.settingsBackendInstance.error = null
            binding.settingsBackendDiscover.isEnabled = false
            Observable.fromCallable { ClientBackend.discover(address) }
                .subscribeOn(Schedulers.io())
                .ui(this)
                .subscribe({ backend ->
                    binding.settingsBackendDiscover.isEnabled = true
                    fill(endpoints, backend)
                }, {
                    binding.settingsBackendDiscover.isEnabled = true
                    binding.settingsBackendInstance.error = getString(R.string.backend_discover_failed)
                })
        }

        binding.settingsBackendSave.setOnClickListener {
            val backend = if (custom) {
                val (api, gateway, cdn, media, web) = endpoints.map { it.editText?.text?.toString()?.trim().orEmpty() }
                ClientBackend.Backend(
                    api = api.trimEnd('/') + "/",
                    cdn = cdn.trimEnd('/'),
                    media = media.ifEmpty { cdn }.trimEnd('/'),
                    gateway = gateway.trimEnd('/'),
                    web = web.trimEnd('/'),
                ).takeIf { it.isValid() }
                    ?: return@setOnClickListener run { binding.settingsBackendApi.error = getString(R.string.backend_invalid) }
            } else {
                null
            }
            if (backend == ClientBackend.stored()) return@setOnClickListener requireActivity().onBackPressedDispatcher.onBackPressed()
            ClientBackend.save(backend)
            ClientBackend.restartProcess(requireContext())
        }
    }

    private fun fill(endpoints: List<TextInputLayout>, backend: ClientBackend.Backend) {
        listOf(backend.api, backend.gateway, backend.cdn, backend.media, backend.web)
            .zip(endpoints)
            .forEach { (value, field) -> field.editText?.setText(value) }
    }
}
