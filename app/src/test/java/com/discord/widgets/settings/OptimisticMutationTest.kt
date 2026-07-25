package com.discord.widgets.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticMutationTest {
    @Test fun `failure rolls back and stale failure cannot overwrite newer state`() {
        val rendered = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
        val mutation = OptimisticMutation(1, rendered::add, errors::add)

        mutation.submit(2) { _, done -> callbacks += done }
        mutation.submit(3) { _, done -> callbacks += done }
        callbacks[0](Result.failure(IllegalStateException("stale")))
        assertEquals(3, mutation.state)
        callbacks[1](Result.failure(IllegalStateException("current")))

        assertEquals(2, mutation.state)
        assertEquals(listOf(2, 3, 2), rendered)
        assertEquals("current", errors.single().message)
    }
}
