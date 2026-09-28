package com.claudecode.countdown.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudecode.countdown.container
import com.claudecode.countdown.launchAsync
import java.time.LocalDate

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        launchAsync(context) { context.container.reminders.deliverDueAndReschedule() }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DONE = "com.claudecode.countdown.ACTION_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.claudecode.countdown.ACTION_REMINDER_SNOOZE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_MINUTES = "minutes"
        const val ACTION_HABIT_DONE = "com.claudecode.countdown.ACTION_HABIT_DONE"
        const val EXTRA_HABIT_ID = "habit_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_HABIT_DONE) {
            val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
            ReminderNotifier.cancelHabit(context, habitId)
            launchAsync(context) {
                val habit = context.container.habits.active().firstOrNull { it.id == habitId } ?: return@launchAsync
                context.container.habits.setCount(habitId, LocalDate.now(), habit.goal)
            }
            return
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        ReminderNotifier.cancel(context, taskId)
        val repo = context.container.tasks
        launchAsync(context) {
            when (intent.action) {
                ACTION_DONE -> repo.get(taskId)?.takeIf { !it.isDone }?.let { repo.setDone(it, true) }
                ACTION_SNOOZE -> repo.snooze(taskId, intent.getIntExtra(EXTRA_MINUTES, 15))
            }
        }
    }
}
