package com.discord.dialogs

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.setI18nText
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.google.android.material.button.MaterialButton

/**
 * Asks permission to record that the account uses a screen reader.
 *
 * Shown once, when accessibility services are first detected. Both buttons write the answer and
 * dismiss — there is no "ask me later", because the notice is marked seen either way in
 * [WidgetAccessibilityDetectionDialogViewModel.onCleared], so declining is permanent until the
 * setting is changed in Settings → Accessibility.
 */
class WidgetAccessibilityDetectionDialog : AppDialog(R.layout.allow_accessibility_detection_dialog) {

    private val viewModel: WidgetAccessibilityDetectionDialogViewModel by appViewModels {
        WidgetAccessibilityDetectionDialogViewModel()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.accessibility_detection_disable)
            .setOnClickListener { setDetectionAllowed(false) }
        view.findViewById<MaterialButton>(R.id.accessibility_detection_enable)
            .setOnClickListener { setDetectionAllowed(true) }
        view.findViewById<TextView>(R.id.accessibility_detection_body_text).setI18nText(
            R.string.accessibility_detection_modal_body,
            "helpdeskArticle" to HELP_ARTICLE_URL,
        )
    }

    /**
     * The dialog dismisses immediately rather than waiting for the write to land: the setting is
     * account-level and syncs through the gateway, so a failed call simply leaves it unchanged.
     */
    private fun setDetectionAllowed(allowed: Boolean) {
        viewModel.setIsAccessibilityDetectionAllowed(allowed)
        dismiss()
    }

    companion object {
        /** Article 360035966492, "How does Discord use my accessibility information?". */
        private const val HELP_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/360035966492"

        /** `StoreNotices` notice name; kept verbatim so a persisted "seen" record still matches. */
        const val NOTICE_NAME: String = "ALLOW_ACCESSIBILITY_DETECTION_DIALOG"

        fun show(fragmentManager: FragmentManager) {
            WidgetAccessibilityDetectionDialog().show(
                fragmentManager,
                WidgetAccessibilityDetectionDialog::class.java.simpleName,
            )
        }
    }
}

/**
 * Owns the one write this dialog performs, and the notice bookkeeping around it.
 *
 * The state is a single `Uninitialized` object in the reference too — the dialog's content is
 * static. It exists as a view model purely so [onCleared] can mark the notice seen exactly once,
 * including when the dialog is dismissed by rotation rather than by a button.
 */
class WidgetAccessibilityDetectionDialogViewModel(
    private val storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
) : AppViewModel<WidgetAccessibilityDetectionDialogViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class ViewState {
        object Uninitialized : ViewState()
    }

    fun setIsAccessibilityDetectionAllowed(allowed: Boolean) {
        storeUserSettings.setIsAccessibilityDetectionAllowed(allowed)
            .restSubscribeOn()
            .appSubscribe(WidgetAccessibilityDetectionDialogViewModel::class.java) { }
    }

    /**
     * Marked seen here rather than in the dialog so a dismissal by rotation, back press or button
     * all count — the dialog must not reappear after the user has answered it once.
     */
    override fun onCleared() {
        super.onCleared()
        StoreStream.getNotices().markSeen(WidgetAccessibilityDetectionDialog.NOTICE_NAME)
    }
}
