package com.discord.models.domain

/** Response body of the endpoints that answer with nothing but a `url`. */
open class ModelUrl(open val url: String?) {

    override fun equals(other: Any?): Boolean = other is ModelUrl && other.canEqual(this) && url == other.url

    override fun hashCode(): Int = url?.hashCode() ?: 0

    override fun toString(): String = "ModelUrl(url=$url)"

    protected open fun canEqual(other: Any?): Boolean = other is ModelUrl
}
