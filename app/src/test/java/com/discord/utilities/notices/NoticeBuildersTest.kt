package com.discord.utilities.notices

import com.discord.stores.StoreNotices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the one wiring step that fails silently.
 *
 * `StoreNotices` polls on a background scheduler and a `Dialog`-backed notice whose presenter is
 * missing simply reports that it did not show — it stays queued forever and nothing is logged. The
 * only visible symptom is a modal that never appears, which is indistinguishable from the cooldown
 * not having elapsed.
 */
class NoticeBuildersTest {

    @Before fun setUp() { StoreNotices.dialogPresenter = null }

    @After fun tearDown() { StoreNotices.dialogPresenter = null }

    @Test fun installClosesTheDialogPresenterSeam() {
        assertNull("A fresh process has no presenter", StoreNotices.dialogPresenter)

        NoticeBuilders.install()

        assertNotNull(
            "NoticeBuilders.install() must run during app start, or every Dialog notice is inert",
            StoreNotices.dialogPresenter,
        )
    }

    @Test fun installIsIdempotent() {
        NoticeBuilders.install()
        val first = StoreNotices.dialogPresenter
        NoticeBuilders.install()

        assertNotNull(first)
        assertNotNull(StoreNotices.dialogPresenter)
    }

    /**
     * The queued notice's name has to be the enum's own name: it is what travels through the
     * dialog's arguments bundle and comes back to `markDialogSeen`, which resolves it with
     * `Dialog.Type.valueOf`. A different name would leave the notice queued after it was shown.
     */
    @Test fun everyDialogTypeQueuesUnderItsOwnName() {
        StoreNotices.Dialog.Type.values().forEach { type ->
            val notice = type.buildPassiveNotice()

            assertEquals(type.name, notice.name)
            assertEquals(StoreNotices.PRIORITY_PASSIVE_NOTICE, notice.priority)
            assertFalse("Passive dialogs are not persisted unless a caller asks", notice.persist)
        }
    }
}
