package com.discord.widgets.settings.themes

import android.content.Context
import com.discord.R
import com.discord.app.AppToast
import com.discord.utilities.theme.ThemeAssets
import com.discord.utilities.theme.ThemeJson
import com.discord.utilities.theme.ThemeRuntime
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

/**
 * Installs a theme file posted in a channel, as Aliucord's "Install" message action does, then
 * opens the theme list so it can be turned on. [ThemeAssets.fetch] re-signs the attachment link.
 */
internal object ThemeInstaller {
    fun install(context: Context, url: String, fallbackName: String) {
        val app = context.applicationContext
        Observable.fromCallable {
            val theme = ThemeJson.parse(ThemeAssets.fetch(url, 4 * ThemeJson.MAX_CHARS).toString(Charsets.UTF_8), fallbackName).theme
            ThemeRuntime.store(app).save(theme)
            theme.manifest.name
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { name ->
                    AppToast.show(app, app.getString(R.string.microcord_themes_imported, name))
                    WidgetSettingsThemes.launch(context)
                },
                { error -> AppToast.show(app, app.getString(R.string.microcord_themes_import_failed, error.message ?: error.javaClass.simpleName)) },
            )
    }
}
