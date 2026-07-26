package com.discord.widgets.stickers

import android.os.Bundle
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.i18n.i18nRender
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.input.sticker.WidgetStickerPackStoreSheet
import com.discord.widgets.chat.input.sticker.WidgetStickerPickerSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import rx.subscriptions.CompositeSubscription

/**
 * The popout for a *standard* sticker tapped in a message: a big preview of its pack, the pack
 * name, and the way in to the rest of the pack.
 *
 * Show it through [StickerSheets.show], which picks this sheet, [WidgetGuildStickerSheet] or
 * [WidgetUnknownStickerSheet] from the sticker's type.
 */
class WidgetStickerSheet : AppBottomSheet() {

    private val sticker: Sticker
        get() = requireNotNull(
            argumentsOrDefault.getSerializable(EXTRA_STICKER) as? Sticker,
        ) { "WidgetStickerSheet requires a Sticker in $EXTRA_STICKER" }

    private val viewModel: StickerSheetViewModel by appViewModels {
        StickerSheetViewModel(
            sticker = sticker,
            location = argumentsOrDefault.getString(ANALYTICS_LOCATION),
        )
    }

    private lateinit var stickerName: TextView
    private lateinit var stickerInfo: TextView
    private lateinit var packStickers: List<StickerView>
    private lateinit var buyButton: LoadingButton
    private lateinit var viewButton: MaterialButton
    private lateinit var limitedContainer: RelativeLayout
    private lateinit var limitedChip: Chip

    override fun getContentViewResId(): Int = R.layout.widget_sticker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stickerName = view.findViewById(R.id.sticker_sheet_sticker_name)
        stickerInfo = view.findViewById(R.id.sticker_sheet_sticker_info)
        packStickers = listOf(
            view.findViewById(R.id.stickers_sheet_sticker_1),
            view.findViewById(R.id.stickers_sheet_sticker_2),
            view.findViewById(R.id.stickers_sheet_sticker_3),
            view.findViewById(R.id.stickers_sheet_sticker_4),
        )
        buyButton = view.findViewById(R.id.sticker_sheet_buy_button)
        viewButton = view.findViewById(R.id.sticker_sheet_view_button)
        limitedContainer = view.findViewById(R.id.sticker_sheet_view_limited_container)
        limitedChip = view.findViewById(R.id.sticker_sheet_view_limited_chip)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(viewState: StickerSheetViewModel.ViewState) {
        val pack = viewState.stickerPack
        val context = requireContext()
        stickerName.text = viewState.sticker.name

        stickerInfo.text = when {
            viewState.isStickerPackEnabled ||
                viewState.canUsePremiumStickers ||
                pack.isPremiumPack() ->
                context.i18nRender(
                    R.string.sticker_popout_pack_info_premium,
                    "stickerPackName" to pack.name,
                )
            !pack.canBePurchased() ->
                context.i18nRender(
                    R.string.sticker_popout_pack_info_unavailable,
                    "stickerPackName" to pack.name,
                )
            else -> context.i18nRender(
                R.string.sticker_popout_pack_info,
                "stickerPackName" to pack.name,
                "discount" to StickerPackFormatting.calculatePremiumStickerPackDiscount(),
            )
        }
        // The "Get Nitro" run inside that copy is a `(onClick)` hook in the reference, which routes
        // to WidgetSettingsPremium. That screen does not exist yet, so the whole label is tappable
        // and lands on the same upsell modal the buy button uses — see StickerPremiumUpsellDialog.
        if (!viewState.canUsePremiumStickers) {
            stickerInfo.setOnClickListener { showPremiumUpsell() }
        } else {
            stickerInfo.setOnClickListener(null)
        }

        // The layout has exactly four preview slots; a pack with fewer simply leaves them blank.
        packStickers.forEachIndexed { index, stickerView ->
            pack.stickers.getOrNull(index)?.let { stickerView.setSticker(it, ALWAYS_ANIMATE) }
        }

        buyButton.setText(
            StickerPackFormatting.getStickerPackPremiumPriceLabel(
                context,
                pack,
                viewState.meUserPremiumTier,
                viewState.isStickerPackEnabled,
            ),
        )
        buyButton.setIsLoading(false)
        buyButton.setOnClickListener { showPremiumUpsell() }
        val showBuyButton = viewState.isStickerPackEnabled && !viewState.canUsePremiumStickers
        buyButton.visibility = if (showBuyButton) View.VISIBLE else View.GONE

        viewButton.visibility = if (viewState.isStickerPackEnabled) View.VISIBLE else View.GONE
        viewButton.setOnClickListener { onViewPackClicked(viewState) }

        limitedContainer.visibility = if (pack.isLimitedPack()) View.VISIBLE else View.GONE
        limitedChip.text = StickerPackFormatting.getLimitedTimeLeftString(context, pack.storeListing)
    }

    private fun showPremiumUpsell() {
        dismiss()
        StickerPremiumUpsellDialog.show(parentFragmentManager)
    }

    /**
     * "View Sticker Pack".
     *
     * A user who owns the pack goes to the picker opened on it. The reference gets there by
     * emitting `ExpressionPickerEvent.OpenStickerPicker` on the `ExpressionPickerEventBus`, so the
     * already-open expression tray switches tabs; that tray does not exist in this project, so the
     * picker is opened as its own sheet instead — same destination, one more surface.
     */
    private fun onViewPackClicked(viewState: StickerSheetViewModel.ViewState) {
        if (viewState.isStickerPackEnabled && viewState.canUsePremiumStickers) {
            WidgetStickerPickerSheet.show(
                fragmentManager = parentFragmentManager,
                stickerPickerListener = null,
                initialStickerPackId = viewState.sticker.packId,
            )
        } else {
            WidgetStickerPackStoreSheet.show(
                fragmentManager = parentFragmentManager,
                sticker = viewState.sticker,
                analyticsLocation = argumentsOrDefault.getString(ANALYTICS_LOCATION),
                purchaseLocation = StickerPurchaseLocation.getPopoutPurchaseLocation(
                    viewState.stickerPack.canBePurchased(),
                ),
            )
        }
        dismiss()
    }

    companion object {
        const val EXTRA_STICKER = "com.discord.intent.extra.EXTRA_STICKER"
        private const val ANALYTICS_LOCATION = "widget_sticker_sheet_analytics_location"

        /** [com.discord.utilities.stickers.StickerUtils.ALWAYS_ANIMATE]. */
        private const val ALWAYS_ANIMATE = 0

        /**
         * Only meaningful for [StickerType.STANDARD]; other types are handled by the sibling
         * sheets. [channelId] decides the analytics page name only.
         */
        fun show(fragmentManager: FragmentManager, sticker: Sticker, channelId: Long) {
            if (sticker.type != StickerType.STANDARD) return
            val channel = StoreStream.getChannels().findChannelById(channelId) ?: return
            val page = if (channel.guildId == 0L) "DM Channel" else "Guild Channel"
            WidgetStickerSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(EXTRA_STICKER, sticker)
                    putString(ANALYTICS_LOCATION, page)
                }
            }.show(fragmentManager, WidgetStickerSheet::class.java.name)
        }
    }
}
