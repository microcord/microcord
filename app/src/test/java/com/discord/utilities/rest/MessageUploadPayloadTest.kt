package com.discord.utilities.rest

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUploadPayloadTest {
    @Test fun ordersPayloadBeforeNumberedFiles() {
        val files = (0..1).map { index ->
            MultipartBody.Part.createFormData(
                "files[$index]",
                "file$index.txt",
                "content$index".toRequestBody("text/plain".toMediaType()),
            )
        }
        val multipart = buildMultipartBody("{\"content\":\"hello\"}", files)

        assertEquals(3, multipart.parts.size)
        assertTrue(multipart.parts[0].headers.toString().contains("name=\"payload_json\""))
        assertTrue(multipart.parts[1].headers.toString().contains("name=\"files[0]\""))
        assertTrue(multipart.parts[2].headers.toString().contains("name=\"files[1]\""))
    }

    @Test fun countingBodyReportsProgress() {
        val body = com.discord.restapi.utils.CountingRequestBody("hello".toRequestBody())
        val progress = mutableListOf<Long>()
        body.bytesWrittenObservable.subscribe(progress::add)
        body.writeTo(Buffer())
        assertEquals(listOf(0L, 5L), progress)
    }
}
