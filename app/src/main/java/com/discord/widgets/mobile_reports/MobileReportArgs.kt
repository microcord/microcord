package com.discord.widgets.mobile_reports

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.report.ReportType

sealed class MobileReportArgs(
    val reportType: ReportType,
    open val channelId: Long,
) : Parcelable {

    data class Message(val messageId: Long, override val channelId: Long) :
        MobileReportArgs(ReportType.Message, channelId) {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(messageId)
            parcel.writeLong(channelId)
        }

        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<Message> {
            override fun createFromParcel(parcel: Parcel) = Message(parcel.readLong(), parcel.readLong())
            override fun newArray(size: Int) = arrayOfNulls<Message>(size)
        }
    }

    data class StageChannel(override val channelId: Long) :
        MobileReportArgs(ReportType.StageChannel, channelId) {
        override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeLong(channelId)
        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<StageChannel> {
            override fun createFromParcel(parcel: Parcel) = StageChannel(parcel.readLong())
            override fun newArray(size: Int) = arrayOfNulls<StageChannel>(size)
        }
    }

    data class DirectoryServer(
        val guildId: Long,
        val hubId: Long,
        override val channelId: Long,
    ) : MobileReportArgs(ReportType.DirectoryServer, channelId) {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(guildId)
            parcel.writeLong(hubId)
            parcel.writeLong(channelId)
        }

        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<DirectoryServer> {
            override fun createFromParcel(parcel: Parcel) =
                DirectoryServer(parcel.readLong(), parcel.readLong(), parcel.readLong())

            override fun newArray(size: Int) = arrayOfNulls<DirectoryServer>(size)
        }
    }

    data class GuildScheduledEvent(val guildId: Long, val eventId: Long) :
        MobileReportArgs(ReportType.GuildScheduledEvent, -1L) {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(guildId)
            parcel.writeLong(eventId)
        }

        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<GuildScheduledEvent> {
            override fun createFromParcel(parcel: Parcel) =
                GuildScheduledEvent(parcel.readLong(), parcel.readLong())

            override fun newArray(size: Int) = arrayOfNulls<GuildScheduledEvent>(size)
        }
    }
}
