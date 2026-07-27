package com.discord.stores

import android.content.Context
import android.content.ContextWrapper
import sun.misc.Unsafe

internal fun testContext(): Context = unsafe.allocateInstance(ContextWrapper::class.java) as Context

private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
    isAccessible = true
    get(null) as Unsafe
}
