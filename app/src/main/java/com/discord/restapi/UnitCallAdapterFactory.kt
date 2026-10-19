package com.discord.restapi

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Retrofit
import rx.Observable

/**
 * Makes `Observable<Unit>` endpoints emit [Unit] for a body-less reply.
 *
 * Retrofit hands a 204/205 to the call adapter as a null body without asking the converter, and
 * RxJava 1 passes that null on. Kotlin callers of `Observable<Unit>` then fail their non-null
 * `onNext` parameter check, so a request that succeeded was reported as an error (removing a
 * device from Devices did exactly this).
 */
internal class UnitCallAdapterFactory : CallAdapter.Factory() {
    override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): CallAdapter<*, *>? {
        if (getRawType(returnType) != Observable::class.java) return null
        val parameterized = returnType as? ParameterizedType ?: return null
        if (getParameterUpperBound(0, parameterized) != Unit::class.java) return null
        @Suppress("UNCHECKED_CAST")
        val delegate = retrofit.nextCallAdapter(this, returnType, annotations) as CallAdapter<Unit, Observable<Unit?>>
        return object : CallAdapter<Unit, Observable<Unit>> {
            override fun responseType(): Type = delegate.responseType()
            override fun adapt(call: Call<Unit>): Observable<Unit> = delegate.adapt(call).map { Unit }
        }
    }
}
