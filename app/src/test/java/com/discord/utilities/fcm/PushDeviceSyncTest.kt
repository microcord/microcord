package com.discord.utilities.fcm

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PushDeviceSyncTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    /** Only a 401 means the stored token is spent; anything else must not sign the account out. */
    @Test
    fun onlyUnauthorizedRejectsTheToken() {
        assertTrue(PushDeviceSync.isAuthFailure(http(401)))
        assertFalse(PushDeviceSync.isAuthFailure(http(403)))
        assertFalse(PushDeviceSync.isAuthFailure(http(500)))
        assertFalse(PushDeviceSync.isAuthFailure(IOException()))
    }
}
