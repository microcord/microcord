package com.discord.widgets.settings

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.discord.BuildConfig
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.databinding.WidgetSettingsBinding
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils.displayName
import com.discord.widgets.debugging.WidgetDebugging
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.discord.widgets.settings.developer.WidgetSettingsDeveloper
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.user.Badge
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
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
    private var _binding: WidgetSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsBinding.bind(view)
        configureToolbar()
        hideUnavailableRows()

        binding.setStatusContainer.apply {
            contentDescription = getString(R.string.set_status)
            isFocusable = true
            setOnClickListener {
                onSetStatus?.invoke() ?: WidgetUserStatusSheet.show(parentFragmentManager)
            }
        }

        binding.userSettingsProfileHeaderView.apply {
            setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
            setOnBannerPress { viewModel.toggleAllowAnimationInReducedMotion() }
        }

        binding.appInfoHeader.text = buildString {
            append(getString(R.string.app_information))
            append(" - ")
            append("Dev ")
            append(BuildConfig.BUILD_ID)
            append(" ")
            append(BuildConfig.COMMIT_HASH.take(8))
        }

        bindRoute(binding.account, ::WidgetSettingsAccount)
        bindRoute(binding.profile, ::WidgetEditUserOrGuildMemberProfile)
        bindRoute(binding.authorizedApps, ::WidgetSettingsAuthorizedApps)
        bindRoute(binding.sessions, ::WidgetSettingsSessions)
        bindRoute(binding.textImagesSettings, ::WidgetSettingsMedia)
        bindRoute(binding.appearance, ::WidgetSettingsAppearance)
        bindRoute(binding.language, ::WidgetSettingsLanguage)
        bindRoute(binding.accessibility, ::WidgetSettingsAccessibility)
        bindRoute(binding.behavior, ::WidgetSettingsBehavior)
        bindRoute(binding.activityStatus, ::WidgetSettingsActivityStatus)
        bindRoute(binding.notifications, ::WidgetSettingsNotifications)
        bindRoute(binding.privacy, ::WidgetSettingsPrivacy)
        bindRoute(binding.developerOptions, ::WidgetSettingsDeveloper)
        // Voice persists every setting `StoreMediaSettings` backs; the OpenSL ES section and the
        // microphone test are hidden inside it because `StoreStream` publishes no media engine yet.
        bindRoute(binding.voice, ::WidgetSettingsVoice)

        binding.support.setOnClickListener {
            UriHandler.handle(it.context, SUPPORT_URL)
        }
        binding.acknowledgements.setOnClickListener {
            UriHandler.handle(it.context, ACKNOWLEDGEMENTS_URL)
        }
        binding.notificationUpsellEnable.setOnClickListener {
            openSystemNotificationSettings(it.context)
        }
        binding.notificationUpsellDismiss.setOnClickListener {
            viewModel.dismissPushNotificationUpsell()
        }

        listOf(
            binding.userSettingsHeader,
            binding.nitroHeader,
            binding.appSettingsHeader,
            binding.developerOptionsHeader,
            binding.appInfoHeader,
        ).forEach { ViewCompat.setAccessibilityHeading(it, true) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
                _binding?.userSettingsProfileHeaderView?.updateViewState(state)
            }
        }
    }

    private fun configureToolbar() {
        setActionBarTitle(R.string.user_settings)
        setActionBarTitleLayoutMinimumTappableArea()
        setActionBarOptionsMenu(
            R.menu.menu_settings,
            onSelected = { item ->
                when (item.itemId) {
                    R.id.menu_settings_debugging -> WidgetDebugging.launch(requireContext())
                    R.id.menu_settings_log_out -> showLogoutDialog(requireContext())
                }
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
        if (_binding == null) return
        binding.settingsStatusView.setPresence(model.presence)
        binding.settingsPresenceText.setText(PresenceUtils.getStatusStringResForPresence(model.presence))
        binding.userSettingsProfileHeaderView.contentDescription = displayName(model.meUser)

        binding.developerOptionsDivider.visibility = View.VISIBLE
        binding.developerOptionsHeader.visibility = View.VISIBLE
        binding.developerOptions.visibility = View.VISIBLE

        binding.roleSubscriptions.visibility =
            if (model.showRoleSubscriptionsButton) View.VISIBLE else View.GONE

        // The recovered layout names this view `nitroGiftingBadge`, not `nitro_gifting_badge`.
        val badge = binding.nitroGiftingBadge
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
        binding.notificationUpsell.visibility =
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

    private fun hideUnavailableRows() {
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
        val rows = binding.userSettingsHeader.parent as ViewGroup
        for (index in 0 until rows.childCount) {
            val child = rows.getChildAt(index)
            child.visibility = if (child.id in keep) View.VISIBLE else View.GONE
        }
    }

    private fun bindRoute(row: View, destination: () -> Fragment) {
        row.setOnClickListener {
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
            R.id.profile,
            R.id.authorized_apps,
            R.id.sessions,
            R.id.text_images_settings,
            R.id.appearance,
            R.id.language,
            R.id.accessibility,
            R.id.behavior,
            R.id.activity_status,
            R.id.notifications,
            R.id.privacy,
            R.id.voice,
            R.id.developer_options,
        )

        /**
         * Host overrides, kept because `WidgetTabsHost` assigns them so the settings tab can push
         * destinations into its own child fragment manager instead of the parent one. Each has a
         * working default; the reference has no equivalent indirection.
         */
        @JvmField var onSetStatus: (() -> Unit)? = null
        @JvmField var onLogout: (() -> Unit)? = null

        internal fun displayName(user: MeUser) = user.displayName
    }
}
