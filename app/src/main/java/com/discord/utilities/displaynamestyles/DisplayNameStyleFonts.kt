package com.discord.utilities.displaynamestyles

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.discord.R
import com.discord.api.user.DisplayNameStyles

/**
 * The faces a display-name style can ask for.
 *
 * Discord names these differently in the picker than the type foundries do — Cherry Bomb One is
 * "Sakura", Zilla Slab is "Tempo" — and the resource names follow the picker, because that is what
 * a reader of this code will have seen on screen.
 *
 * Four ids the API defines are deliberately unmapped: Bangers, BioRhyme, Compagnon and Ribes are
 * not shipped on mobile by the reference either, so a name using one draws in the normal face.
 */
object DisplayNameStyleFonts {

    private val cache = HashMap<Int, Typeface?>()

    /** The face for [fontId], or `null` to leave the view's own. */
    fun typefaceFor(context: Context, fontId: Int): Typeface? {
        val fontRes = fontResFor(fontId) ?: return null
        return synchronized(cache) {
            cache.getOrPut(fontId) {
                runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull()
            }
        }
    }

    @FontRes
    private fun fontResFor(fontId: Int): Int? = when (fontId) {
        DisplayNameStyles.FONT_CHERRY_BOMB -> R.font.sakura
        DisplayNameStyles.FONT_CHICLE -> R.font.jellybean
        DisplayNameStyles.FONT_MUSEO_MODERNO -> R.font.modern
        DisplayNameStyles.FONT_NEO_CASTEL -> R.font.medieval
        DisplayNameStyles.FONT_PIXELIFY -> R.font.eightbit
        DisplayNameStyles.FONT_SINISTRE -> R.font.vampyre
        DisplayNameStyles.FONT_ZILLA_SLAB -> R.font.tempo
        DisplayNameStyles.FONT_PLAYPEN_SANS -> R.font.monkeybars
        DisplayNameStyles.FONT_ORBITRON -> R.font.mainframe
        DisplayNameStyles.FONT_NEW_ROCKER -> R.font.headbang
        DisplayNameStyles.FONT_KALAM -> R.font.journal
        else -> null
    }

    /** The picker's order, which is not the id order. */
    val PICKER_ORDER: List<Int> = listOf(
        DisplayNameStyles.FONT_DEFAULT,
        DisplayNameStyles.FONT_ZILLA_SLAB,
        DisplayNameStyles.FONT_CHERRY_BOMB,
        DisplayNameStyles.FONT_CHICLE,
        DisplayNameStyles.FONT_MUSEO_MODERNO,
        DisplayNameStyles.FONT_NEO_CASTEL,
        DisplayNameStyles.FONT_PIXELIFY,
        DisplayNameStyles.FONT_SINISTRE,
        DisplayNameStyles.FONT_PLAYPEN_SANS,
        DisplayNameStyles.FONT_ORBITRON,
        DisplayNameStyles.FONT_NEW_ROCKER,
        DisplayNameStyles.FONT_KALAM,
    )
}
