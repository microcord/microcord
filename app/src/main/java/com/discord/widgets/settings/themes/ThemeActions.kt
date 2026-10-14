package com.discord.widgets.settings.themes

import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeRuntime
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

/** Turning themes on and off from the theme screens. */
internal object ThemeActions {

    /**
     * Enables exactly [ids]: downloads their missing files and builds their table off the main
     * thread, then turns them on and recreates the screen so it shows.
     *
     * Not tied to the fragment's lifecycle: leaving the screen mid-download still applies the
     * themes, and every activity recreates when it next resumes.
     */
    fun enable(fragment: AppFragment, ids: List<String>) {
        if (!ThemeRuntime.isSupported) return
        val context = fragment.requireContext().applicationContext
        val store = ThemeRuntime.store(context)
        val themes = ids.mapNotNull(store::read)
        if (themes.any { theme -> ThemeAssets.references(theme).any { ThemeAssets.local(context, it) == null } }) {
            AppToast.show(context, R.string.microcord_themes_downloading)
        }
        Observable.fromCallable {
            val failed = ThemeAssets.fetchMissing(context, themes).size
            runCatching { ThemeRuntime.prepare(context, ids) }
            failed
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { failed ->
                    if (failed > 0) AppToast.show(context, context.getString(R.string.microcord_themes_download_failed, failed))
                    apply(fragment, ids)
                },
                { apply(fragment, ids) },
            )
    }

    private fun apply(fragment: AppFragment, ids: List<String>) {
        val context = fragment.context?.applicationContext ?: return
        try {
            ThemeRuntime.enable(context, ids)
        } catch (e: Exception) {
            AppToast.show(context, context.getString(R.string.microcord_themes_apply_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        val skipped = ThemeRuntime.lastWarnings.size
        if (skipped > 0) AppToast.show(context, context.getString(R.string.microcord_themes_skipped, skipped))
        fragment.appActivity?.takeIf { fragment.isResumed }?.let(AppScreenRouter::recreate)
    }
}
