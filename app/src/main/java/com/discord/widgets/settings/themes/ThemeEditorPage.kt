package com.discord.widgets.settings.themes

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeRuntime
import com.discord.utilities.theme.ThemeStore
import com.discord.widgets.settings.SettingsNavigation

/**
 * One page of the theme editor: the hub ([WidgetSettingsThemeEditor]) or one of the pages it opens.
 *
 * Every page reads the theme from its file and writes each change straight back, so pages need
 * nothing from each other. The hub re-reads on resume. Applying stays the hub's job.
 */
abstract class ThemeEditorPage(@LayoutRes layout: Int, @StringRes private val title: Int) : AppFragment(layout) {

    protected lateinit var store: ThemeStore
        private set
    protected lateinit var themeId: String
        private set
    protected lateinit var theme: ThemeDefinition
        private set

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        store = ThemeRuntime.store(requireContext())
        themeId = arguments?.getString(ARG_THEME_ID).orEmpty()
        theme = store.read(themeId) ?: run {
            requireActivity().finish()
            return
        }
        setActionBarTitle(title)
        setActionBarSubtitle(theme.manifest.name)
        setActionBarDisplayHomeAsUpEnabled()
        onThemeLoaded(view)
        render()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (!::themeId.isInitialized) return
        val latest = store.read(themeId) ?: return
        if (latest != theme) {
            theme = latest
            render()
        }
    }

    /** Binds the page once [theme] is known; [render] follows. */
    protected abstract fun onThemeLoaded(view: View)

    /** Shows [theme]; called after loading and after every change. */
    protected abstract fun render()

    protected fun save(updated: ThemeDefinition) {
        theme = updated
        store.save(updated, themeId)
        render()
    }

    companion object {
        const val ARG_THEME_ID = "theme_id"

        fun launch(context: Context, page: Class<out Fragment>, themeId: String, extras: Intent = Intent()) =
            SettingsNavigation.launch(context, page, extras.putExtra(ARG_THEME_ID, themeId))

        /** The pages' shared row label for an override: the file or link it points at, or "Default". */
        fun describeFile(context: Context, reference: String?): String =
            reference?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.microcord_theme_file_default)
    }
}
