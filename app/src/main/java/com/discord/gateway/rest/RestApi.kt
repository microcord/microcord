package com.discord.gateway.rest

import com.discord.models.domain.ModelGateway
import retrofit2.http.GET
import rx.Observable

internal interface RestApi {
    @GET("gateway")
    fun getGateway(): Observable<ModelGateway>
}
