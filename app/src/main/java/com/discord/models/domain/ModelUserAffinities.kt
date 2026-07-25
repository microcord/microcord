package com.discord.models.domain

data class ModelUserAffinity(val userId: Long, val affinity: Float)

data class ModelUserAffinities(
    val userAffinities: List<ModelUserAffinity>,
    val inverseUserAffinities: List<ModelUserAffinity>,
)
