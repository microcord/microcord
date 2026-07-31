@file:JvmName("StockGuildTemplateKt")

package com.discord.widgets.guilds.create

import com.discord.restapi.RestAPIParams

private const val SYSTEM_CHANNEL_ID = 11L

internal fun createCategorySection(
    categoryName: String,
    categoryId: Long,
    channelType: Int,
    vararg channels: ChannelTemplate,
): List<RestAPIParams.CreateGuildChannel> = listOf(
    RestAPIParams.CreateGuildChannel(4, categoryId, categoryName, null, null, null),
) + channels.map { channel ->
    RestAPIParams.CreateGuildChannel(channelType, channel.id, channel.name, categoryId, null, null)
}
