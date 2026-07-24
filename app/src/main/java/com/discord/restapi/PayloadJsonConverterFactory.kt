package com.discord.restapi

import com.google.gson.Gson
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import okhttp3.RequestBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PayloadJsonConverterFactory(gson: Gson) : Converter.Factory() {
    private val gsonFactory = GsonConverterFactory.create(gson)

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        if (type !is ParameterizedType || type.rawType != PayloadJSON::class.java) return null
        @Suppress("UNCHECKED_CAST")
        val delegate = gsonFactory.requestBodyConverter(
            type.actualTypeArguments[0],
            parameterAnnotations,
            methodAnnotations,
            retrofit,
        ) as Converter<Any, RequestBody>
        return Converter<PayloadJSON<Any>, RequestBody> { delegate.convert(it.data) }
    }
}
