package com.discord.utilities.locale

import android.content.Context
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * Resolves the locale that user-facing text should be formatted in.
 *
 * The app locale can differ from the process default (the user may override the language in
 * settings), so formatting must follow the resource configuration of the context that owns the
 * view. Without a context the process default is the only thing available.
 */
class LocaleManager {

    fun getPrimaryLocale(context: Context?): Locale {
        if (context == null) return Locale.getDefault()
        return ConfigurationCompat.getLocales(context.resources.configuration)[0]
            ?: Locale.getDefault()
    }
}
