package com.discord.restapi

import com.discord.app.AppLog
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.lang.reflect.Type
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Gson converters that report deserialization failures before rethrowing them.
 *
 * Retrofit surfaces a parse failure as `onError`, which callers reduce to a generic failure flag, so
 * one unparseable field silently empties an entire response with nothing in the log to say why.
 * Gson's message carries the JSON path and offset, which together with the endpoint and target type
 * is enough to identify the offending field.
 */
class LoggingGsonConverterFactory(gson: Gson) : Converter.Factory() {

    private val delegate = GsonConverterFactory.create(gson)

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val converter = delegate.responseBodyConverter(type, annotations, retrofit) ?: return null
        val endpoint = endpointOf(annotations)
        return Converter<ResponseBody, Any> { body ->
            try {
                converter.convert(body)
            } catch (error: JsonParseException) {
                AppLog.e(
                    TAG,
                    "$endpoint -> ${typeName(type)}: ${error.cause?.message ?: error.message}",
                    error,
                    mapOf("endpoint" to endpoint, "type" to typeName(type)),
                )
                throw error
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? =
        delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

    override fun stringConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, String>? = delegate.stringConverter(type, annotations, retrofit)

    private companion object {
        const val TAG = "JsonParse"

        fun typeName(type: Type): String = (type as? Class<*>)?.simpleName ?: type.toString()

        fun endpointOf(annotations: Array<Annotation>): String =
            annotations.firstNotNullOfOrNull {
                when (it) {
                    is GET -> "GET ${it.value}"
                    is POST -> "POST ${it.value}"
                    is PATCH -> "PATCH ${it.value}"
                    is PUT -> "PUT ${it.value}"
                    is DELETE -> "DELETE ${it.value}"
                    is HEAD -> "HEAD ${it.value}"
                    is HTTP -> "${it.method} ${it.path}"
                    else -> null
                }
            } ?: "unknown endpoint"
    }
}
