package com.discord.utilities.analytics

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import rx.Observable
import rx.schedulers.Schedulers

object AdvertisingIdProvider {
    fun initialize(
        context: Context,
        loader: (Context) -> AdvertisingIdClient.Info = AdvertisingIdClient::getAdvertisingIdInfo,
    ) {
        Observable.fromCallable { loader(context.applicationContext) }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { info -> publish(info.id, info.isLimitAdTrackingEnabled) },
                { /* Play Services is optional; leave the property absent. */ },
            )
    }

    internal fun publish(id: String?, limitAdTracking: Boolean): Boolean {
        if (limitAdTracking || id.isNullOrBlank() || id == ZERO_ADVERTISING_ID) return false
        AnalyticSuperProperties.setAdvertiserId(id)
        return true
    }

    private const val ZERO_ADVERTISING_ID = "00000000-0000-0000-0000-000000000000"
}
