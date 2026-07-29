package com.discord.widgets.channels.threads.browser

import com.discord.api.permission.Permission
import com.discord.stores.ArchivedThreadsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBrowserViewModelsTest {
    @Test fun archivedViewModeSelectsTheReferenceEndpoint() {
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PUBLIC_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PublicThreads,
                true,
            ).threadListingType,
        )
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                false,
            ).threadListingType,
        )
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PRIVATE_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                true,
            ).threadListingType,
        )
    }

    @Test fun eitherThreadCreationPermissionEnablesCreate() {
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(null))
        assertTrue(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS))
        assertTrue(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PRIVATE_THREADS))
    }
}
