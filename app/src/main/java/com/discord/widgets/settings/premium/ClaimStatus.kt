package com.discord.widgets.settings.premium

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.utcdatetime.UtcDateTime

sealed class ClaimStatus : Parcelable {
    data class Claimed(
        val promoId: Long,
        val code: String,
        val body: String,
        val link: String,
        val redeemBy: UtcDateTime,
    ) : ClaimStatus() {
        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(promoId)
            parcel.writeString(code)
            parcel.writeString(body)
            parcel.writeString(link)
            parcel.writeLong(redeemBy.dateTimeMillis)
        }

        override fun toString() =
            "Claimed(promoId=$promoId, code=<redacted>, body=$body, link=$link, redeemBy=$redeemBy)"

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<Claimed> {
                override fun createFromParcel(parcel: Parcel) = Claimed(
                    parcel.readLong(),
                    parcel.readString()!!,
                    parcel.readString()!!,
                    parcel.readString()!!,
                    UtcDateTime(parcel.readLong()),
                )

                override fun newArray(size: Int): Array<Claimed?> = arrayOfNulls(size)
            }
        }
    }

    data class Unclaimed(
        val promoId: Long,
        val claimBy: UtcDateTime,
    ) : ClaimStatus() {
        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(promoId)
            parcel.writeLong(claimBy.dateTimeMillis)
        }

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<Unclaimed> {
                override fun createFromParcel(parcel: Parcel) =
                    Unclaimed(parcel.readLong(), UtcDateTime(parcel.readLong()))

                override fun newArray(size: Int): Array<Unclaimed?> = arrayOfNulls(size)
            }
        }
    }
}
