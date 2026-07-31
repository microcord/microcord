package com.discord.utilities.premium

import com.discord.dialogs.WidgetBlockRussianPurchasesDialog
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream

object BlockRussianPurchasesUtils {
    private const val EXPERIMENT = "2022-03_block_russian_purchases"
    const val NOTICE_NAME = "Block Russian Purchases Dialog"

    private val isBlockedFromPurchases by lazy {
        isBlockedBucket(StoreStream.getExperiments().getUserExperiment(EXPERIMENT, true)?.bucket)
    }

    fun showDialog(): Boolean {
        if (!isBlockedFromPurchases) return false
        StoreStream.getNotices().requestToShow(
            StoreNotices.Notice(
                name = NOTICE_NAME,
                validScreens = null,
                delayPeriodMs = 0L,
                autoMarkSeen = true,
                sinceLastPeriodMs = 0L,
            ) { activity ->
                WidgetBlockRussianPurchasesDialog.show(activity.supportFragmentManager)
                true
            },
        )
        return true
    }

    internal fun isBlockedBucket(bucket: Int?): Boolean = bucket == 1
}
