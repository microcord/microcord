package com.discord.widgets.chat.list

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.i18n.i18nRender
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.button.MaterialButton

class PublishActionDialog : AppDialog(R.layout.publish_message_dialog) {

    private lateinit var viewModel: PublishActionDialogViewModel
    private var onSuccess: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        arguments?.let { arguments ->
            val theme = TypedValue()
            context?.theme?.resolveAttribute(
                arguments.getInt(ARG_THEME_ID, R.attr.dialogTheme),
                theme,
                true,
            )
            setStyle(STYLE_NO_TITLE, theme.resourceId)
        }
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val arguments = argumentsOrDefault
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PublishActionDialogViewModel(
                        arguments.getLong(EXTRA_MESSAGE_ID),
                        arguments.getLong(EXTRA_CHANNEL_ID),
                    ) as T
            },
        )[PublishActionDialogViewModel::class.java]

        view.findViewById<TextView>(R.id.notice_header).setText(R.string.news_channel_publish_bump)
        view.findViewById<LinearLayout>(R.id.notice_header_container).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.notice_body_text).apply {
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            movementMethod = WidgetNoticeDialog.ActionLinkMovementMethod(::dismiss)
        }
        view.findViewById<MaterialButton>(R.id.notice_ok).apply {
            setTextAndVisibilityBy(getString(R.string.news_channel_publish))
            setOnClickListener {
                viewModel.publishMessage()
                onSuccess?.invoke()
            }
        }
        view.findViewById<MaterialButton>(R.id.CANCEL_BUTTON).apply {
            setTextAndVisibilityBy(getString(R.string.cancel))
            setOnClickListener { dismiss() }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this)
            .appSubscribe(PublishActionDialog::class.java, onNext = ::updateView)
        viewModel.observeEvents().ui(this)
            .appSubscribe(PublishActionDialog::class.java, onNext = ::handleEvent)
    }

    private fun updateView(viewState: PublishActionDialogViewModel.ViewState) {
        val body = requireView().findViewById<TextView>(R.id.notice_body_text)
        body.text = when (viewState) {
            PublishActionDialogViewModel.ViewState.Loading -> getString(R.string.loading)
            PublishActionDialogViewModel.ViewState.LoadedNoFollowers ->
                getString(R.string.publish_followed_news_body)
            is PublishActionDialogViewModel.ViewState.LoadedHasFollowers -> requireContext().i18nRender(
                R.string.publish_followed_news_body_reach,
                "numGuildsFollowing" to viewState.followerStats.guildsFollowing,
            )
        }
    }

    private fun handleEvent(event: PublishActionDialogViewModel.Event) {
        AppToast.show(
            this,
            when (event) {
                is PublishActionDialogViewModel.Event.Success -> R.string.message_published
                is PublishActionDialogViewModel.Event.Failure -> R.string.publish_followed_news_generic_body
            },
        )
        dismiss()
    }

    companion object {
        private const val ARG_THEME_ID = "theme_id"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            messageId: Long,
            channelId: Long,
            onSuccess: (() -> Unit)? = null,
            dialogTheme: Int? = null,
        ) {
            PublishActionDialog().apply {
                this.onSuccess = onSuccess
                arguments = bundleOf(
                    EXTRA_MESSAGE_ID to messageId,
                    EXTRA_CHANNEL_ID to channelId,
                ).apply {
                    if (dialogTheme != null) putInt(ARG_THEME_ID, dialogTheme)
                }
            }.show(fragmentManager, PublishActionDialog::class.java.name)
        }
    }
}
