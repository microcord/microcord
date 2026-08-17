package com.discord.widgets.chat.input.sticker

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.widgets.stickers.StickerPackFormatting
import com.discord.widgets.stickers.StickerPremiumUpsellDialog
import com.discord.widgets.stickers.StickerPurchaseLocation
import com.discord.widgets.stickers.StickerSendability
import com.discord.widgets.stickers.WidgetStickerPackDetailsDialog
import com.discord.views.VitoImageView
import rx.subscriptions.CompositeSubscription

/**
 * The whole pack behind one sticker: its banner, every sticker in it, and its price.
 *
 * This is the destination of the sticker popout's "View Sticker Pack" for a pack the user does not
 * already own. Tapping a tile here previews it full size rather than sending it — the user does not
 * have the pack yet.
 */
class WidgetStickerPackStoreSheet : AppBottomSheet() {

    private val sticker: Sticker
        get() = requireNotNull(
            argumentsOrDefault.getSerializable(EXTRA_STICKER) as? Sticker,
        ) { "WidgetStickerPackStoreSheet requires a Sticker in $EXTRA_STICKER" }

    private val viewModel: StickerPackStoreSheetViewModel by appViewModels {
        StickerPackStoreSheetViewModel(sticker)
    }

    private lateinit var banner: VitoImageView
    private lateinit var recycler: RecyclerView
    private lateinit var buyButtonContainer: View
    private lateinit var buyButton: LoadingButton
    private lateinit var adapter: WidgetStickerAdapter

    override fun getContentViewResId(): Int = R.layout.widget_sticker_pack_store_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        banner = view.findViewById(R.id.sticker_pack_view_all_banner)
        recycler = view.findViewById(R.id.sticker_pack_store_sheet_recycler)
        buyButtonContainer = view.findViewById(R.id.sticker_pack_store_sheet_buy_button_container)
        buyButton = view.findViewById(R.id.sticker_store_buy_button_premium)
        recycler.itemAnimator = null
        adapter = WidgetStickerAdapter(
            recycler = recycler,
            onStickerItemSelected = { item ->
                StickerFullSizeDialog.show(parentFragmentManager, item.sticker)
            },
            onStickerHeaderItemClicked = { header ->
                WidgetStickerPackDetailsDialog.show(parentFragmentManager, header.pack.id)
            },
            appComponent = this,
            showStickerPackDescriptions = true,
        )
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { viewState ->
                if (viewState is StickerPackStoreSheetViewModel.ViewState.Loaded) configureUI(viewState)
            }
    }

    private fun configureUI(viewState: StickerPackStoreSheetViewModel.ViewState.Loaded) {
        val pack = viewState.stickerPack
        MGImages.setImage(banner, StickerPackFormatting.getBannerCDNAssetUrl(pack))
        adapter.setData(buildRows(pack, viewState.stickerAnimationSettings))

        buyButton.setText(
            StickerPackFormatting.getStickerPackPremiumPriceLabel(
                requireContext(),
                pack,
                viewState.meUserPremiumTier,
                isPackEnabled = false,
            ),
        )
        buyButton.setIsLoading(false)
        buyButton.setOnClickListener {
            dismiss()
            StickerPremiumUpsellDialog.show(parentFragmentManager)
        }
        // A pack that is no longer for sale has nothing to offer but the preview.
        buyButtonContainer.visibility = if (pack.canBePurchased()) View.VISIBLE else View.GONE
    }

    private fun buildRows(pack: ModelStickerPack, animationSettings: Int): List<MGRecyclerDataPayload> =
        listOf(StoreHeaderItem(pack, isNewPack = false)) +
            pack.stickers.map { sticker ->
                StickerItem(
                    sticker = sticker,
                    stickerAnimationSettings = animationSettings,
                    mode = StickerItem.Mode.STORE,
                    // Sendability is irrelevant on a store tile: nothing here sends. SENDABLE keeps
                    // the art at full opacity.
                    sendability = StickerSendability.SENDABLE,
                )
            }

    companion object {
        private const val EXTRA_STICKER = "com.discord.intent.extra.EXTRA_STICKER"
        private const val EXTRA_VIEW_TYPE = "com.discord.intent.EXTRA_STICKER_VIEW_TYPE"
        private const val EXTRA_ANALYTICS_LOCATION = "com.discord.intent.EXTRA_ANALYTICS_LOCATION"
        private const val EXTRA_PURCHASE_LOCATION = "com.discord.intent.EXTRA_PURCHASE_LOCATION"

        fun show(
            fragmentManager: FragmentManager,
            sticker: Sticker,
            viewType: StickerPackStoreSheetViewType =
                StickerPackStoreSheetViewType.STICKER_POPOUT_VIEW_ALL,
            analyticsLocation: String? = null,
            purchaseLocation: StickerPurchaseLocation? = null,
        ) {
            WidgetStickerPackStoreSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(EXTRA_STICKER, sticker)
                    putSerializable(EXTRA_VIEW_TYPE, viewType)
                    putString(EXTRA_ANALYTICS_LOCATION, analyticsLocation)
                    putSerializable(EXTRA_PURCHASE_LOCATION, purchaseLocation)
                }
            }.show(fragmentManager, WidgetStickerPackStoreSheet::class.java.name)
        }
    }
}
