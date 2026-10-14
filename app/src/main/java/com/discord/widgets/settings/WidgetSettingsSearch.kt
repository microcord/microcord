package com.discord.widgets.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsSearchBinding
import com.discord.databinding.WidgetSettingsSearchItemBinding
import com.discord.views.CheckedSetting
import com.discord.views.ThemedColor

/**
 * Searches the rows of every settings page that [WidgetSettings] pushes ([WidgetSettings.routes]).
 *
 * Microcord addition; Discord 126.21 has no settings search. The index is read from the pages'
 * own layouts, so a new row is searchable without registering it anywhere. Text a page only sets
 * in code is not indexed. Picking a result opens the page, scrolls to the row and flashes it.
 */
class WidgetSettingsSearch : AppFragment(R.layout.widget_settings_search) {

    private class Entry(val title: String, val page: String?, val routeId: Int, val path: IntArray?, val keywords: String)

    private var entries: List<Entry> = emptyList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.search)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        val binding = WidgetSettingsSearchBinding.bind(view)
        entries = buildIndex(requireContext())
        binding.settingsSearchInput.bindTextWatcher(this) { query -> show(binding, query) }
        binding.settingsSearchInput.getEditText().requestFocus()
        showKeyboard(binding.settingsSearchInput.getEditText())
    }

    private fun show(binding: WidgetSettingsSearchBinding, query: String) {
        val tokens = query.lowercase().split(' ').filter(String::isNotEmpty)
        val matches = if (tokens.isEmpty()) emptyList() else entries
            .filter { entry -> tokens.all { it in entry.keywords } }
            .sortedBy { if (it.title.lowercase().startsWith(tokens.first())) 0 else 1 }
            .take(MAX_RESULTS)
        binding.settingsSearchEmpty.visibility = if (tokens.isNotEmpty() && matches.isEmpty()) View.VISIBLE else View.GONE
        binding.settingsSearchResults.removeAllViews()
        for (entry in matches) {
            val row = WidgetSettingsSearchItemBinding.inflate(layoutInflater, binding.settingsSearchResults, true)
            row.settingsSearchItemTitle.text = entry.title
            row.settingsSearchItemPage.text = entry.page
            row.settingsSearchItemPage.visibility = if (entry.page == null) View.GONE else View.VISIBLE
            row.root.setOnClickListener { open(entry) }
        }
    }

    private fun open(entry: Entry) {
        hideKeyboard()
        val page = WidgetSettings.routes.getValue(entry.routeId)()
        val path = entry.path
        if (path != null) {
            parentFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    if (f !== page) return
                    fm.unregisterFragmentLifecycleCallbacks(this)
                    v.post { highlight(v, path) }
                }
            }, false)
        }
        SettingsNavigation.push(this, page)
    }

    private companion object {
        const val MAX_RESULTS = 50

        /** Longer text is a description paragraph rather than a row name. */
        const val MAX_TITLE_LENGTH = 80

        private class Row(val path: IntArray, val title: String, val detail: String)

        private fun buildIndex(context: Context): List<Entry> {
            val inflater = LayoutInflater.from(context)
            val settings = inflater.inflate(R.layout.widget_settings, null, false)
            return WidgetSettings.routes.flatMap { (routeId, newPage) ->
                val name = settings.findViewById<TextView>(routeId).text.toString()
                // A settings page's onCreateView only inflates its content layout.
                val layout = runCatching { newPage().onCreateView(inflater, null, null) }.getOrNull()
                val rows = layout?.let { rows(it, IntArray(0), mutableListOf()) }.orEmpty()
                listOf(Entry(name, null, routeId, null, name.lowercase())) +
                    rows.distinctBy { it.title }.map { Entry(it.title, name, routeId, it.path, "${it.title} ${it.detail} $name".lowercase()) }
            }
        }

        /** Searchable rows of an inflated page; [Row.path] holds child indices from the page root. */
        private fun rows(view: View, path: IntArray, out: MutableList<Row>): List<Row> {
            if (view.visibility == View.GONE) return out
            when (view) {
                is CheckedSetting -> {
                    val label = view.findViewById<TextView>(R.id.setting_label).text.toString()
                    if (label.isNotBlank()) out += Row(path, label, view.findViewById<TextView>(R.id.setting_subtext).text.toString())
                }
                is EditText -> Unit
                is TextView -> view.text.toString().takeIf { it.isNotBlank() && it.length <= MAX_TITLE_LENGTH }?.let { out += Row(path, it, "") }
                is ViewGroup -> for (i in 0 until view.childCount) rows(view.getChildAt(i), path + i, out)
            }
            return out
        }

        private fun highlight(root: View, path: IntArray) {
            var target = root
            for (index in path) target = (target as? ViewGroup)?.getChildAt(index) ?: return
            if (!target.isShown) return
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height))
            val flash = ColorDrawable(ThemedColor.resolve(target.context, R.attr.colorBackgroundModifierSelected))
            flash.setBounds(0, 0, target.width, target.height)
            flash.alpha = 0
            target.overlay.add(flash)
            ValueAnimator.ofInt(0, 255, 0).apply {
                duration = 1200
                addUpdateListener { flash.alpha = it.animatedValue as Int }
                doOnEnd { target.overlay.remove(flash) }
                start()
            }
        }
    }
}
