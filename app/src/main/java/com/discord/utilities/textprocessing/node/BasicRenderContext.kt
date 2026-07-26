package com.discord.utilities.textprocessing.node

import android.content.Context

/** Smallest render context every Discord markdown node needs: somewhere to resolve resources. */
interface BasicRenderContext {
    val context: Context
}
