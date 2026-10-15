package com.androbs.lens

import android.content.Context

object Prefs {
    private const val FILE = "lente_prefs"
    private const val KEY_SOURCE = "source_lang"
    private const val KEY_TARGET = "target_lang"

    fun sourceLang(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SOURCE, "auto") ?: "auto"

    fun targetLang(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TARGET, "it") ?: "it"

    fun setSourceLang(ctx: Context, tag: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, tag).apply()
    }

    fun setTargetLang(ctx: Context, tag: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TARGET, tag).apply()
    }
}
