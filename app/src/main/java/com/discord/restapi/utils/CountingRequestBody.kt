package com.discord.restapi.utils

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import rx.Observable
import rx.subjects.BehaviorSubject

class CountingRequestBody(private val delegate: RequestBody) : RequestBody() {
    private val bytesWritten = BehaviorSubject.create(0L)

    val estimatedContentLength: Long = delegate.contentLength()

    val bytesWrittenObservable: Observable<Long>
        get() = bytesWritten.distinctUntilChanged().onBackpressureLatest()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = estimatedContentLength

    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            private var total = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                total += byteCount
                bytesWritten.onNext(total)
            }
        }.buffer()
        delegate.writeTo(countingSink)
        countingSink.flush()
    }
}
