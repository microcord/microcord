package com.discord.widgets.playstation

import com.discord.app.AppBottomSheet
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaystationExactPathContractsTest {
    @Test
    fun `126 21 declarations occupy their exact paths`() {
        val root = sequenceOf(
            File("app/src/main/java/com/discord/widgets/playstation"),
            File("src/main/java/com/discord/widgets/playstation"),
        ).first(File::isDirectory)

        assertEquals(
            listOf(
                "PlaystationExperimentUtils.kt",
                "PlaystationUpsellManager.kt",
                "WidgetPlaystationIntegrationUpsellBottomSheet.kt",
            ),
            root.listFiles().orEmpty().filter(File::isFile).map(File::getName).sorted(),
        )
        assertEquals(
            "com.discord.widgets.playstation.PlaystationExperimentUtilsKt",
            Class.forName("com.discord.widgets.playstation.PlaystationExperimentUtilsKt").name,
        )
        assertTrue(AppBottomSheet::class.java.isAssignableFrom(WidgetPlaystationIntegrationUpsellBottomSheet::class.java))
    }

    @Test
    fun `method surface matches 126 21`() {
        assertEquals(
            listOf(
                "canSeePlaystationAccountIntegration:public",
                "canSeePlaystationUpsells:public",
                "getExperimentBucket:private",
            ),
            Class.forName("com.discord.widgets.playstation.PlaystationExperimentUtilsKt")
                .declaredMethods
                .map { "${it.name}:${if (Modifier.isPrivate(it.modifiers)) "private" else "public"}" }
                .sorted(),
        )
        assertEquals(
            listOf("canShow", "hasBeenShown", "isAccountOldEnough", "isUserMissingPlaystationAccountIntegration", "setHasBeenShown"),
            PlaystationUpsellManager::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name }
                .sorted(),
        )
    }
}
