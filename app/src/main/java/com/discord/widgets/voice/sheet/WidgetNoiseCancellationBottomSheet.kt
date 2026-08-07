package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.i18nRender
import com.discord.utilities.rx.bindToComponentLifecycle
import com.google.android.material.button.MaterialButton

/**
 * One-time explainer for Krisp noise cancellation, shown the first time the voice sheet's
 * noise-cancellation button is pressed. Enabling from here turns the feature on and dismisses;
 * dismissing without enabling leaves the setting untouched.
 */
class WidgetNoiseCancellationBottomSheet : AppBottomSheet() {

    private val viewModel: WidgetNoiseCancellationBottomSheetViewModel by appViewModels {
        WidgetNoiseCancellationBottomSheetViewModel()
    }

    private lateinit var learnMore: TextView
    private lateinit var enableButton: MaterialButton

    override fun getContentViewResId(): Int = R.layout.widget_voice_noise_cancellation_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        learnMore = view.findViewById(R.id.noise_cancellation_learn_more)
        enableButton = view.findViewById(R.id.noise_cancellation_enable_button)
        configureUI()
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .subscribe(::handleEvent)
    }

    private fun configureUI() {
        // `learn_more_link` is "[Learn More]({helpdeskArticle})". `i18nFormat` returns plain text,
        // which leaves the markdown on screen verbatim; the span renderer is what turns it into the
        // labelled link the movement method can then open.
        learnMore.text = learnMore.context.i18nRender(
            R.string.learn_more_link,
            "helpdeskArticle" to HELPDESK_ARTICLE_URL,
        )
        learnMore.movementMethod = LinkMovementMethod.getInstance()
        enableButton.setOnClickListener {
            viewModel.onNoiseCancellationButtonPressed()
            dismiss()
        }
    }

    private fun handleEvent(event: WidgetNoiseCancellationBottomSheetViewModel.Event) {
        when (event) {
            is WidgetNoiseCancellationBottomSheetViewModel.Event.ShowToast ->
                AppToast.show(this, event.toastResId)
        }
    }

    companion object {
        /** Reference: `f.a.a(360040843952L, null)`, the helpdesk-article URL builder. */
        private const val HELPDESK_ARTICLE_URL =
            "https://support.discord.com/hc/articles/360040843952"

        fun show(fragment: Fragment) {
            WidgetNoiseCancellationBottomSheet().show(
                fragment.parentFragmentManager,
                WidgetNoiseCancellationBottomSheet::class.java.name,
            )
        }
    }
}
