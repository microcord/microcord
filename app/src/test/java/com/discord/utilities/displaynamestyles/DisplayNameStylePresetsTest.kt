package com.discord.utilities.displaynamestyles

import com.discord.api.user.DisplayNameStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNameStylePresetsTest {
    @Test
    fun emptyColoursFallBackToReactNativesEffectDefaults() {
        assertEquals(
            listOf(0xfe9242, 0x35ed7e, 0xffe047, 0xff4cd2, 0x5865f2),
            DisplayNameStylePresets.defaultColorsFor(DisplayNameStyles.EFFECT_PRISM),
        )
        assertEquals(listOf(0x2aaea6, 0xffc490), DisplayNameStylePresets.defaultColorsFor(DisplayNameStyles.EFFECT_GLOW))
        assertEquals(listOf(0x0fcf86), DisplayNameStylePresets.defaultColorsFor(DisplayNameStyles.EFFECT_POP))
        assertNull(DisplayNameStylePresets.defaultColorsFor(DisplayNameStyles.EFFECT_SOLID))
    }
}
