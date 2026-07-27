package com.discord.widgets.guild_role_subscriptions

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import rx.subscriptions.CompositeSubscription

/**
 * Lets a creator pick what a tier costs.
 *
 * Results come back through the fragment-result API rather than a listener so the sheet survives
 * the host being recreated behind it; [registerForResult] and [launch] must be given the same key.
 */
class WidgetPriceTierPickerBottomSheet : AppBottomSheet() {

    private val viewModel: PriceTierPickerBottomSheetViewModel by appViewModels {
        PriceTierPickerBottomSheetViewModel()
    }

    private lateinit var adapter: PriceTierPickerAdapter

    private lateinit var appFlipper: AppViewFlipper

    private val itemClickListener: (Int) -> Unit = { priceTier ->
        val requestKey = arguments?.getString(ARG_REQUEST_KEY).orEmpty()
        setFragmentResult(requestKey, bundleOf(RESULT_EXTRA_PRICE_TIER to priceTier))
        dismiss()
    }

    override fun getContentViewResId(): Int = R.layout.widget_price_tier_picker_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appFlipper = requireNotNull(view.findViewById(R.id.price_tier_picker_app_flipper))
        adapter = PriceTierPickerAdapter(itemClickListener)
        val recycler: RecyclerView = requireNotNull(view.findViewById(R.id.price_tier_picker_recycler))
        recycler.adapter = adapter
        val padding = resources.getDimensionPixelSize(R.dimen.guild_role_subscription_setup_default_padding)
        recycler.addItemDecoration(InsetDividerItemDecoration(recycler, padding))
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetPriceTierPickerBottomSheet::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: PriceTierPickerBottomSheetViewModel.ViewState) {
        when (viewState) {
            is PriceTierPickerBottomSheetViewModel.ViewState.Loading ->
                appFlipper.displayedChild = FLIPPER_LOADING
            is PriceTierPickerBottomSheetViewModel.ViewState.Loaded -> {
                appFlipper.displayedChild = FLIPPER_RESULT
                adapter.setItems(viewState.priceTiers)
            }
            // Nothing to pick from, so the sheet says so and gets out of the way rather than
            // showing an empty list the user could stare at.
            is PriceTierPickerBottomSheetViewModel.ViewState.Failed -> {
                AppToast.show(requireContext(), R.string.default_failure_to_perform_action_message)
                dismiss()
            }
        }
    }

    /**
     * A divider inset by [padding] on both sides.
     *
     * Reference: `utilities/view/recycler/PaddedItemDecorator`, which has not been reconstructed.
     * Replace this with it when it lands.
     */
    private class InsetDividerItemDecoration(
        recyclerView: RecyclerView,
        private val padding: Int,
    ) : DividerItemDecoration(recyclerView.context, VERTICAL) {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            super.getItemOffsets(outRect, view, parent, state)
            outRect.left = padding
            outRect.right = padding
        }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val RESULT_EXTRA_PRICE_TIER = "RESULT_EXTRA_PRICE_TIER"

        private const val FLIPPER_LOADING = 0
        private const val FLIPPER_RESULT = 1

        fun launch(fragment: Fragment, requestKey: String) {
            WidgetPriceTierPickerBottomSheet()
                .apply { arguments = bundleOf(ARG_REQUEST_KEY to requestKey) }
                .show(fragment.parentFragmentManager, WidgetPriceTierPickerBottomSheet::class.java.name)
        }

        /** Must be called before [launch], from the same fragment, with the same [requestKey]. */
        fun registerForResult(
            fragment: Fragment,
            requestKey: String,
            onPriceTierSelected: (Int) -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { resultRequestKey, bundle ->
                if (requestKey == resultRequestKey) {
                    onPriceTierSelected(bundle.getInt(RESULT_EXTRA_PRICE_TIER, -1))
                }
            }
        }
    }
}
