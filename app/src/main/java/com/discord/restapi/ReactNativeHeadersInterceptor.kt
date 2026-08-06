package com.discord.restapi

import okhttp3.Interceptor

/** Headers added by the current React Native client's `prepareRequest`. */
object ReactNativeHeadersInterceptor : Interceptor by ClientMetadataInterceptor
