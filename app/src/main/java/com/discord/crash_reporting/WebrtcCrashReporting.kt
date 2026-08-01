package com.discord.crash_reporting

import com.discord.app.AppLog

@Suppress("unused")
object WebrtcCrashReporting {
    @JvmStatic
    fun reportWebrtcException(throwable: Throwable): String {
        AppLog.e("WebRTC", "Native WebRTC exception", throwable)
        return throwable.javaClass.name
    }
}
