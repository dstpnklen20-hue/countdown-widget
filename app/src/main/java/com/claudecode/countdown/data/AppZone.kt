package com.claudecode.countdown.data

import java.time.ZoneId
import java.util.TimeZone

/**
 * The time zone the whole app works in: the phone's, or one the user picked in the calendar
 * settings. It is set as the process default, so every screen, widget, reminder and calendar
 * computation (they all use ZoneId.systemDefault()) follows it.
 *
 * Android resets the process default when the phone's zone changes, so [apply] is called again
 * then (BootReceiver), at start and when the app comes to the front.
 */
object AppZone {
    /** The phone's own zone, whatever the app uses. */
    fun device(): ZoneId {
        val current = TimeZone.getDefault()
        // Clearing the default makes the runtime read the system setting again.
        TimeZone.setDefault(null)
        val device = TimeZone.getDefault().toZoneId()
        TimeZone.setDefault(current)
        return device
    }

    /** Makes [id] the zone of the app; null follows the phone. */
    fun apply(id: String?) {
        TimeZone.setDefault(null)
        val zone = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }
}
