package com.discord.utilities.persister

/**
 * Generates `<Class>CacheFormat`, a field-by-field Kryo stream codec for this class, at compile
 * time (`:cache-codegen`). See that processor for the encoding and what is written through Kryo.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CachedFormat
