package com.discord.rtcconnection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks every `external` method the app declares against the symbol table of the packaged
 * `libdiscord.so`.
 *
 * `libdiscord.so` is an immutable binary contract and JNI binds by *name*: a descriptor that is one
 * character wrong compiles, links, and then throws `UnsatisfiedLinkError` the first time a user
 * joins a call — which is exactly the moment nobody is running a debugger. A descriptor that was
 * inferred rather than read off the binary is indistinguishable from a correct one until then, so
 * this test reads the binary.
 *
 * The check is a byte search of the whole file rather than a real ELF parse: the exported names live
 * null-terminated in `.dynstr`, and a null-terminated hit is unambiguous for names this distinctive.
 * All four ABIs are checked, because they are separately built artifacts.
 */
class NativeSymbolContractTest {

    /**
     * The one deliberate exception.
     *
     * `Discord.setKeepAliveChannel(boolean)` is declared `native` in the reference APK too, and no
     * `libdiscord.so` in the release exports it — verified across all four ABIs. It is kept for
     * structural parity with the reference class and must never be called.
     */
    private val declaredButNotExported = setOf(
        "Java_com_hammerandchisel_libdiscord_Discord_setKeepAliveChannel",
    )

    private val expectedSymbols: List<String> = listOf(
        // com.hammerandchisel.libdiscord.Discord
        "nativeConstructor", "setLocalVoiceLevelChangedCallbackNative", "connectToServer", "crash",
        "dispose", "enableBuiltInAEC", "getAudioInputDevices", "getAudioOutputDevices",
        "getAudioSubsystem", "getRankedRtcRegions", "getSupportedVideoCodecs", "getVideoInputDevices",
        "setAudioInputEnabled", "setAutomaticGainControl", "setEchoCancellation", "setMicVolume",
        "setNoAudioInputCallback", "setNoAudioInputThreshold", "setNoiseCancellation",
        "setNoiseSuppression", "setPlayoutDevice", "setRecordingDevice", "setSpeakerVolume",
        "setVideoInputDevice", "setVideoOutputSink", "signalVideoOutputSinkReady",
    ).map { "Java_com_hammerandchisel_libdiscord_Discord_$it" } + listOf(
        // co.discord.media_engine.Connection
        "dispose", "simulatePacketLoss", "setQoS", "setEncryptionSettings", "getEncryptionModes",
        "connectUser", "disconnectUser", "setUserPlayoutVolume", "muteUser", "disableVideo",
        "deafenLocalUser", "muteLocalUser", "setMinimumPlayoutDelay", "enableForwardErrorCorrection",
        "enableDiscontinuousTransmission", "setExpectedPacketLossRate", "setCodecs",
        "setOnVideoCallback", "setVideoBroadcast", "startScreenshareBroadcast",
        "stopScreenshareBroadcast", "setAudioInputMode", "setVADLeadingFramesToBuffer",
        "setVADTrailingFramesToSend", "setVADTriggerThreshold", "setVADAutoThreshold",
        "setVADUseKrisp", "setPTTActive", "setEncodingQuality", "getStatsNative",
    ).map { "Java_co_discord_media_1engine_Connection_$it" } + listOf(
        // co.discord.media_engine.SoundshareAudioSource
        "nativeSetSampleFormat", "nativeCacheDirectBufferAddress", "nativeDataIsRecorded",
        "nativeCreateInstance", "nativeDestroyInstance",
    ).map { "Java_co_discord_media_1engine_SoundshareAudioSource_$it" } + listOf(
        // co.discord.media_engine.NativeCapturerObserver
        "Java_co_discord_media_1engine_NativeCapturerObserver_nativeOnFrame",
    )

    @Test
    fun `every declared descriptor is exported by libdiscord for every abi`() {
        val jniLibs = findJniLibs()
        assumeTrue("jniLibs not found from ${File("").absolutePath}", jniLibs != null)

        ABIS.forEach { abi ->
            val library = File(jniLibs, "$abi/libdiscord.so")
            assertTrue("missing $abi/libdiscord.so", library.isFile)
            val bytes = library.readBytes()
            val missing = expectedSymbols.filterNot { bytes.containsSymbol(it) }
            assertEquals("$abi is missing JNI descriptors", emptyList<String>(), missing)
        }
    }

    @Test
    fun `setKeepAliveChannel is absent from every abi and must not be called`() {
        val jniLibs = findJniLibs()
        assumeTrue("jniLibs not found", jniLibs != null)

        ABIS.forEach { abi ->
            val bytes = File(jniLibs, "$abi/libdiscord.so").readBytes()
            declaredButNotExported.forEach { symbol ->
                assertTrue(
                    "$symbol unexpectedly appeared in $abi; the declaration can now be used",
                    !bytes.containsSymbol(symbol),
                )
            }
        }
    }

    /**
     * `RLottieDrawable`'s companion binds against `libdsti.so`, not `libdiscord.so`.
     *
     * Included here because the two libraries are easy to confuse and one of these descriptors was
     * originally inferred rather than read from the binary.
     */
    @Test
    fun `rlottie descriptors are exported by libdsti`() {
        val jniLibs = findJniLibs()
        assumeTrue("jniLibs not found", jniLibs != null)

        val expected = listOf(
            "create", "createCache", "createWithJson", "destroy", "getFrame", "replaceColors",
            "setLayerColor",
        ).map { "Java_com_discord_rlottie_RLottieDrawable_00024Companion_$it" }

        ABIS.forEach { abi ->
            val library = File(jniLibs, "$abi/libdsti.so")
            assertTrue("missing $abi/libdsti.so", library.isFile)
            val bytes = library.readBytes()
            assertEquals(
                "$abi is missing RLottie descriptors",
                emptyList<String>(),
                expected.filterNot { bytes.containsSymbol(it) },
            )
        }
    }

    /** A null-terminated ASCII match, so `foo` cannot satisfy a search for `fooBar`. */
    private fun ByteArray.containsSymbol(symbol: String): Boolean {
        val needle = (symbol.toByteArray(Charsets.US_ASCII) + 0)
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    /** Unit tests run from the module directory, but tolerate being run from the repository root. */
    private fun findJniLibs(): File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .take(4)
        .map { File(it, "app/src/main/jniLibs") to File(it, "src/main/jniLibs") }
        .flatMap { (fromRoot, fromModule) -> sequenceOf(fromModule, fromRoot) }
        .firstOrNull { it.isDirectory }

    private companion object {
        private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
}
