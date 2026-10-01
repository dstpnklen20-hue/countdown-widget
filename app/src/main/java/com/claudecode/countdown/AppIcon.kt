package com.claudecode.countdown

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * Colour variants of the launcher icon. [alias] is the activity-alias in the manifest that shows
 * this icon; the classic one is MainActivity itself, so a phone that never changes the icon keeps
 * exactly the component it had.
 */
enum class AppIconStyle(val label: String, val alias: String?, @DrawableRes val foreground: Int, @ColorRes val background: Int) {
    CLASSIC("Классика", null, R.drawable.ic_logo_classic, R.color.logo_bg_classic),
    LIGHT("Светлый", "IconLight", R.drawable.ic_logo_light, R.color.logo_bg_light),
    MIDNIGHT("Полночь", "IconMidnight", R.drawable.ic_logo_midnight, R.color.logo_bg_midnight),
    OCEAN("Океан", "IconOcean", R.drawable.ic_logo_ocean, R.color.logo_bg_ocean),
    FOREST("Лес", "IconForest", R.drawable.ic_logo_forest, R.color.logo_bg_forest),
    SUNSET("Закат", "IconSunset", R.drawable.ic_logo_sunset, R.color.logo_bg_sunset),
    VIOLET("Фиолет", "IconViolet", R.drawable.ic_logo_violet, R.color.logo_bg_violet),
}

/**
 * Switches the launcher icon by enabling one launcher component (MainActivity or an alias of it)
 * and disabling the rest. A disabled component can't be started, so everything that opens the app
 * (notifications, widgets) goes through [launchIntent], which targets the enabled one.
 */
object AppIcon {
    /** Read from the system itself (which component is enabled), so it can't drift from a saved setting. */
    fun current(context: Context): AppIconStyle {
        val pm = context.packageManager
        return AppIconStyle.entries.firstOrNull {
            it.alias != null && pm.getComponentEnabledSetting(componentOf(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIconStyle.CLASSIC
    }

    private fun componentOf(context: Context, style: AppIconStyle): ComponentName =
        ComponentName(context.packageName, "com.claudecode.countdown." + (style.alias ?: "MainActivity"))

    /** The component that opens the app now. */
    fun component(context: Context): ComponentName = componentOf(context, current(context))

    fun launchIntent(context: Context): Intent = Intent().setComponent(component(context))

    /**
     * Enables the chosen icon first, then disables the others, so there is never a moment without
     * a launcher entry. DONT_KILL_APP keeps the app running while the launcher refreshes.
     */
    fun apply(context: Context, style: AppIconStyle) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(componentOf(context, style), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in AppIconStyle.entries) {
            if (other == style) continue
            // MainActivity is enabled by the manifest, the aliases are not: say so explicitly either way.
            pm.setComponentEnabledSetting(componentOf(context, other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * After an update: if no alias is enabled, MainActivity must be, or the app would have no
     * launcher entry at all (e.g. a future version dropped the alias that was in use).
     */
    fun ensureLaunchable(context: Context) {
        val main = componentOf(context, AppIconStyle.CLASSIC)
        if (current(context) == AppIconStyle.CLASSIC &&
            context.packageManager.getComponentEnabledSetting(main) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            context.packageManager.setComponentEnabledSetting(main, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}
