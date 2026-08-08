package com.discord.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.discord.utilities.websocket.ZstdStreamDecoder
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZstdStreamDecoderTest {
    @Test
    fun persistentContextDecodesOneGatewayPayloadPerFlush() {
        val sink = ByteArrayOutputStream()
        val compressor = ZstdOutputStreamNoFinalizer(sink)
        val decoder = ZstdStreamDecoder()

        val first = encode(sink, compressor, "{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}")
        val second = encode(sink, compressor, "{\"op\":11,\"d\":null}")

        assertEquals("{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}", decoder.decode(first).single())
        assertEquals("{\"op\":11,\"d\":null}", decoder.decode(second).single())

        compressor.close()
        decoder.close()
    }

    private fun encode(
        sink: ByteArrayOutputStream,
        compressor: ZstdOutputStreamNoFinalizer,
        json: String,
    ): ByteArray {
        val start = sink.size()
        compressor.write(json.toByteArray())
        compressor.flush()
        return sink.toByteArray().copyOfRange(start, sink.size())
    }
}
