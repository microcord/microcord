package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ui

/**
 * Sets the user's per-guild nickname inside a hub — hubs ask for a real name on first entry.
 *
 * The initial state is read once from the stores rather than observed: the screen is transient and the
 * field is user-owned, so re-seeding it from a store update mid-edit would fight the keyboard. That
 * matches the reference.
 */
class WidgetHubAddNameViewModel(
    private val guildId: Long,
    guildStore: StoreGuilds = StoreStream.getGuilds(),
    userStore: StoreUser = StoreStream.getUsers(),
    private val restAPI: RestAPIInterface = RestAPI.api,
) : AppViewModel<AddNameState>(AddNameState()) {

    init {
        val guild = guildStore.getGuild(guildId)
        val nickname = guildStore.getMember(guildId, userStore.getMe().id)?.nick.orEmpty()
        updateViewState(AddNameState(nickname = nickname, guild = guild))
    }

    fun setName(name: String) {
        viewState?.let { updateViewState(it.copy(nickname = name)) }
        restAPI.changeGuildNickname(guildId, RestAPIParams.Nick(name))
            .ui(this)
            .executeRequest { async ->
                viewState?.let { updateViewState(it.copy(changeNicknameAsync = async)) }
            }
    }
}
