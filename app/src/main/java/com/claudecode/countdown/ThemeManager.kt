package com.claudecode.countdown

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

object ThemeManager {

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    const val TAG_CARD = "card"
    const val TAG_ACCENT = "accent"
    const val TAG_SECONDARY = "secondary"
    const val TAG_FAB_SECONDARY = "fab_secondary"
    const val TAG_BUTTON_SECONDARY = "button_secondary"

    private const val PREFS = "theme_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_PRESET = "preset"
    private const val KEY_ACCENT = "accent"

    const val DEFAULT_PRESET = "default"

    data class Scheme(val bg: Int, val surface: Int, val text: Int, val accent: Int)

    data class Preset(val id: String, val name: String, val light: Scheme, val dark: Scheme)

    data class Palette(
        val isDark: Boolean,
        val bg: Int,
        val surface: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val onAccent: Int
    )

    private fun c(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    private fun s(bg: Long, surface: Long, text: Long, accent: Long) =
        Scheme(c(bg), c(surface), c(text), c(accent))

    val PRESETS: List<Preset> = listOf(
        Preset("default", "Стандартная",
            s(0xF6F6FA, 0xE6E6EF, 0x1B1B1F, 0x2962FF),
            s(0x15131A, 0x2B2833, 0xE6E1E9, 0x5B8CFF)),
        Preset("obsidian", "Обсидиан",
            s(0xFFFFFF, 0xF2F3F5, 0x2E3338, 0x705DCF),
            s(0x1E1E1E, 0x2A2A2A, 0xDADADA, 0xA78BFA)),
        Preset("nord", "Нордик",
            s(0xECEFF4, 0xE1E6EE, 0x2E3440, 0x5E81AC),
            s(0x2E3440, 0x3B4252, 0xECEFF4, 0x88C0D0)),
        Preset("dracula", "Дракула",
            s(0xF8F8F2, 0xEAEAF0, 0x282A36, 0x8A5CE0),
            s(0x282A36, 0x343746, 0xF8F8F2, 0xBD93F9)),
        Preset("solarized", "Солярис",
            s(0xFDF6E3, 0xEEE8D5, 0x586E75, 0x268BD2),
            s(0x002B36, 0x073642, 0x93A1A1, 0x2AA198)),
        Preset("gruvbox", "Грувбокс",
            s(0xFBF1C7, 0xEBDBB2, 0x3C3836, 0xB57614),
            s(0x282828, 0x3C3836, 0xEBDBB2, 0xFABD2F)),
        Preset("tokyo", "Токио",
            s(0xD5D6DB, 0xC8C9D0, 0x343B58, 0x34548A),
            s(0x1A1B26, 0x24283B, 0xC0CAF5, 0x7AA2F7)),
        Preset("mocha", "Мокка-Пастель",
            s(0xEFF1F5, 0xE6E9EF, 0x4C4F69, 0x8839EF),
            s(0x1E1E2E, 0x313244, 0xCDD6F4, 0xCBA6F7)),
        Preset("forest", "Лес",
            s(0xF1F8F3, 0xDDEBE1, 0x1B3025, 0x2E7D4F),
            s(0x12201A, 0x1E3229, 0xDDEBE2, 0x5FD38D)),
        Preset("ocean", "Океан",
            s(0xEFF7FC, 0xDCEBF6, 0x0F2A3F, 0x0277BD),
            s(0x0B1C2C, 0x132F45, 0xD8E8F5, 0x4FC3F7)),
        Preset("sunset", "Закат",
            s(0xFFF4F0, 0xF8E0D9, 0x3B211C, 0xD9480F),
            s(0x22141A, 0x3A222B, 0xF7E3E0, 0xFF7A59)),
        Preset("rose", "Роза",
            s(0xFFF1F5, 0xF9DCE6, 0x3A1F2A, 0xD6336C),
            s(0x24151D, 0x3A2130, 0xF8E1EA, 0xFF6B9D)),
        Preset("coffee", "Кофе",
            s(0xF7EFE7, 0xEADCCB, 0x3A2A20, 0x8D5A2B),
            s(0x1F1712, 0x33261E, 0xEBDDD0, 0xD2A679)),
        Preset("mono", "Монохром",
            s(0xFFFFFF, 0xEEEEEE, 0x000000, 0x000000),
            s(0x000000, 0x161616, 0xFFFFFF, 0xE0E0E0))
    )

    val ACCENTS: List<Int> = listOf(
        0x2962FF, 0x7C4DFF, 0xE91E63, 0xF44336, 0xFF9800, 0xFFC107,
        0x4CAF50, 0x009688, 0x00BCD4, 0x03A9F4, 0x795548, 0x607D8B
    ).map { c(it.toLong()) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(context: Context): String = prefs(context).getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun getPresetId(context: Context): String =
        prefs(context).getString(KEY_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET

    /** Selecting a ready-made theme also drops the custom accent, so the theme's own accent shows. */
    fun setPreset(context: Context, id: String) {
        prefs(context).edit().putString(KEY_PRESET, id).remove(KEY_ACCENT).apply()
    }

    fun getAccentOverride(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_ACCENT)) p.getInt(KEY_ACCENT, 0) else null
    }

    fun setAccentOverride(context: Context, color: Int?) {
        val e = prefs(context).edit()
        if (color == null) e.remove(KEY_ACCENT) else e.putInt(KEY_ACCENT, color)
        e.apply()
    }

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (getMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun palette(context: Context): Palette {
        val isDark = when (getMode(context)) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val preset = PRESETS.firstOrNull { it.id == getPresetId(context) } ?: PRESETS.first()
        val scheme = if (isDark) preset.dark else preset.light
        val accent = getAccentOverride(context) ?: scheme.accent
        return Palette(
            isDark = isDark,
            bg = scheme.bg,
            surface = scheme.surface,
            text = scheme.text,
            textSecondary = ColorUtils.blendARGB(scheme.text, scheme.bg, 0.4f),
            accent = accent,
            onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) Color.BLACK else Color.WHITE
        )
    }

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Paints window background, system bars and the whole view tree of the activity. */
    fun apply(activity: Activity) {
        val p = palette(activity)
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !p.isDark
        controller.isAppearanceLightNavigationBars = !p.isDark
        paint(activity.findViewById(android.R.id.content), p)
    }

    fun paint(v: View, p: Palette) {
        val tag = v.tag as? String
        if (tag == TAG_CARD) {
            v.background = rounded(p.surface, v.resources.displayMetrics.density * 16f)
        }
        when (v) {
            is FloatingActionButton -> {
                val secondary = tag == TAG_FAB_SECONDARY
                v.backgroundTintList = ColorStateList.valueOf(if (secondary) p.surface else p.accent)
                v.imageTintList = ColorStateList.valueOf(if (secondary) p.accent else p.onAccent)
            }
            is Button -> {
                val secondary = tag == TAG_BUTTON_SECONDARY
                v.backgroundTintList = ColorStateList.valueOf(if (secondary) p.surface else p.accent)
                v.setTextColor(if (secondary) p.text else p.onAccent)
            }
            is EditText -> {
                v.setTextColor(p.text)
                v.setHintTextColor(p.textSecondary)
                v.backgroundTintList = ColorStateList.valueOf(p.accent)
            }
            is TextView -> v.setTextColor(
                when (tag) {
                    TAG_ACCENT -> p.accent
                    TAG_SECONDARY -> p.textSecondary
                    else -> p.text
                }
            )
            is ViewGroup -> for (i in 0 until v.childCount) paint(v.getChildAt(i), p)
        }
    }
}
