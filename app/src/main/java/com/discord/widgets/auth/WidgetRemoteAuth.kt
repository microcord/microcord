package com.discord.widgets.auth

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Confirmation screen for a QR-code login started on another device.
 *
 * Reached by scanning a `/ra/<fingerprint>` code in [com.discord.widgets.media.WidgetQRScanner], or
 * by following the same link from outside the app. Four states share one flipper: the pending
 * confirmation, "that code is no longer valid", a spinner, and the success screen.
 *
 * Backing out of the pending state **cancels** the handshake rather than leaving it open, so a code
 * the user decided not to approve cannot be approved later by accident.
 *
 * Reference: `com.discord.widgets.auth.WidgetRemoteAuth`.
 */
class WidgetRemoteAuth : AppFragment(R.layout.widget_remote_auth) {

    private val viewModel: WidgetRemoteAuthViewModel by lazy {
        val fingerprint = arguments?.getString(EXTRA_REMOTE_AUTH_FINGERPRINT)
            ?: mostRecentIntent.getStringExtra(EXTRA_REMOTE_AUTH_FINGERPRINT)
            ?: ""
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WidgetRemoteAuthViewModel(fingerprint) as T
            },
        ).get(WidgetRemoteAuthViewModel::class.java)
    }

    private lateinit var flipper: AppViewFlipper

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        flipper = view.findViewById(R.id.remote_auth_view_flipper)
        // Leaving by the system back button has to revoke the handshake too, or a code the user
        // declined stays live on the waiting device until it expires on its own.
        setOnBackPressed(handler = {
            viewModel.cancelLogin()
            false
        })
    }

    /**
     * Resubscribes on every resume, because [AppFragment.unsubscribeSignal] fires on pause and the
     * state this screen waits for arrives while it is not in front: verifying MFA sends the user to
     * their authenticator, and the login completes in that window. The state is a `BehaviorSubject`,
     * so resubscribing replays whatever was decided while we were away.
     */
    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(errorClass = WidgetRemoteAuth::class.java, context = context, onNext = ::configureUI)
    }

    private fun configureUI(viewState: WidgetRemoteAuthViewModel.ViewState) {
        val view = view ?: return
        when (viewState) {
            WidgetRemoteAuthViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING

            WidgetRemoteAuthViewModel.ViewState.Failed -> {
                flipper.displayedChild = VIEW_NOT_FOUND
                view.findViewById<MaterialButton>(R.id.remote_auth_not_found_cancel_button)
                    .setOnClickListener { close() }
            }

            WidgetRemoteAuthViewModel.ViewState.Succeeded -> {
                flipper.displayedChild = VIEW_SUCCEEDED
                view.findViewById<MaterialButton>(R.id.remote_auth_finish_button)
                    .setOnClickListener { close() }
            }

            is WidgetRemoteAuthViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_LOADED
                view.findViewById<MaterialButton>(R.id.remote_auth_login_button).apply {
                    isEnabled = viewState.loginAllowed
                    if (viewState.loginAllowed) setOnClickListener { viewModel.remoteLogin() }
                }
                view.findViewById<MaterialButton>(R.id.remote_auth_cancel_button).setOnClickListener {
                    viewModel.cancelLogin()
                    close()
                }
                view.findViewById<SwitchMaterial>(R.id.remote_auth_temporary_switch).apply {
                    // The switch reads "stay logged in", i.e. the inverse of `isTemporary`. Detach
                    // the listener first so applying state does not look like a user toggle.
                    setOnCheckedChangeListener(null)
                    isChecked = !viewState.isTemporary
                    setOnCheckedChangeListener { _, isPermanent -> viewModel.toggleTemporary(!isPermanent) }
                }
            }
        }
    }

    /**
     * Leaves the screen.
     *
     * The reference finishes its own activity. This app hosts screens as back-stacked fragments in
     * a `singleTask` activity, so finishing would close the app; popping returns to whatever was
     * showing, and finishing is only the fallback when nothing is behind us.
     */
    private fun close() {
        if (!parentFragmentManager.popBackStackImmediate()) requireActivity().finish()
    }

    companion object {
        const val EXTRA_REMOTE_AUTH_FINGERPRINT = "REMOTE_AUTH_FINGERPRINT"

        private const val VIEW_LOADED = 0
        private const val VIEW_NOT_FOUND = 1
        private const val VIEW_LOADING = 2
        private const val VIEW_SUCCEEDED = 3

        /** Opens the confirmation screen for a scanned or linked remote-auth [fingerprint]. */
        fun launch(context: Context, fingerprint: String) {
            AppScreenRouter.launch(
                context,
                WidgetRemoteAuth::class.java,
                Intent().putExtra(EXTRA_REMOTE_AUTH_FINGERPRINT, fingerprint),
            )
        }
    }
}
