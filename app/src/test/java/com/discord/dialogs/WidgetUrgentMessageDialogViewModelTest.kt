package com.discord.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable
import rx.observers.TestSubscriber

class WidgetUrgentMessageDialogViewModelTest {
    @Test
    fun `acknowledging opens the system dm and clears only the urgent flag`() {
        var openedSystemDm = false
        var patchedFlags: Int? = null
        val viewModel = WidgetUrgentMessageDialogViewModel(
            userFlagsObservable = Observable.just<Int?>(8193),
            updateFlags = { flags ->
                patchedFlags = flags
                Observable.just(Unit)
            },
        )
        val events = TestSubscriber<WidgetUrgentMessageDialogViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        viewModel.acknowledge { openedSystemDm = true }

        assertTrue(openedSystemDm)
        assertEquals(1, patchedFlags)
        assertTrue(viewModel.requireViewState().isBusy)
        events.assertValue(WidgetUrgentMessageDialogViewModel.Event.Dismiss)
    }

    @Test
    fun `missing flags still opens the system dm without sending an empty patch`() {
        var openedSystemDm = false
        var patched = false
        val viewModel = WidgetUrgentMessageDialogViewModel(
            userFlagsObservable = Observable.just<Int?>(null),
            updateFlags = {
                patched = true
                Observable.just(Unit)
            },
        )

        viewModel.acknowledge { openedSystemDm = true }

        assertTrue(openedSystemDm)
        assertFalse(patched)
    }

    @Test
    fun `failed acknowledgement emits the failure event`() {
        val viewModel = WidgetUrgentMessageDialogViewModel(
            userFlagsObservable = Observable.just<Int?>(8192),
            updateFlags = { Observable.error(IllegalStateException("failed")) },
        )
        val events = TestSubscriber<WidgetUrgentMessageDialogViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        viewModel.acknowledge { }

        events.assertValue(WidgetUrgentMessageDialogViewModel.Event.Failed)
        assertTrue(viewModel.requireViewState().isBusy)
    }
}
