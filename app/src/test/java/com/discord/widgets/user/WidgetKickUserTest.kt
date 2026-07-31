package com.discord.widgets.user

import org.junit.Assert.assertEquals
import org.junit.Test
import rx.Observable

class WidgetKickUserTest {
    @Test
    fun `no-content completion emits success`() {
        assertEquals(listOf(Unit), completeNoContent(Observable.empty()).toList().toBlocking().single())
    }
}
