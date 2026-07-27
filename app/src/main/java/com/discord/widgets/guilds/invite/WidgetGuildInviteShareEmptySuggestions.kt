package com.discord.widgets.guilds.invite

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.fadeBy
import com.discord.widgets.tabs.WidgetTabsHost
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton

/**
 * The invite screen shown when there is nobody to suggest: just the link, its settings summary,
 * and Share.
 *
 * This is also the timeout destination in [WidgetGuildInviteShare.launch] — it is the only variant
 * that renders correctly with no suggestion data at all.
 *
 * A missing or non-positive guild id finishes the activity rather than rendering an invite to
 * nothing; the reference does the same, because this screen is reachable from a deep link.
 */
class WidgetGuildInviteShareEmptySuggestions :
    AppFragment(R.layout.widget_guild_invite_share_empty_suggestions) {

    private lateinit var inviteLinkView: TextView
    private lateinit var subtext: TextView
    private lateinit var shareButton: MaterialButton
    private lateinit var settingsEdit: ImageView
    private lateinit var bottomSheetTint: View
    private lateinit var settingsSheet: ViewInviteSettingsSheet
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>

    private val guildId: Long get() = mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0L)

    private val viewModel: WidgetGuildInviteShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WidgetGuildInviteShareViewModel(
                    guildId = guildId,
                    eventId = mostRecentIntent
                        .getLongExtra(EXTRA_GUILD_SCHEDULED_EVENT_ID, 0L)
                        .takeIf { it != 0L },
                    inviteStoreKey = null,
                    resources = resources,
                ) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (guildId <= 0L) {
            requireActivity().finish()
            return
        }

        inviteLinkView = view.findViewById(R.id.guild_invite_empty_suggestions_invite_link)
        subtext = view.findViewById(R.id.guild_invite_empty_suggestions_settings_invite_link_subtext)
        shareButton = view.findViewById(R.id.guild_invite_empty_suggestions_invite_share_btn)
        settingsEdit = view.findViewById(R.id.guild_invite_empty_suggestions_settings_edit)
        bottomSheetTint = view.findViewById(R.id.bottom_sheet_tint)
        settingsSheet = view.findViewById(R.id.invite_settings_bottom_sheet)

        val channelId = mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId != 0L) viewModel.selectChannel(channelId)

        val isNuxFlow = mostRecentIntent.getBooleanExtra(WidgetGuildInviteShare.INTENT_IS_NUX_FLOW, false)
        setActionBarDisplayHomeAsUpEnabled(
            showHomeAsUp = true,
            iconRes = if (isNuxFlow) requireContext().getThemedDrawableRes(R.attr.ic_close_24dp) else null,
            iconAccessibilityLabel = if (isNuxFlow) R.string.close else null,
        )

        setOnBackPressed({
            when {
                bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    true
                }
                isNuxFlow -> {
                    StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
                    AppScreenRouter.launch(requireContext(), WidgetTabsHost::class.java)
                    true
                }
                else -> false
            }
        })

        bottomSheetBehavior = BottomSheetBehavior.from(settingsSheet)
        initBottomSheetBehavior(bottomSheetBehavior)
        settingsSheet.viewModel = viewModel
        settingsSheet.onGenerateLinkListener = {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        bottomSheetTint.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (guildId <= 0L) return
        viewModel.observeViewState()
            .filter { it is WidgetGuildInviteShareViewModel.ViewState.Loaded }
            .map { it as WidgetGuildInviteShareViewModel.ViewState.Loaded }
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInviteShareEmptySuggestions::class.java) { updateUi(it) }
    }

    private fun initBottomSheetBehavior(
        bottomSheetBehavior: BottomSheetBehavior<ViewInviteSettingsSheet>,
    ) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) viewModel.refreshUi()
                    bottomSheetTint.fadeBy(newState != BottomSheetBehavior.STATE_HIDDEN)
                }
            },
        )
    }

    private fun updateUi(viewState: WidgetGuildInviteShareViewModel.ViewState.Loaded) {
        val model = viewState.widgetInviteModel
        val invite = model.invite
        settingsSheet.configureUi(model)

        inviteLinkView.text =
            invite?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE
        inviteLinkView.setOnClickListener { view ->
            copyLinkClick(view.context, invite, mostRecentIntent)
        }
        shareButton.setOnClickListener { invite?.let { shareLinkClick(context, it) } }
        settingsEdit.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        model.settings?.let { subtext.text = settingsSummary(it) }
    }

    /**
     * "Expires in 12 hours, 10 uses".
     *
     * The duration is formatted from the *configured* `maxAge` rather than from the invite's
     * remaining lifetime, so it is an exact plural rather than the rounded form
     * [humanizeDurationRounded] produces. "Never" is a plain concatenation in the reference — the
     * `{maxAge}` template is skipped entirely — and that is reproduced here.
     */
    private fun settingsSummary(settings: ModelInvite.Settings): CharSequence {
        val uses = if (settings.maxUses == 0) {
            getString(R.string.max_uses_description_unlimited_uses)
        } else {
            requireContext().i18nPlural(
                R.plurals.max_uses_description_mobile_maxUses,
                settings.maxUses,
                "maxUses" to settings.maxUses,
            )
        }

        if (settings.maxAge == ModelInvite.Settings.NEVER) {
            return "${getString(R.string.max_age_never_description_mobile)}, $uses"
        }

        val expiry = when (settings.maxAge) {
            ModelInvite.Settings.HALF_HOUR -> requireContext().formatDuration(DurationUnit.MINS, 30)
            ModelInvite.Settings.ONE_HOUR -> requireContext().formatDuration(DurationUnit.HOURS, 1)
            ModelInvite.Settings.SIX_HOURS -> requireContext().formatDuration(DurationUnit.HOURS, 6)
            ModelInvite.Settings.TWELVE_HOURS -> requireContext().formatDuration(DurationUnit.HOURS, 12)
            ModelInvite.Settings.ONE_DAY -> requireContext().formatDuration(DurationUnit.DAYS, 1)
            ModelInvite.Settings.SEVEN_DAYS -> requireContext().formatDuration(DurationUnit.DAYS, 7)
            // The reference leaves the subtext untouched for an unknown value rather than
            // throwing; keeping the previous text is the closest safe equivalent.
            else -> return subtext.text
        }
        return requireContext().i18nFormat(
            R.string.invite_settings_expired_description,
            "maxAge" to expiry,
            "maxUses" to uses,
        )
    }

    private companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_SCHEDULED_EVENT_ID =
            "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"
    }
}
