package com.kami.app

import android.content.Context

/** Application context holder for code that runs outside a component tree. */
object AppContextHolder {
    @Volatile
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun get(): Context = ctx ?: throw IllegalStateException("AppContextHolder not initialized")
}
