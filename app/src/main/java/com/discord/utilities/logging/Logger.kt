package com.discord.utilities.logging

import com.discord.app.AppLog

open class Logger(val defaultTag: String) {
    open fun v(tag: String, message: String, throwable: Throwable? = null) =
        AppLog.v(tag, message, throwable)

    open fun v(message: String, throwable: Throwable? = null) =
        v(defaultTag, message, throwable)

    open fun d(tag: String, message: String, throwable: Throwable? = null) =
        AppLog.d(tag, message, throwable)

    open fun d(message: String, throwable: Throwable? = null) =
        d(defaultTag, message, throwable)

    open fun i(tag: String, message: String, throwable: Throwable? = null) =
        AppLog.i(tag, message, throwable)

    fun i(message: String) = i(message, null)

    open fun i(message: String, throwable: Throwable?) =
        i(defaultTag, message, throwable)

    open fun w(tag: String, message: String, throwable: Throwable? = null) =
        AppLog.w(tag, message, throwable)

    open fun w(message: String, throwable: Throwable? = null) =
        w(defaultTag, message, throwable)

    open fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String>? = null,
    ) = AppLog.e(tag, message, throwable, metadata)

    open fun e(
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String>? = null,
    ) = e(defaultTag, message, throwable, metadata)

    open fun recordBreadcrumb(message: String, category: String) =
        AppLog.recordBreadcrumb(message, category)
}
