package com.claudecode.countdown.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.R
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.data.db.Habit
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.formatDue
import com.claudecode.countdown.ui.formatEventSpan
import android.media.AudioAttributes
import android.media.RingtoneManager

object ReminderNotifier {
    private const val CHANNEL_ID = "reminders"
    private const val EVENT_CHANNEL_ID = "events"
    private const val SILENT_CHANNEL_ID = "reminders_silent"
    private const val ALARM_CHANNEL_ID = "event_alarm"

    fun notificationId(taskId: String) = taskId.hashCode()

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Напоминания о задачах"
                    enableVibration(true)
                }
            )
        }
        // One channel per kind, so each can get its own sound in the system settings.
        if (manager.getNotificationChannel(EVENT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(EVENT_CHANNEL_ID, "События", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Напоминания о событиях календаря"
                    enableVibration(true)
                }
            )
        }
        if (manager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(SILENT_CHANNEL_ID, "Напоминания без звука", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "В тихие часы и во время «времени для работы»"
                }
            )
        }
        if (manager.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL_ID, "Будильник событий", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Важные события: звонит, пока не ответите"
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                }
            )
        }
    }

    /**
     * A task's or an event's reminder. [silent] (quiet hours, time to work) posts it on a channel
     * without sound. Events get "snooze", "open" and, with a place, "directions" instead of "done".
     */
    fun show(context: Context, task: Task, silent: Boolean = false) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val id = notificationId(task.id)
        val open = PendingIntent.getActivity(
            context, id, MainActivity.openTaskIntent(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = when {
            silent -> SILENT_CHANNEL_ID
            task.isEvent -> EVENT_CHANNEL_ID
            else -> CHANNEL_ID
        }
        val text = whenText(task)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle(task.title)
            .setContentText(text ?: task.content.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, task.content.takeIf { it.isNotBlank() }).joinToString("\n")))
            .setCategory(if (task.isEvent) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (task.isEvent) {
            builder.addAction(0, "Отложить", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 10))
            builder.addAction(0, "Открыть", open)
            task.location?.let { place -> builder.addAction(0, "Маршрут", directions(context, task.id, place)) }
        } else {
            builder.addAction(0, "Выполнено", action(context, task.id, ReminderActionReceiver.ACTION_DONE, 0))
            builder.addAction(0, "+15 мин", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 15))
            builder.addAction(0, "+1 час", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 60))
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    /**
     * A reminder set to ring as an alarm: the alarm sound repeats until it is answered (for a
     * minute at most), and over the lock screen it opens [AlarmActivity] full screen.
     */
    fun showAlarm(context: Context, task: Task) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val id = notificationId(task.id)
        val full = PendingIntent.getActivity(
            context, id, AlarmActivity.intent(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle(task.title)
            .setContentText(whenText(task) ?: "")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setTimeoutAfter(60_000)
            .addAction(0, "Отложить", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 10))
            .addAction(0, "Выключить", action(context, task.id, ReminderActionReceiver.ACTION_DISMISS, 0))
            .build()
            .apply { flags = flags or android.app.Notification.FLAG_INSISTENT }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    /** "Сегодня, 14:00–15:00 · Офис" for an event, the due date for a task. */
    fun whenText(task: Task): String? {
        val time = if (task.isEvent) formatEventSpan(task, today()) else formatDue(task, today())
        return listOfNotNull(time, task.location).joinToString(" · ").ifEmpty { null }
    }

    private fun directions(context: Context, taskId: String, place: String): PendingIntent =
        PendingIntent.getActivity(
            context, (taskId + "route").hashCode(),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(place)))
                .let { nav -> if (nav.resolveActivity(context.packageManager) != null) nav else Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place))) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun showHabit(context: Context, habit: Habit) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val id = ("habit:" + habit.id).hashCode()
        val open = PendingIntent.getActivity(
            context, id,
            MainActivity.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION_HABIT_DONE)
                .putExtra(ReminderActionReceiver.EXTRA_HABIT_ID, habit.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle("${habit.emoji} ${habit.name}")
            .setContentText(if (habit.goal > 1) "Цель на сегодня: ${habit.goal}" else "Время для привычки")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Выполнено", done)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancelHabit(context: Context, habitId: String) {
        NotificationManagerCompat.from(context).cancel(("habit:$habitId").hashCode())
    }

    fun cancel(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun action(context: Context, taskId: String, action: String, minutes: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderActionReceiver.EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(
            context, (taskId + action + minutes).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
