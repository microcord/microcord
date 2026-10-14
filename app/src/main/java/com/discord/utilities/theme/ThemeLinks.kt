package com.discord.utilities.theme

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import com.discord.R
import com.discord.models.domain.ModelUserSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Theme attributes that share a color on the base theme follow each other.
 *
 * The app paints one surface through several attributes. The tab bar, the message box and the
 * tertiary background are all `neutral_73` on Ash, for example. Changing one of them alone would
 * leave the others behind, which is not what a theme author means. So an overridden attribute
 * carries every attribute that has the same color on the base theme, unless the theme sets that
 * one itself.
 *
 * Aliucord's Themer gets the same effect by swapping colors by value. Pure white, pure black and
 * transparent are left out: they are shared by unrelated things such as button labels.
 */
object ThemeLinks {
    private val UNLINKED = setOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0)

    fun baseStyle(base: String) = when (base) {
        ModelUserSettings.THEME_LIGHT -> R.style.AppTheme_Light
        ModelUserSettings.THEME_DARKER -> R.style.AppTheme_Dark_Darker
        ModelUserSettings.THEME_MIDNIGHT -> R.style.AppTheme_Dark_Evil
        ModelUserSettings.THEME_MATERIAL_YOU -> R.style.AppTheme_MaterialYou
        else -> R.style.AppTheme_Dark
    }

    private val cache = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * Every color attribute's value on [base], by name; reflection over a few thousand ids, so kept
     * per base. For display: while a theme is on, it shows through.
     */
    fun baseColors(context: Context, base: String): Map<String, Int> = cache.getOrPut(base) { readBaseColors(context, base) }

    /** Uncached: what the table build uses, from resources it knows to be free of any theme. */
    fun readBaseColors(context: Context, base: String): Map<String, Int> {
        val themed = ContextThemeWrapper(context.applicationContext, baseStyle(base))
        val value = TypedValue()
        return R.attr::class.java.fields.mapNotNull { field ->
            val id = runCatching { field.getInt(null) }.getOrNull() ?: return@mapNotNull null
            if (!themed.theme.resolveAttribute(id, value, true)) return@mapNotNull null
            if (value.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return@mapNotNull null
            field.name to value.data
        }.toMap()
    }

    /** [resolved] with linked attributes added; see the class comment. Earlier overrides win a shared color. */
    fun link(resolved: ThemeResolver.Resolved, baseColors: Map<String, Int>): ThemeResolver.Resolved {
        val byValue = baseColors.entries.groupBy({ it.value }, { it.key })
        val attrs = LinkedHashMap(resolved.attrs)
        resolved.attrs.forEach { (name, color) ->
            val original = baseColors[name]?.takeIf { it !in UNLINKED } ?: return@forEach
            byValue[original].orEmpty().forEach { linked -> attrs.putIfAbsent(linked, color) }
        }
        return resolved.copy(attrs = attrs)
    }
}
