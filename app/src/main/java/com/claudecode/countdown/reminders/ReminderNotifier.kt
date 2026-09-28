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
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.formatDue

object ReminderNotifier {
    private const val CHANNEL_ID = "reminders"

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
    }

    fun show(context: Context, task: Task) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val id = notificationId(task.id)
        val open = PendingIntent.getActivity(
            context, id, MainActivity.openTaskIntent(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle(task.title)
            .setContentText(formatDue(task, today()) ?: task.content.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(formatDue(task, today()), task.content.takeIf { it.isNotBlank() }).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Выполнено", action(context, task.id, ReminderActionReceiver.ACTION_DONE, 0))
            .addAction(0, "+15 мин", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 15))
            .addAction(0, "+1 час", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE, 60))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
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
