package com.discord.widgets.settings

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.discord.BuildConfig
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.views.StatusView
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.user.Badge
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * The user-settings root screen, hosted by the settings tab.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettings` + `SettingsViewModel`.
 *
 * One deliberate divergence from the reference is recorded here:
 *
 * **[hideUnavailableRows]** has no reference counterpart. The reference shows every row and gates
 *    only `developer_options*`, `nitro_settings_container`, `role_subscriptions`,
 *    `nitro_gifting_badge` and `notification_upsell`. Rows here are hidden when their destination
 *    screen does not exist yet, so the screen has no dead taps; every id in [implementedRouteIds]
 *    navigates to a reconstructed screen. Delete the mechanism once the missing screens exist.
 *
 *    One caveat, so the set is not read as stronger than it is: `notifications` reaches a complete
 *    [WidgetSettingsNotifications], but that screen's toggles stay disabled until `StoreStream`
 *    publishes `StoreNotifications` (see `NotificationStore`). The row is exposed anyway because the
 *    destination exists and renders real state — hiding a finished screen is the mistake the parity
 *    audit already caught on `blocked_users`.
 */
class WidgetSettings : AppFragment(R.layout.widget_settings) {

    private val viewModel: SettingsViewModel by appViewModels { SettingsViewModel() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        configureToolbar()
        hideUnavailableRows(view)

        view.findViewById<View>(R.id.set_status_container).apply {
            contentDescription = getString(R.string.set_status)
            isFocusable = true
            setOnClickListener {
                onSetStatus?.invoke() ?: WidgetUserStatusSheet.show(parentFragmentManager)
            }
        }

        view.findViewById<UserProfileHeaderView>(R.id.user_settings_profile_header_view).apply {
            setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
            setOnBannerPress { viewModel.toggleAllowAnimationInReducedMotion() }
        }

        view.findViewById<TextView>(R.id.app_info_header).text = buildString {
            append(getString(R.string.app_information))
            append(" - ")
            append(BuildConfig.VERSION_NAME)
            append(" - Stable (")
            append(BuildConfig.VERSION_CODE)
            append(")")
        }

        bindRoute(view, R.id.account, ::WidgetSettingsAccount)
        bindRoute(view, R.id.authorized_apps, ::WidgetSettingsAuthorizedApps)
        bindRoute(view, R.id.text_images_settings, ::WidgetSettingsMedia)
        bindRoute(view, R.id.appearance, ::WidgetSettingsAppearance)
        bindRoute(view, R.id.language, ::WidgetSettingsLanguage)
        bindRoute(view, R.id.accessibility, ::WidgetSettingsAccessibility)
        bindRoute(view, R.id.behavior, ::WidgetSettingsBehavior)
        bindRoute(view, R.id.activity_status, ::WidgetSettingsActivityStatus)
        bindRoute(view, R.id.notifications, ::WidgetSettingsNotifications)
        bindRoute(view, R.id.privacy, ::WidgetSettingsPrivacy)
        // Voice persists every setting `StoreMediaSettings` backs; the OpenSL ES section and the
        // microphone test are hidden inside it because `StoreStream` publishes no media engine yet.
        bindRoute(view, R.id.voice, ::WidgetSettingsVoice)

        view.findViewById<View>(R.id.support).setOnClickListener {
            UriHandler.handle(it.context, SUPPORT_URL)
        }
        view.findViewById<View>(R.id.acknowledgements).setOnClickListener {
            UriHandler.handle(it.context, ACKNOWLEDGEMENTS_URL)
        }
        view.findViewById<View>(R.id.notification_upsell_enable).setOnClickListener {
            openSystemNotificationSettings(it.context)
        }
        view.findViewById<View>(R.id.notification_upsell_dismiss).setOnClickListener {
            viewModel.dismissPushNotificationUpsell()
        }

        listOf(
            R.id.user_settings_header,
            R.id.nitro_header,
            R.id.app_settings_header,
            R.id.developer_options_header,
            R.id.app_info_header,
        ).forEach { id -> ViewCompat.setAccessibilityHeading(view.findViewById(id), true) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Reference registers itself as the settings tab's `OnTabSelectedListener` and refreshes
        // here. `widgets/tabs` has no listener registry yet, so the refresh runs on every resume.
        viewModel.refreshSubscriptions()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        viewModel.observeProfileHeaderViewState().ui(this).subscribe { state ->
            // `UserProfileHeaderView` only renders the loaded state; there is nothing to show for
            // any other one.
            if (state is UserProfileHeaderViewModel.ViewState.Loaded) {
                view?.findViewById<UserProfileHeaderView>(R.id.user_settings_profile_header_view)
                    ?.updateViewState(state)
            }
        }
    }

    private fun configureToolbar() {
        setActionBarTitle(R.string.user_settings)
        setActionBarTitleLayoutMinimumTappableArea()
        setActionBarOptionsMenu(
            R.menu.menu_settings,
            onSelected = { item ->
                if (item.itemId == R.id.menu_settings_log_out) showLogoutDialog(requireContext())
            },
            onConfigured = { menu ->
                // `WidgetDebugging` has not been reconstructed; hide its entry rather than
                // presenting a menu item that does nothing.
                menu.findItem(R.id.menu_settings_debugging)?.isVisible = false
            },
        )
    }

    private fun configureUI(viewState: SettingsViewModel.ViewState) {
        when (viewState) {
            is SettingsViewModel.ViewState.Uninitialized -> Unit
            is SettingsViewModel.ViewState.Loaded -> configureLoaded(viewState)
        }
    }

    private fun configureLoaded(model: SettingsViewModel.ViewState.Loaded) {
        val root = view ?: return
        root.findViewById<StatusView>(R.id.settings_status_view).setPresence(model.presence)
        root.findViewById<TextView>(R.id.settings_presence_text)
            .setText(PresenceUtils.getStatusStringResForPresence(model.presence))
        root.findViewById<UserProfileHeaderView>(R.id.user_settings_profile_header_view)
            .contentDescription = displayName(model.meUser)

        val staffVisibility = if (model.isStaffOrAlpha) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.developer_options_divider).visibility = staffVisibility
        root.findViewById<View>(R.id.developer_options_header).visibility = staffVisibility
        // `WidgetSettingsDeveloper` is missing, so the row stays hidden even for staff accounts.
        root.findViewById<View>(R.id.developer_options).visibility = View.GONE

        root.findViewById<TextView>(R.id.role_subscriptions).visibility =
            if (model.showRoleSubscriptionsButton) View.VISIBLE else View.GONE

        // The recovered layout names this view `nitroGiftingBadge`, not `nitro_gifting_badge`.
        val badge = root.findViewById<TextView>(R.id.nitroGiftingBadge)
        if (model.promoCount > 0) {
            badge.text = model.promoCount.toString()
            badge.background = MaterialShapeDrawable(
                ShapeAppearanceModel.Builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build(),
            ).apply {
                fillColor = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.status_red_500),
                )
            }
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }

        val notificationsBlocked = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        root.findViewById<LinearLayout>(R.id.notification_upsell).visibility =
            if (notificationsBlocked && !model.pushNotificationUpsellDismissed) View.VISIBLE else View.GONE
    }

    private fun showLogoutDialog(context: Context) {
        WidgetNoticeDialog.Builder(context)
            .setTitle(R.string.logout)
            .setMessage(R.string.user_settings_confirm_logout)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.logout) {
                onLogout?.invoke() ?: StoreStream.getAuthentication().logout()
            }
            .setDialogAttrTheme(R.attr.notice_theme_positive_red)
            .show(parentFragmentManager)
    }

    private fun openSystemNotificationSettings(context: Context) {
        // Reference: `SystemSettingNavigators.openSystemNotification(context)`, in a utilities
        // package this slice does not own.
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }

    private fun hideUnavailableRows(view: View) {
        val keep = setOf(
            R.id.user_settings_header,
            R.id.set_status_container,
            R.id.app_settings_header,
            R.id.developer_options_divider,
            R.id.developer_options_header,
            R.id.app_info_header,
            R.id.support,
            R.id.acknowledgements,
            *implementedRouteIds.toTypedArray(),
        )
        val rows = view.findViewById<View>(R.id.user_settings_header).parent as ViewGroup
        for (index in 0 until rows.childCount) {
            val child = rows.getChildAt(index)
            child.visibility = if (child.id in keep) View.VISIBLE else View.GONE
        }
    }

    private fun bindRoute(view: View, rowId: Int, destination: () -> Fragment) {
        view.findViewById<View>(rowId).setOnClickListener {
            val fragment = destination()
            parentFragmentManager.commit {
                replace(this@WidgetSettings.id, fragment)
                addToBackStack(fragment::class.java.name)
            }
        }
    }

    companion object {
        private const val SUPPORT_URL = "https://support.discord.com"
        private const val ACKNOWLEDGEMENTS_URL = "https://discord.com/acknowledgements"

        /** Rows whose destination screen exists; everything else is hidden by [hideUnavailableRows]. */
        internal val implementedRouteIds = setOf(
            R.id.account,
            R.id.authorized_apps,
            R.id.text_images_settings,
            R.id.appearance,
            R.id.language,
            R.id.accessibility,
            R.id.behavior,
            R.id.activity_status,
            R.id.notifications,
            R.id.privacy,
            R.id.voice,
        )

        /**
         * Host overrides, kept because `WidgetTabsHost` assigns them so the settings tab can push
         * destinations into its own child fragment manager instead of the parent one. Each has a
         * working default; the reference has no equivalent indirection.
         */
        @JvmField var onSetStatus: (() -> Unit)? = null
        @JvmField var onLogout: (() -> Unit)? = null

        internal fun displayName(user: MeUser) = if (user.discriminator == 0) {
            user.username
        } else {
            "${user.username}#${user.discriminator.toString().padStart(4, '0')}"
        }
    }
}
