// SPDX-License-Identifier: GPL-3.0-only
package ru.klaviatura.bg

import android.content.Context
import android.content.Intent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/** «Сбросить тему»: default theme style/colors/borders, no background images, no blur/dim */
object ThemeReset {
    private val KEYS = listOf(Settings.PREF_THEME_STYLE, Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT,
        Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT, BgFx.PREF_BLUR, BgFx.PREF_DIM)
    private val PREFIXES = listOf(Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX)

    fun reset(ctx: Context) {
        val prefs = ctx.prefs()
        val e = prefs.edit()
        KEYS.forEach { e.remove(it) }
        prefs.all.keys.filter { k -> PREFIXES.any { k.startsWith(it) } }.forEach { e.remove(it) }
        e.apply()
        for (night in listOf(false, true)) for (land in listOf(false, true))
            Settings.getCustomBackgroundFile(ctx, night, land).delete()
        Settings.clearCachedBackgroundImages()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        ctx.sendBroadcast(Intent(AiBackgroundActivity.ACTION_BG_CHANGED).setPackage(ctx.packageName))
    }
}
