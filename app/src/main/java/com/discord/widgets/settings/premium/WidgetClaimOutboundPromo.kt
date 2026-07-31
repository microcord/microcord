package com.discord.widgets.settings.premium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppViewFlipper
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

class WidgetClaimOutboundPromo : AppDialog(R.layout.widget_claim_outbound_promo) {
    private lateinit var viewModel: ClaimOutboundPromoViewModel
    private lateinit var flipper: AppViewFlipper
    private lateinit var body: TextView
    private lateinit var code: TextView
    private lateinit var codeBox: View
    private lateinit var copy: MaterialButton
    private val codeBoxBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f
        setColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val status = requireNotNull(arguments?.getParcelable<ClaimStatus>(ARG_CLAIM_STATUS))
        viewModel = ViewModelProvider(this, Factory(status))[ClaimOutboundPromoViewModel::class.java]
        flipper = view.findViewById(R.id.claimPromoFlipper)
        body = view.findViewById(R.id.claimPromoBody)
        code = view.findViewById(R.id.claimPromoCode)
        codeBox = view.findViewById(R.id.claimPromoCodeBox)
        copy = view.findViewById(R.id.claimPromoCopyButton)
        ViewCompat.setBackground(codeBox, codeBoxBackground)
        setCodeBoxColor(resolveColor(R.attr.colorInteractiveNormal))

        copy.setOnClickListener { viewModel.copyClicked() }
        view.findViewById<View>(R.id.claimPromoRedeemButton).setOnClickListener { viewModel.redeemClicked() }
        view.findViewById<View>(R.id.claimPromoMaybeLaterButton).setOnClickListener { viewModel.maybeLaterClicked() }
        view.findViewById<View>(R.id.claimPromoFailureCloseButton).setOnClickListener { viewModel.failureCloseClicked() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass, onNext = ::handleEvent)
    }

    private fun render(state: ClaimOutboundPromoViewModel.ViewState) {
        (view as? ViewGroup)?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(200))
        }
        when (state) {
            ClaimOutboundPromoViewModel.ViewState.ClaimInProgress -> flipper.displayedChild = INDEX_LOADING
            ClaimOutboundPromoViewModel.ViewState.ClaimFailed -> {
                flipper.measureAllChildren = false
                flipper.displayedChild = INDEX_FAILURE
                isCancelable = true
            }
            is ClaimOutboundPromoViewModel.ViewState.Claimed -> {
                body.text = state.claimedStatus.body
                code.text = state.claimedStatus.code
                flipper.displayedChild = INDEX_SUCCESS
                isCancelable = true
            }
        }
    }

    private fun handleEvent(event: ClaimOutboundPromoViewModel.Event) = when (event) {
        is ClaimOutboundPromoViewModel.Event.Claimed -> parentFragmentManager.setFragmentResult(
            KEY_PROMO_CLAIMED,
            Bundle().apply { putParcelable(KEY_PROMO_CLAIMED, event.claimedStatus) },
        )
        is ClaimOutboundPromoViewModel.Event.CopyCode -> {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Promo Code", event.code))
            copy.setText(R.string.copied)
            setCodeBoxColor(ContextCompat.getColor(requireContext(), R.color.status_green_600))
        }
        is ClaimOutboundPromoViewModel.Event.OpenRedemptionUrl -> {
            UriHandler.handle(requireContext(), event.url)
            dismiss()
        }
        ClaimOutboundPromoViewModel.Event.Dismiss -> dismiss()
    }

    private fun setCodeBoxColor(color: Int) {
        codeBoxBackground.setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), color)
        codeBox.invalidate()
    }

    private fun resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private class Factory(private val status: ClaimStatus) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ClaimOutboundPromoViewModel(status) as T
        }
    }

    companion object {
        private const val ARG_CLAIM_STATUS = "ARG_CLAIM_STATUS"
        private const val KEY_PROMO_CLAIMED = "KEY_PROMO_CLAIMED"
        private const val INDEX_LOADING = 0
        private const val INDEX_SUCCESS = 1
        private const val INDEX_FAILURE = 2

        fun showAndRegisterForClaimResult(
            claimStatus: ClaimStatus,
            fragment: Fragment,
            onPromoClaimed: (ClaimStatus.Claimed) -> Unit,
        ) {
            val manager = fragment.parentFragmentManager
            if (manager.findFragmentByTag(WidgetClaimOutboundPromo::class.java.name) != null) return
            if (claimStatus is ClaimStatus.Unclaimed) {
                manager.setFragmentResultListener(KEY_PROMO_CLAIMED, fragment) { _, result ->
                    result.getParcelable<ClaimStatus.Claimed>(KEY_PROMO_CLAIMED)?.let(onPromoClaimed)
                }
            }
            WidgetClaimOutboundPromo().apply {
                arguments = Bundle().apply { putParcelable(ARG_CLAIM_STATUS, claimStatus) }
            }.show(manager, WidgetClaimOutboundPromo::class.java.name)
        }
    }
}
