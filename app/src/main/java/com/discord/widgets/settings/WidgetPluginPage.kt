package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetPluginPageBinding
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object PluginPageHost {
    private data class Page(
        val title: String,
        val subtitle: String,
        val createView: (Context) -> View,
    )

    private val pages = ConcurrentHashMap<String, Page>()

    fun open(context: Context, title: String, subtitle: String, createView: (Context) -> View) {
        val token = UUID.randomUUID().toString()
        pages[token] = Page(title, subtitle, createView)
        AppScreenRouter.launch(
            context,
            WidgetPluginPage::class.java,
            Intent().putExtra(EXTRA_TOKEN, token),
        )
    }

    private fun page(token: String) = pages[token]
    private fun remove(token: String) = pages.remove(token)

    private const val EXTRA_TOKEN = "plugin_page_token"

    class WidgetPluginPage : AppFragment(R.layout.widget_plugin_page) {
        private val token get() = requireArguments().getString(EXTRA_TOKEN).orEmpty()

        override fun onViewBound(view: View) {
            super.onViewBound(view)
            val page = page(token) ?: return
            setActionBarTitle(page.title)
            setActionBarSubtitle(page.subtitle)
            setActionBarDisplayHomeAsUpEnabled()
            SettingsNavigation.bindBack(this)
            val binding = WidgetPluginPageBinding.bind(view)
            runCatching { page.createView(requireContext()) }
                .onSuccess(binding.pluginPageContent::addView)
        }

        override fun onDestroy() {
            if (isRemoving || activity?.isFinishing == true) remove(token)
            super.onDestroy()
        }
    }
}
