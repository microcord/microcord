package com.discord.rtcconnection

import co.discord.media_engine.NativeCapturerObserver
import co.discord.media_engine.SoundshareAudioSource
import com.discord.native.engine.NativeConnection
import com.discord.native.engine.NativeEngine
import java.io.File
import java.lang.reflect.Modifier
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

    private val expectedSymbols = listOf(
        NativeEngine::class.java,
        NativeConnection::class.java,
        SoundshareAudioSource::class.java,
        NativeCapturerObserver::class.java,
    ).flatMap { owner ->
        owner.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { method -> "Java_${jniMangle(owner.name)}_${jniMangle(method.name)}" }
    }.sorted()

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

    private fun jniMangle(value: String): String = buildString {
        value.forEach { character ->
            append(
                when (character) {
                    '.' -> "_"
                    '_' -> "_1"
                    ';' -> "_2"
                    '[' -> "_3"
                    '$' -> "_00024"
                    else -> character
                },
            )
        }
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
