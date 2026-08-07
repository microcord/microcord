package com.discord.widgets.emoji

import android.content.res.ColorStateList
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetEmojiSheetBinding
import com.discord.widgets.settings.premium.WidgetSettingsPremium
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
    private var _binding: WidgetEmojiSheetBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId(): Int = R.layout.widget_emoji_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetEmojiSheetBinding.bind(view)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .ui(this)
            .subscribe { viewState ->
                when (viewState) {
                    is EmojiSheetViewModel.ViewState.Loading ->
                        binding.emojiSheetFlipper.displayedChild = FLIPPER_INDEX_LOADING

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
            view = binding.emojiIv,
            url = ModelEmojiCustom.getImageUri(emoji.id, emoji.isAnimated, emojiSizePx),
            changeDetector = imageChangeDetector,
        )
        binding.nameTv.text = ":${emoji.name}:"
        binding.emojiInfoTv.setText(
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
            isPublic = viewState.emojiGuildInfo.isPublic,
            approximateOnline = known?.approximateOnline,
        )
        configureFavorite(viewState.canFavorite, viewState.isFavorite)
        binding.emojiSheetFlipper.displayedChild = FLIPPER_INDEX_CONTENT
    }

    private fun configureUnicodeEmoji(viewState: EmojiSheetViewModel.ViewState.EmojiUnicode) {
        MGImages.setImage(
            view = binding.emojiIv,
            url = viewState.emojiUnicode.getImageUri(true, emojiSizePx, requireContext()),
            changeDetector = imageChangeDetector,
        )
        binding.nameTv.text = ":${viewState.emojiUnicode.firstName}:"
        binding.emojiInfoTv.setText(R.string.emoji_popout_standard_emoji_description)
        binding.buttonContainer.visibility = View.GONE
        binding.guildContainer.visibility = View.GONE
        configureFavorite(viewState.canFavorite, viewState.isFavorite)
        binding.emojiSheetFlipper.displayedChild = FLIPPER_INDEX_CONTENT
    }

    /**
     * At most one call to action: upsell Nitro to a non-subscriber, otherwise offer to join the
     * source server. A subscriber who is already a member gets neither.
     */
    private fun configureButtons(isUserPremium: Boolean, isUserInGuild: Boolean, guild: Guild?) {
        when {
            guild == null -> binding.buttonContainer.visibility = View.GONE

            !isUserPremium -> {
                binding.buttonContainer.visibility = View.VISIBLE
                binding.premiumBtn.visibility = View.VISIBLE
                binding.joinBtn.visibility = View.GONE
                binding.premiumBtn.setOnClickListener {
                    val context = requireContext()
                    dismiss()
                    WidgetSettingsPremium.launch(context, locationSection = PREMIUM_UPSELL_LOCATION)
                }
            }

            !isUserInGuild -> {
                binding.buttonContainer.visibility = View.VISIBLE
                binding.premiumBtn.visibility = View.GONE
                binding.joinBtn.visibility = View.VISIBLE
                binding.joinBtn.setOnClickListener { viewModel.joinGuild(requireContext(), guild) }
            }

            else -> binding.buttonContainer.visibility = View.GONE
        }
    }

    private fun configureFavorite(canFavorite: Boolean, isFavorite: Boolean) {
        binding.favoriteContainer.visibility = if (canFavorite) View.VISIBLE else View.GONE
        // The two buttons are stacked; cross-fading keeps the row from resizing on toggle.
        binding.favoriteAddButton.fadeBy(!isFavorite, FAVORITE_FADE_MS)
        binding.favoriteAddButton.setOnClickListener { viewModel.setFavorite(true) }
        binding.favoriteRemoveButton.fadeBy(isFavorite, FAVORITE_FADE_MS)
        binding.favoriteRemoveButton.setOnClickListener { viewModel.setFavorite(false) }
    }

    /** The source-server card; a known guild is always shown so the emoji's origin is explicit. */
    private fun configureGuildSection(
        guild: Guild?,
        isPublic: Boolean,
        approximateOnline: Int?,
    ) {
        if (guild == null) {
            binding.guildContainer.visibility = View.GONE
            return
        }
        binding.guildContainer.visibility = View.VISIBLE

        if (guild.hasIcon()) {
            IconUtils.setIcon(binding.guildIv, guild, changeDetector = imageChangeDetector, allowAnimation = true)
        } else {
            binding.guildAvatarWrap.setBackgroundResource(R.drawable.drawable_circle_black)
            binding.guildAvatarWrap.backgroundTintList = ColorStateList.valueOf(
                ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundSecondary),
            )
            binding.guildAvatarText.text = guild.shortName
        }

        binding.guildNameTv.setCompoundDrawablesCompat(
            start = when {
                guild.hasFeature(GuildFeature.PARTNERED) -> R.drawable.ic_partnered_badge_banner
                guild.hasFeature(GuildFeature.VERIFIED) -> R.drawable.ic_verified_badge_banner
                else -> 0
            },
        )
        binding.guildNameTv.text = guild.name

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
        binding.guildInfoTv.text = onlinePrefix + visibility
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
