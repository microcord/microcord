package com.discord.widgets.mobile_reports

import com.discord.stores.StoreStream

class ReportsFeatureFlag {
    companion object {
        @JvmStatic
        fun isEnabled(): Boolean = StoreStream.getExperiments()
            .getUserExperiment("2021-03_mobile_can_view_in_app_reports", true)
            ?.bucket == 1
    }
}
