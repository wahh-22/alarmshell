package dev.wahh.alarmshell

import android.content.Context
import android.graphics.Typeface

/** API 26+. Shared HUD face for every label, button and field caption. */
internal object HudFont {
    private val fonts = mutableMapOf<Boolean, Typeface>()

    fun typeface(
        context: Context,
        bold: Boolean = false,
    ): Typeface =
        fonts.getOrPut(bold) {
            Typeface
                .Builder(context.assets, "fonts/KodeMono[ROND,wdth,wght].ttf")
                .setFontVariationSettings("'wght' ${if (bold) 700 else 400}, 'wdth' 100, 'ROND' 0")
                .build()
        }
}
