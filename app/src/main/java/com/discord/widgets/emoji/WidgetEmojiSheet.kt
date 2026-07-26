package com.discord.widgets.emoji

import android.content.res.ColorStateList
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.guild.Guild
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.view.extensions.fadeBy
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import rx.subscriptions.CompositeSubscription

/**
 * The emoji popout: tap a custom or unicode emoji in a message and this explains where it came
 * from, whether you can use it elsewhere, and offers to join the source server or favourite it.
 *
 * The content pane resizes as the guild card and buttons appear, so each rebuild is wrapped in a
 * short [ChangeBounds] transition rather than snapping. The framework transition classes are used
 * rather than `androidx.transition` so the sheet adds no dependency of its own; minSdk is 21 and
 * both have been in the platform since 19.
 */
class WidgetEmojiSheet : AppBottomSheet() {

    private val emojiIdAndType: EmojiNode.EmojiIdAndType
        get() = requireNotNull(
            argumentsOrDefault.getSerializable(EXTRA_EMOJI_ID_AND_TYPE) as? EmojiNode.EmojiIdAndType,
        ) { "WidgetEmojiSheet requires an EmojiIdAndType in $EXTRA_EMOJI_ID_AND_TYPE" }

    private val viewModel: EmojiSheetViewModel by appViewModels {
        EmojiSheetViewModel(emojiIdAndType)
    }

    private val emojiSizePx: Int = IconUtils.getMediaProxySize(dpToPixels(48))
    private val imageChangeDetector = MGImages.DistinctChangeDetector()

    private lateinit var flipper: AppViewFlipper
    private lateinit var emojiIv: SimpleDraweeView
    private lateinit var nameTv: TextView
    private lateinit var emojiInfoTv: TextView
    private lateinit var buttonContainer: FrameLayout
    private lateinit var joinBtn: MaterialButton
    private lateinit var premiumBtn: MaterialButton
    private lateinit var favoriteContainer: FrameLayout
    private lateinit var favoriteAddButton: MaterialButton
    private lateinit var favoriteRemoveButton: MaterialButton
    private lateinit var guildContainer: LinearLayout
    private lateinit var guildAvatarWrap: FrameLayout
    private lateinit var guildAvatarText: TextView
    private lateinit var guildIv: SimpleDraweeView
    private lateinit var guildNameTv: TextView
    private lateinit var guildInfoTv: TextView

    override fun getContentViewResId(): Int = R.layout.widget_emoji_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flipper = view.findViewById(R.id.emoji_sheet_flipper)
        emojiIv = view.findViewById(R.id.emoji_iv)
        nameTv = view.findViewById(R.id.name_tv)
        emojiInfoTv = view.findViewById(R.id.emoji_info_tv)
        buttonContainer = view.findViewById(R.id.button_container)
        joinBtn = view.findViewById(R.id.join_btn)
        premiumBtn = view.findViewById(R.id.premium_btn)
        favoriteContainer = view.findViewById(R.id.favorite_container)
        favoriteAddButton = view.findViewById(R.id.favorite_add_button)
        favoriteRemoveButton = view.findViewById(R.id.favorite_remove_button)
        guildContainer = view.findViewById(R.id.guild_container)
        guildAvatarWrap = view.findViewById(R.id.guildAvatarWrap)
        guildAvatarText = view.findViewById(R.id.guildAvatarText)
        guildIv = view.findViewById(R.id.guild_iv)
        guildNameTv = view.findViewById(R.id.guild_name_tv)
        guildInfoTv = view.findViewById(R.id.guild_info_tv)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .ui(this)
            .subscribe { viewState ->
                when (viewState) {
                    is EmojiSheetViewModel.ViewState.Loading ->
                        flipper.displayedChild = FLIPPER_INDEX_LOADING

                    is EmojiSheetViewModel.ViewState.EmojiCustom -> configureCustomEmoji(viewState)
                    is EmojiSheetViewModel.ViewState.EmojiUnicode -> configureUnicodeEmoji(viewState)
                    is EmojiSheetViewModel.ViewState.Invalid,
                    is EmojiSheetViewModel.ViewState.Dismiss,
                    -> dismiss()
                }
            }
    }

    private fun configureCustomEmoji(viewState: EmojiSheetViewModel.ViewState.EmojiCustom) {
        (view?.parent as? ViewGroup)?.let { parent ->
            TransitionManager.beginDelayedTransition(
                parent,
                ChangeBounds().setDuration(RESIZE_TRANSITION_MS),
            )
        }

        val known = viewState.emojiGuildInfo as? EmojiSheetViewModel.Companion.CustomEmojiGuildInfo.Known
        val emoji = viewState.emojiCustom

        MGImages.setImage(
            view = emojiIv,
            url = ModelEmojiCustom.getImageUri(emoji.id, emoji.isAnimated, emojiSizePx),
            changeDetector = imageChangeDetector,
        )
        nameTv.text = ":${emoji.name}:"
        emojiInfoTv.setText(
            getCustomEmojiInfoText(
                isCurrentGuild = viewState.isCurrentGuild,
                isUserInGuild = viewState.emojiGuildInfo.isUserInGuild,
                isGuildPublic = viewState.emojiGuildInfo.isPublic,
                isUserPremium = viewState.isUserPremium,
            ),
        )

        configureButtons(
            isUserPremium = viewState.isUserPremium,
            isUserInGuild = viewState.emojiGuildInfo.isUserInGuild,
            guild = known?.guild,
        )
        configureGuildSection(
            guild = known?.guild,
            isCurrent = viewState.isCurrentGuild,
            isPublic = viewState.emojiGuildInfo.isPublic,
            approximateOnline = known?.approximateOnline,
        )
        configureFavorite(viewState.canFavorite, viewState.isFavorite)
        flipper.displayedChild = FLIPPER_INDEX_CONTENT
    }

    private fun configureUnicodeEmoji(viewState: EmojiSheetViewModel.ViewState.EmojiUnicode) {
        MGImages.setImage(
            view = emojiIv,
            url = viewState.emojiUnicode.getImageUri(true, emojiSizePx, requireContext()),
            changeDetector = imageChangeDetector,
        )
        nameTv.text = ":${viewState.emojiUnicode.firstName}:"
        emojiInfoTv.setText(R.string.emoji_popout_standard_emoji_description)
        buttonContainer.visibility = View.GONE
        guildContainer.visibility = View.GONE
        configureFavorite(viewState.canFavorite, viewState.isFavorite)
        flipper.displayedChild = FLIPPER_INDEX_CONTENT
    }

    /**
     * At most one call to action: upsell Nitro to a non-subscriber, otherwise offer to join the
     * source server. A subscriber who is already a member gets neither.
     */
    private fun configureButtons(isUserPremium: Boolean, isUserInGuild: Boolean, guild: Guild?) {
        when {
            guild == null -> buttonContainer.visibility = View.GONE

            !isUserPremium -> {
                buttonContainer.visibility = View.VISIBLE
                premiumBtn.visibility = View.VISIBLE
                joinBtn.visibility = View.GONE
                premiumBtn.setOnClickListener {
                    dismiss()
                    // Reference: WidgetSettingsPremium.launch(context, "Emoji Upsell Popout").
                    // That screen is not reconstructed; the sheet closes rather than leading
                    // nowhere. Reported in the handoff.
                    onPremiumUpsell(PREMIUM_UPSELL_LOCATION)
                }
            }

            !isUserInGuild -> {
                buttonContainer.visibility = View.VISIBLE
                premiumBtn.visibility = View.GONE
                joinBtn.visibility = View.VISIBLE
                joinBtn.setOnClickListener { viewModel.joinGuild(guild) }
            }

            else -> buttonContainer.visibility = View.GONE
        }
    }

    private fun configureFavorite(canFavorite: Boolean, isFavorite: Boolean) {
        favoriteContainer.visibility = if (canFavorite) View.VISIBLE else View.GONE
        // The two buttons are stacked; cross-fading keeps the row from resizing on toggle.
        favoriteAddButton.fadeBy(!isFavorite, FAVORITE_FADE_MS)
        favoriteAddButton.setOnClickListener { viewModel.setFavorite(true) }
        favoriteRemoveButton.fadeBy(isFavorite, FAVORITE_FADE_MS)
        favoriteRemoveButton.setOnClickListener { viewModel.setFavorite(false) }
    }

    /** The source-server card, hidden when the emoji is from the server we are already looking at. */
    private fun configureGuildSection(
        guild: Guild?,
        isCurrent: Boolean,
        isPublic: Boolean,
        approximateOnline: Int?,
    ) {
        if (guild == null || isCurrent) {
            guildContainer.visibility = View.GONE
            return
        }
        guildContainer.visibility = View.VISIBLE

        if (guild.hasIcon()) {
            IconUtils.setIcon(guildIv, guild, changeDetector = imageChangeDetector, allowAnimation = true)
        } else {
            guildAvatarWrap.setBackgroundResource(R.drawable.drawable_circle_black)
            guildAvatarWrap.backgroundTintList = ColorStateList.valueOf(
                ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary),
            )
            guildAvatarText.text = guild.shortName
        }

        guildNameTv.setCompoundDrawablesCompat(
            start = when {
                guild.hasFeature(GuildFeature.PARTNERED) -> R.drawable.ic_partnered_badge_banner
                guild.hasFeature(GuildFeature.VERIFIED) -> R.drawable.ic_verified_badge_banner
                else -> 0
            },
        )
        guildNameTv.text = guild.name

        val visibility = getString(
            if (isPublic) R.string.emoji_popout_public_server else R.string.emoji_popout_private_server,
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
        guildInfoTv.text = onlinePrefix + visibility
    }

    /**
     * The one-line explanation under the emoji name. Seven distinct outcomes, keyed by where the
     * emoji is from and whether the viewer has Nitro.
     */
    @StringRes
    private fun getCustomEmojiInfoText(
        isCurrentGuild: Boolean,
        isUserInGuild: Boolean,
        isGuildPublic: Boolean,
        isUserPremium: Boolean,
    ): Int = when {
        isCurrentGuild && !isUserPremium -> R.string.emoji_popout_current_guild_description
        isCurrentGuild -> R.string.emoji_popout_premium_current_guild_description
        isUserInGuild && !isUserPremium -> R.string.emoji_popout_joined_guild_description
        isUserInGuild -> R.string.emoji_popout_premium_joined_guild_description
        isGuildPublic && !isUserPremium -> R.string.emoji_popout_unjoined_discoverable_guild_description
        isGuildPublic -> R.string.emoji_popout_premium_unjoined_discoverable_guild_description
        else -> R.string.emoji_popout_premium_unjoined_private_guild_description
    }

    companion object {
        private const val EXTRA_EMOJI_ID_AND_TYPE = "EXTRA_EMOJI_ID_AND_TYPE"
        private const val FLIPPER_INDEX_CONTENT = 0
        private const val FLIPPER_INDEX_LOADING = 1
        private const val RESIZE_TRANSITION_MS = 150L
        private const val FAVORITE_FADE_MS = 200L
        private const val PREMIUM_UPSELL_LOCATION = "Emoji Upsell Popout"

        /**
         * Reference: `WidgetSettingsPremium.launch(context, location)`. That screen does not exist
         * in this project, so the Nitro button closes the sheet and reports here.
         */
        @JvmStatic
        var onPremiumUpsell: (location: String) -> Unit = { }

        /**
         * Queues the popout instead of showing it now, for callers that are mid-transition.
         * Marked seen as soon as it is shown: the same emoji must not re-prompt later.
         */
        fun enqueueNotice(emojiIdAndType: EmojiNode.EmojiIdAndType) {
            val storeNotices = StoreStream.getNotices()
            val noticeName = "Emoji Sheet: $emojiIdAndType"
            storeNotices.requestToShow(
                StoreNotices.Notice(
                    name = noticeName,
                    priority = StoreNotices.PRIORITY_HIGH,
                    delayPeriodMs = 0L,
                    sinceLastPeriodMs = 0L,
                ) { activity ->
                    show(activity.supportFragmentManager, emojiIdAndType)
                    storeNotices.markSeen(noticeName)
                    true
                },
            )
        }

        fun show(fragmentManager: FragmentManager, emojiIdAndType: EmojiNode.EmojiIdAndType) {
            WidgetEmojiSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(EXTRA_EMOJI_ID_AND_TYPE, emojiIdAndType)
                }
            }.show(fragmentManager, WidgetEmojiSheet::class.java.name)
        }
    }
}
