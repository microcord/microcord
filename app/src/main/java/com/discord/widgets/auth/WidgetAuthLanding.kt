package com.discord.widgets.auth

import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreInviteSettings
import com.discord.stores.StoreStream
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.google.android.material.button.MaterialButton
import rx.Subscription

class WidgetAuthLanding : AuthFragment(R.layout.widget_auth_landing, AuthRoute.LANDING) {
    private var landingSubscription: Subscription? = null

    private val landingModel: WidgetAuthLandingViewModel by viewModels {
        val route = (DeepLinkParser.parse(mostRecentIntent.dataString) as? LinkParseResult.Internal)?.route
        val invite = (route as? DeepLinkRoute.Invite)?.let {
            StoreInviteSettings.InviteCode(
                inviteCode = it.code,
                source = it.source ?: StoreInviteSettings.LOCATION_DEEPLINK,
                eventId = it.eventId,
            )
        }
        val guildTemplateCode = (route as? DeepLinkRoute.GuildTemplate)?.code
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WidgetAuthLandingViewModel(
                storeObservable = WidgetAuthLandingViewModel.observeStoreState(invite, guildTemplateCode),
                fetchInvite = { code ->
                    StoreStream.getInstantInvites().fetchInviteIfNotLoaded(code.inviteCode, code.eventId)
                },
                requestConsentRequired = StoreStream.getAuthentication()::requestConsentRequired,
            ) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.auth_landing_login).setOnClickListener {
            actions.navigate(AuthRoute.LOGIN)
        }
    }

    override fun onStart() {
        super.onStart()
        landingSubscription = landingModel.observeViewState().subscribe(::renderLanding)
    }

    override fun onStop() {
        landingSubscription?.unsubscribe()
        landingSubscription = null
        super.onStop()
    }

    private fun renderLanding(state: WidgetAuthLandingViewModel.ViewState) {
        val flipper = view?.findViewById<AppViewFlipper>(R.id.auth_landing_switch_flipper) ?: return
        flipper.displayedChild = if (state is WidgetAuthLandingViewModel.ViewState.Empty) 0 else 1
        when (state) {
            is WidgetAuthLandingViewModel.ViewState.Empty -> Unit
            is WidgetAuthLandingViewModel.ViewState.Invite ->
                requireView().findViewById<AuthInviteInfoView>(R.id.auth_landing_invite_info)
                    .configureInvite(state.invite)
            is WidgetAuthLandingViewModel.ViewState.GuildTemplate ->
                requireView().findViewById<AuthInviteInfoView>(R.id.auth_landing_invite_info)
                    .configureGuildTemplate(state.guildTemplate)
        }
        requireView().findViewById<MaterialButton>(R.id.auth_landing_register).setOnClickListener {
            val error = state.ageGateError
            if (error == null) actions.navigate(AuthRoute.REGISTER_IDENTITY)
            else actions.navigate(
                AuthRoute.AGE_GATED,
                AuthUiState(route = AuthRoute.AGE_GATED, underageMessage = error),
            )
        }
    }
}
