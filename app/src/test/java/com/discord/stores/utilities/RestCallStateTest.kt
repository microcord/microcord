package com.discord.stores.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable
import rx.schedulers.Schedulers
import java.io.IOException

class RestCallStateTest {
    @Test
    fun invokeYieldsResponseOnlyForSuccess() {
        assertNull(Default())
        assertNull(Loading())
        assertNull(Failure<String>(restApiError())())
        assertEquals("payload", Success("payload")())
    }

    @Test
    fun successfulRequestTransitionsLoadingThenSuccess() {
        val states = mutableListOf<RestCallState<String>>()

        Observable.just("payload").executeRequest(Schedulers.immediate()) { states += it }

        assertEquals(listOf<RestCallState<String>>(Loading, Success("payload")), states)
    }

    @Test
    fun failedRequestTransitionsLoadingThenFailure() {
        val states = mutableListOf<RestCallState<String>>()

        Observable.error<String>(IOException("offline")).executeRequest(Schedulers.immediate()) { states += it }

        assertEquals(2, states.size)
        assertEquals(Loading, states[0])
        val failure = states[1]
        assertTrue(failure is Failure)
        assertEquals("offline", (failure as Failure).error.message)
    }

    @Test
    fun successValuesCompareByResponse() {
        assertEquals(Success(7), Success(7))
        assertTrue(Success(7) != Success(8))
    }

    private fun restApiError() = com.discord.utilities.error.RestApiError.from(IOException("boom"))
}
