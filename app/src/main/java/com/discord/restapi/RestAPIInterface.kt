package com.discord.restapi

import com.discord.api.channel.Channel
import retrofit2.http.GET
import retrofit2.http.Path
import rx.Observable

interface RestAPIInterface {
    @GET("channels/{channelId}")
    fun getChannel(@Path("channelId") channelId: Long): Observable<Channel>
}
