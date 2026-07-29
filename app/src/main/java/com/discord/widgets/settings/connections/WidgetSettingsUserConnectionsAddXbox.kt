package com.discord.widgets.settings.connections

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast
import com.discord.R
import com.discord.app.AppComponent
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.error.RestApiError
import com.discord.utilities.platform.Platform
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.views.CodeVerificationView
import com.discord.widgets.settings.SettingsNavigation

class WidgetSettingsUserConnectionsAddXbox : AppFragment(R.layout.widget_connections_add_xbox) {
    private lateinit var code: CodeVerificationView
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        code = view.findViewById(R.id.connections_xbox_code)
        dimmer = view.findViewById(R.id.dimmer_view)
        setActionBarTitle(R.string.connected_accounts)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        view.findViewById<View>(R.id.connections_xbox_login).setOnClickListener {
            StoreStream.getUserConnections().authorizeConnection(Platform.XBOX.platformId, it.context)
        }
        code.onCodeEntered = { verificationCode ->
            if (verificationCode.length == PIN_LENGTH) {
                submitPinCode(verificationCode, Platform.XBOX, dimmer, this, activity, ::showPinError)
            }
        }
    }

    fun submitPinCode(
        verificationCode: String,
        platform: Platform,
        dimmer: DimmerView?,
        appComponent: AppComponent,
        activity: Activity?,
        errorHandler: (RestApiError) -> Unit,
    ) {
        RestAPI.api.getConnectionState(platform.platformId, verificationCode)
            .restSubscribeOn()
            .flatMap { state ->
                RestAPI.api.submitConnectionState(
                    platform.platformId,
                    RestAPIParams.ConnectionState(state.code, state.state, true, true),
                )
            }
            .ui(appComponent)
            .withDimmer(dimmer)
            .appSubscribe(
                errorClass = javaClass,
                errorHandler = errorHandler,
                onCompleted = { activity?.finish() },
                onNext = {},
            )
    }

    private fun showPinError(error: RestApiError) {
        error.showErrorToasts = false
        Toast.makeText(requireContext(), R.string.connection_invalid_pin, Toast.LENGTH_SHORT).show()
        code.clearCode()
    }

    companion object {
        private const val PIN_LENGTH = 6

        fun launch(context: Context) = SettingsNavigation.launch(context, WidgetSettingsUserConnectionsAddXbox::class.java)
    }
}
