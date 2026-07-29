package com.discord.widgets.stickers

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.app.AppBottomSheet
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.models.guild.Guild
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setCompoundDrawableWithIntrinsicBounds
import com.discord.views.sticker.StickerView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import rx.subscriptions.CompositeSubscription

/**
 * The popout for a *guild* sticker tapped in a message: the sticker, one line explaining why the
 * viewer can or cannot use it, and — when the sticker belongs to some other server — a card for
 * that server with a Join button.
 */
class WidgetGuildStickerSheet : AppBottomSheet() {

    private val sticker: Sticker
        get() = requireNotNull(
            argumentsOrDefault.getSerializable(EXTRA_STICKER) as? Sticker,
        ) { "WidgetGuildStickerSheet requires a Sticker in $EXTRA_STICKER" }

    private val viewModel: GuildStickerSheetViewModel by appViewModels {
        GuildStickerSheetViewModel(sticker)
    }

    private lateinit var flipper: AppViewFlipper
    private lateinit var stickerView: StickerView
    private lateinit var stickerName: TextView
    private lateinit var description: TextView
    private lateinit var buttonContainer: FrameLayout
    private lateinit var joinButton: MaterialButton
    private lateinit var premiumButton: MaterialButton
    private lateinit var guildContainer: LinearLayout
    private lateinit var guildIconWrapper: FrameLayout
    private lateinit var guildIcon: SimpleDraweeView
    private lateinit var guildIconText: TextView
    private lateinit var guildName: TextView
    private lateinit var guildInfo: TextView

    override fun getContentViewResId(): Int = R.layout.widget_guild_sticker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flipper = view.findViewById(R.id.guild_sticker_sheet_flipper)
        stickerView = view.findViewById(R.id.guild_sticker_sheet_sticker)
        stickerName = view.findViewById(R.id.guild_sticker_sheet_sticker_name)
        description = view.findViewById(R.id.guild_sticker_sheet_description)
        buttonContainer = view.findViewById(R.id.guild_sticker_sheet_button_container)
        joinButton = view.findViewById(R.id.guild_sticker_sheet_join_btn)
        premiumButton = view.findViewById(R.id.guild_sticker_sheet_premium_btn)
        guildContainer = view.findViewById(R.id.guild_sticker_sheet_guild_container)
        guildIconWrapper = view.findViewById(R.id.guild_sticker_sheet_guild_icon_wrapper)
        guildIcon = view.findViewById(R.id.guild_sticker_sheet_guild_icon)
        guildIconText = view.findViewById(R.id.guild_sticker_sheet_guild_icon_text)
        guildName = view.findViewById(R.id.guild_sticker_sheet_guild_name)
        guildInfo = view.findViewById(R.id.guild_sticker_sheet_guild_info)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { viewState ->
                when (viewState) {
                    is GuildStickerSheetViewModel.ViewState.Loading ->
                        flipper.displayedChild = VIEW_LOADING
                    is GuildStickerSheetViewModel.ViewState.Loaded -> configureUI(viewState)
                    is GuildStickerSheetViewModel.ViewState.Dismiss -> dismiss()
                }
            }
    }

    private fun configureUI(viewState: GuildStickerSheetViewModel.ViewState.Loaded) {
        val info = viewState.guildStickerGuildInfo
        val known = info as? GuildStickerSheetViewModel.Companion.GuildStickerGuildInfo.Known

        stickerView.setSticker(viewState.sticker, ALWAYS_ANIMATE)
        stickerName.text = viewState.sticker.name
        description.setText(
            customStickerInfoText(
                isCurrentGuild = viewState.isCurrentGuild,
                isUserInGuild = info.isUserInGuild,
                isPublic = info.isPublic,
                isUserPremium = viewState.isUserPremium,
            ),
        )
        configureButtons(viewState.isUserPremium, info.isUserInGuild, known?.guild)
        configureGuildSection(
            guild = known?.guild,
            isCurrentGuild = viewState.isCurrentGuild,
            isPublic = info.isPublic,
            approximateOnline = known?.approximateOnline,
        )
        flipper.displayedChild = VIEW_CONTENT
    }

    private fun configureButtons(isUserPremium: Boolean, isUserInGuild: Boolean, guild: Guild?) {
        when {
            // No guild to act on, or the user already has Nitro and is a member: nothing to offer.
            guild == null -> buttonContainer.visibility = View.GONE

            !isUserPremium -> {
                buttonContainer.visibility = View.VISIBLE
                premiumButton.visibility = View.VISIBLE
                joinButton.visibility = View.GONE
                premiumButton.setOnClickListener {
                    dismiss()
                    StickerPremiumUpsellDialog.show(parentFragmentManager)
                }
            }

            !isUserInGuild -> {
                buttonContainer.visibility = View.VISIBLE
                premiumButton.visibility = View.GONE
                joinButton.visibility = View.VISIBLE
                joinButton.setOnClickListener { viewModel.joinGuild(requireContext(), guild) }
            }

            else -> buttonContainer.visibility = View.GONE
        }
    }

    private fun configureGuildSection(
        guild: Guild?,
        isCurrentGuild: Boolean,
        isPublic: Boolean,
        approximateOnline: Int?,
    ) {
        // The card names the server the sticker came from, which is redundant when the reader is
        // already looking at that server.
        if (guild == null || isCurrentGuild) {
            guildContainer.visibility = View.GONE
            return
        }
        guildContainer.visibility = View.VISIBLE

        if (guild.hasIcon()) {
            IconUtils.setIcon(guildIcon, IconUtils.getForGuild(guild, allowAnimation = true))
        } else {
            guildIconWrapper.setBackgroundResource(R.drawable.drawable_circle_black)
            guildIconWrapper.backgroundTintList =
                ColorStateList.valueOf(ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary))
            guildIconText.text = guild.shortName
        }

        val badge = when {
            guild.hasFeature(GuildFeature.PARTNERED) -> R.drawable.ic_partnered_badge_banner
            guild.hasFeature(GuildFeature.VERIFIED) -> R.drawable.ic_verified_badge_banner
            else -> 0
        }
        guildName.setCompoundDrawableWithIntrinsicBounds(start = badge)
        guildName.text = guild.name

        val visibility = getString(
            if (isPublic) R.string.sticker_popout_public_server
            else R.string.sticker_popout_private_server,
        )
        val onlinePrefix = approximateOnline?.let { online ->
            val formatted = NumberFormat
                .getNumberInstance(LocaleManager().getPrimaryLocale(requireContext()))
                .format(online)
            requireContext().i18nFormat(
                R.string.instant_invite_guild_members_online,
                "membersOnline" to formatted,
            ) + " • "
        }.orEmpty()
        guildInfo.text = onlinePrefix + visibility
    }

    /**
     * Seven of the eight `sticker_popout_*_description` strings, chosen by how close the reader is
     * to being able to use the sticker. `sticker_popout_unjoined_private_guild_description` is
     * never reached: the reference falls through to the *premium* private-guild copy for both
     * premium states, which is reproduced here rather than corrected.
     */
    private fun customStickerInfoText(
        isCurrentGuild: Boolean,
        isUserInGuild: Boolean,
        isPublic: Boolean,
        isUserPremium: Boolean,
    ): Int = when {
        isCurrentGuild && !isUserPremium -> R.string.sticker_popout_current_guild_description
        isCurrentGuild -> R.string.sticker_popout_premium_current_guild_description
        isUserInGuild && !isUserPremium -> R.string.sticker_popout_joined_guild_description
        isUserInGuild -> R.string.sticker_popout_premium_joined_guild_description
        isPublic && !isUserPremium -> R.string.sticker_popout_unjoined_discoverable_guild_description
        isPublic -> R.string.sticker_popout_premium_unjoined_discoverable_guild_description
        else -> R.string.sticker_popout_premium_unjoined_private_guild_description
    }

    companion object {
        private const val EXTRA_STICKER = "com.discord.intent.extra.EXTRA_STICKER"
        private const val VIEW_CONTENT = 0
        private const val VIEW_LOADING = 1

        /** [com.discord.utilities.stickers.StickerUtils.ALWAYS_ANIMATE]. */
        private const val ALWAYS_ANIMATE = 0

        fun show(fragmentManager: FragmentManager, sticker: Sticker) {
            if (sticker.type != StickerType.GUILD) return
            WidgetGuildStickerSheet().apply {
                arguments = Bundle().apply { putSerializable(EXTRA_STICKER, sticker) }
            }.show(fragmentManager, WidgetGuildStickerSheet::class.java.name)
        }
    }
}
