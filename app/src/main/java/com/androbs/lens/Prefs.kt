package com.androbs.lens

import android.content.Context

object Prefs {
    private const val FILE = "lente_prefs"
    private const val KEY_SOURCE = "source_lang"
    private const val KEY_TARGET = "target_lang"
    private const val KEY_AUTO_TRANSLATE_ON_OPEN = "auto_translate_on_open"

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

    fun autoTranslateOnOpen(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_TRANSLATE_ON_OPEN, true)

    fun setAutoTranslateOnOpen(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_TRANSLATE_ON_OPEN, enabled).apply()
    }
}
