package com.discord.widgets.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetUrlActionsBinding
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.search.results.WidgetSearchResults

class WidgetUrlActions : AppBottomSheet() {
    private val url: String
        get() = arguments?.getString(INTENT_URL).orEmpty()

    override fun getContentViewResId() = R.layout.widget_url_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetUrlActionsBinding.bind(view)
        binding.dialogUrlActionsUrl.text = url
        binding.dialogUrlActionsUrl.visibility = if (url.isNotEmpty()) View.VISIBLE else View.GONE
        binding.dialogUrlActionsCopy.setOnClickListener {
            AppToast.copyToClipboard(requireContext(), url)
            dismiss()
        }
        binding.dialogUrlActionsOpen.setOnClickListener {
            UriHandler.handle(requireContext(), url)
            dismiss()
        }
        binding.dialogUrlActionsShare.setOnClickListener {
            val sendIntent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
            startActivity(Intent.createChooser(sendIntent, getString(R.string.form_label_send_to)))
            dismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        hideKeyboard()
    }

    override fun onPause() {
        dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        StoreStream.getNotices().markSeen(url)
        super.onDestroy()
    }

    companion object {
        private const val INTENT_URL = "INTENT_URL"

        @JvmStatic
        fun launch(fragmentManager: FragmentManager, url: String) {
            WidgetUrlActions().apply {
                arguments = Bundle().apply { putString(INTENT_URL, url) }
            }.show(fragmentManager, WidgetUrlActions::class.java.name)
        }

        @JvmStatic
        fun requestNotice(url: String) {
            StoreStream.getNotices().requestToShow(
                StoreNotices.Notice(
                    name = url,
                    priority = 0,
                    validScreens = listOf(WidgetHome::class, WidgetSearchResults::class),
                    delayPeriodMs = 0L,
                    sinceLastPeriodMs = 0L,
                    show = { activity ->
                        launch(activity.supportFragmentManager, url)
                        true
                    },
                ),
            )
        }
    }
}
