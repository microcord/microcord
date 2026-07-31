package com.discord.widgets.voice

import com.discord.R
import com.discord.widgets.voice.controls.VoiceControlsOutputSelectorState
import com.discord.widgets.voice.fullscreen.WidgetGuildCallOnboardingSheet
import com.discord.widgets.voice.fullscreen.WidgetStartCallSheet
import com.discord.widgets.voice.model.CameraState
import com.discord.widgets.voice.stream.StreamNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceExactPathContractTest {
    @Test
    fun enumDeclarationOrderAndOutputStateMatch12621() {
        assertEquals(
            listOf("CAMERA_ON", "CAMERA_OFF", "CAMERA_DISABLED"),
            CameraState.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "SPEAKER_ON",
                "SPEAKER_OFF",
                "BLUETOOTH_ON_AND_MORE",
                "SPEAKER_ON_AND_MORE",
                "SPEAKER_OFF_AND_MORE",
            ),
            VoiceControlsOutputSelectorState.entries.map { it.name },
        )
        assertEquals(
            R.drawable.ic_sound_quiet_24dp,
            VoiceControlsOutputSelectorState.SPEAKER_OFF.audioOutputIconRes,
        )
        assertEquals(
            R.drawable.ic_sound_quiet_24dp,
            VoiceControlsOutputSelectorState.SPEAKER_OFF_AND_MORE.audioOutputIconRes,
        )
    }

    @Test
    fun reconstructedVoiceTypesOccupyTheReferencePaths() {
        assertEquals(
            "com.discord.widgets.voice.fullscreen.WidgetStartCallSheet",
            WidgetStartCallSheet::class.java.name,
        )
        assertEquals(
            "com.discord.widgets.voice.fullscreen.WidgetGuildCallOnboardingSheet",
            WidgetGuildCallOnboardingSheet::class.java.name,
        )
        assertEquals(
            "com.discord.widgets.voice.call.PrivateCallLaunchUtilsKt",
            Class.forName("com.discord.widgets.voice.call.PrivateCallLaunchUtilsKt").name,
        )
        assertEquals("com.discord.widgets.voice.VoiceUtils", VoiceUtils::class.java.name)
        assertEquals(
            "com.discord.widgets.voice.stream.StreamNavigator",
            StreamNavigator::class.java.name,
        )
        assertNotNull(
            StreamNavigator::class.java.getDeclaredMethod(
                "handleActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                android.content.Intent::class.java,
                kotlin.jvm.functions.Function1::class.java,
            ),
        )
    }
}
