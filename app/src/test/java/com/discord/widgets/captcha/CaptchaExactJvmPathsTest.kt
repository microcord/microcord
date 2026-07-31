package com.discord.widgets.captcha

import com.discord.utilities.error.RestApiError
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaExactJvmPathsTest {
    @Test
    fun `126 21 captcha facades exist at their exact paths`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/java/com/discord/widgets/captcha"),
            File("src/main/java/com/discord/widgets/captcha"),
        ).first(File::isDirectory)

        listOf("WidgetCaptchaKt.kt", "WidgetCaptchaBottomSheetKt.kt").forEach {
            assertTrue(it, File(sourceRoot, it).isFile)
        }
        assertNotNull(Class.forName("com.discord.widgets.captcha.WidgetCaptchaKt"))
        assertNotNull(Class.forName("com.discord.widgets.captcha.WidgetCaptchaBottomSheetKt"))
    }

    @Test
    fun `captcha facade exposes the reference extension signature`() {
        val method = Class.forName("com.discord.widgets.captcha.WidgetCaptchaKt")
            .declaredMethods
            .single { it.name == "isCaptchaError" }

        assertTrue(Modifier.isPublic(method.modifiers))
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        assertEquals(listOf(RestApiError::class.java), method.parameterTypes.toList())
    }

    @Test
    fun `bottom sheet facade retains the private reference argument constant`() {
        val field = Class.forName("com.discord.widgets.captcha.WidgetCaptchaBottomSheetKt")
            .getDeclaredField("ARG_CAPTCHA_ERROR_BODY")

        assertTrue(Modifier.isPrivate(field.modifiers))
        assertTrue(Modifier.isStatic(field.modifiers))
        field.isAccessible = true
        assertEquals("INTENT_EXTRA_CAPTCHA_ERROR_BODY", field.get(null))
    }
}
