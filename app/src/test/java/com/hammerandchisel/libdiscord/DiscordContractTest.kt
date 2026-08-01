package com.hammerandchisel.libdiscord

import co.discord.media_engine.MediaType
import co.discord.media_engine.StreamParameters
import com.google.gson.Gson
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordContractTest {
    @Test
    fun legacyCallerSurfaceRoutesThroughModernEngine() {
        assertTrue(Discord::class.java.declaredMethods.none { Modifier.isNative(it.modifiers) })
        assertEquals(1, Discord.LOGLEVEL_DEBUG)
        assertEquals(2, Discord.LOGLEVEL_DEFAULT)
        Discord::class.java.getDeclaredMethod(
            "connectToServer",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Array<StreamParameters>::class.java,
            Discord.ConnectToServerCallback::class.java,
        )
        Discord::class.java.getDeclaredMethod("dispose")
        Discord::class.java.getDeclaredMethod("setVideoOutputSink", String::class.java, Discord.VideoFrameCallback::class.java)
        assertFalse(Modifier.isNative(Discord::class.java.getDeclaredMethod("crash").modifiers))
    }

    @Test
    fun streamParametersUseModernJsonShape() {
        val old = StreamParameters(MediaType.Video, "100", 42, 43, true, 1_000_000, 100, 921_600)
        val converted = Discord.NativeStreamParameters.from(old)
        val json = Gson().toJson(converted)
        assertTrue(json.contains("\"type\":\"video\""))
        assertTrue(json.contains("\"rtxSsrc\":43"))
        assertEquals(converted, Gson().fromJson(json, Discord.NativeStreamParameters::class.java))
        assertEquals(
            Discord.ConnectionInfo(true, "udp", "127.0.0.1", 5000),
            Discord.ConnectionInfo(true, "udp", "127.0.0.1", 5000),
        )
    }
}
