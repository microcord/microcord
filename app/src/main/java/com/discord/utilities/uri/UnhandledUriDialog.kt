package com.discord.utilities.uri

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.databinding.LayoutUnhandledUriBinding

class UnhandledUriDialog : AppDialog(R.layout.layout_unhandled_uri) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val url = argumentsOrDefault.getString(ARG_URL).orEmpty()
        val binding = LayoutUnhandledUriBinding.bind(view)
        binding.unhandledUriDisplay.text = url
        binding.unhandledUriDisplay.setOnClickListener { AppToast.copyToClipboard(it.context, url) }
        binding.unhandledUriOkay.setOnClickListener { dismiss() }
    }

    companion object {
        private const val ARG_URL = "url"

        fun show(fragmentManager: FragmentManager, url: String) {
            UnhandledUriDialog().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }.show(fragmentManager, UnhandledUriDialog::class.java.name)
        }
    }
}
