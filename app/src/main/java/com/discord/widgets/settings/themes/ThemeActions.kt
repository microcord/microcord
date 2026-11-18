package com.discord.widgets.settings.themes

import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.utilities.theme.ClientTheme
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeRuntime
import com.discord.utilities.theme.ThemeStore
import com.discord.utilities.user.UserUtils.isPremiumTier2
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

    /**
     * Applies a Nitro [theme] under the local themes and recreates the screen. With Nitro it also
     * goes to the account, as RN saves it; without, it stays on this device. Returns whether it applied.
     */
    fun applyClientTheme(activity: AppActivity?, theme: ClientTheme): Boolean {
        if (activity == null) return false
        if (!ThemeRuntime.isSupported) {
            AppToast.show(activity, R.string.microcord_themes_unsupported)
            return false
        }
        val sync = StoreStream.getUsers().getMe().isPremiumTier2 && StoreStream.getUserSettingsSystem().getIsThemeSyncEnabled()
        val picked = theme.withSynced(sync)
        try {
            ThemeRuntime.setClientTheme(activity, picked)
        } catch (e: Exception) {
            AppToast.show(activity, activity.getString(R.string.microcord_themes_apply_failed, e.message ?: e.javaClass.simpleName))
            return false
        }
        ThemeRuntime.store(activity).previewRestore = null
        if (sync) UserSettings.clientTheme.set(picked)
        AppScreenRouter.recreate(activity)
        return true
    }

    /**
     * Shows [theme] on this device only, as desktop's Preview does, until [endPreview] or the next
     * start. Previewing another theme keeps the one to go back to.
     */
    fun previewClientTheme(activity: AppActivity?, theme: ClientTheme) {
        if (activity == null || !ThemeRuntime.isSupported) return
        val store = ThemeRuntime.store(activity)
        val restore = store.previewRestore ?: ThemeStore.Restore(store.clientTheme)
        try {
            ThemeRuntime.setClientTheme(activity, theme.withSynced(false))
        } catch (e: Exception) {
            AppToast.show(activity, activity.getString(R.string.microcord_themes_apply_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        store.previewRestore = restore
        AppScreenRouter.recreate(activity)
    }

    /** Goes back to the theme from before the preview. */
    fun endPreview(activity: AppActivity?) {
        if (activity == null || !ThemeRuntime.isSupported) return
        val store = ThemeRuntime.store(activity)
        val restore = store.previewRestore ?: return
        store.previewRestore = null
        runCatching { ThemeRuntime.setClientTheme(activity, restore.theme) }
        AppScreenRouter.recreate(activity)
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
