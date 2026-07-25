package com.discord.models.domain

data class ModelUserNote(val noteUserId: Long, val note: String?) {
    data class Update(val id: Long, val note: String)
}
