import { DateTime } from "luxon";
import { type LocalDate, daysBetween, plusDays } from "../core/date";
import { advanced, nextAfter, parseRule } from "../core/repeatRule";
import type { Habit, Reminder, Task } from "../data/model";
import { dueDay, epochAt, localDateOf, zone } from "./dates";
import { isScheduled } from "./habits";
import { skippedDays, timeStart } from "./tasks";

/** Port of domain/Reminders.kt. */

/** Time of day (minutes after midnight) at which all-day tasks remind, as the app's default. */
export const ALL_DAY_REMINDER_MINUTES = 9 * 60;
const MINUTE = 60_000;

/**
 * When [reminder] should fire for [task]; a later snooze wins over the base time. All-day tasks
 * remind at [allDayMinutes] after midnight of the due day (minus the offset). Events and tasks
 * that last a while remind before they start, and a repeating event, which is never ticked off,
 * reminds before its first occurrence that fires after [after].
 */
export function reminderTrigger(task: Task, reminder: Reminder, allDayMinutes = ALL_DAY_REMINDER_MINUTES, after = -Infinity): number | null {
  if (reminder.absoluteAt != null) return withSnooze(reminder.absoluteAt, reminder, after);
  const anchor = (task.isEvent ? (task.startAt != null && (task.dueAt == null || task.startAt <= task.dueAt) ? task.startAt : null) : timeStart(task)) ?? task.dueAt;
  if (anchor == null) return null;
  const offset = (reminder.offsetMinutes ?? 0) * MINUTE;
  const triggerAt = (at: number) => (task.isAllDay ? at + allDayMinutes * MINUTE - offset : at - offset);
  let base = triggerAt(anchor);
  const skipped = task.isEvent ? skippedDays(task) : new Set<string>();
  const first = DateTime.fromMillis(anchor, { zone: zone() });
  // Occurrences are told apart by their due day; the anchor may be on the day before (sleep).
  const firstDue = dueDay(task) ?? localDateOf(anchor);
  if (task.isEvent && (base <= after || skipped.has(firstDue))) {
    let r = parseRule(task.repeatRule);
    if (!r) return skipped.has(firstDue) ? null : withSnooze(base, reminder, after);
    let day = firstDue;
    let steps = 0;
    while ((base <= after || skipped.has(day)) && steps++ < 2_000) {
      const next = nextAfter(r, day);
      if (!next) return null;
      day = next;
      r = advanced(r);
      base = triggerAt(first.plus({ days: daysBetween(firstDue, day) }).toMillis());
    }
  }
  return withSnooze(base, reminder, after);
}

/** A snooze later than the base time wins; one still ahead also wins over a later base. */
function withSnooze(base: number, reminder: Reminder, after: number): number {
  const snooze = reminder.snoozedUntil;
  if (snooze == null) return base;
  return snooze > base || (after !== -Infinity && snooze > after) ? snooze : base;
}

/** The reminders whose trigger falls into (from, to], with their tasks. */
export function dueReminders(tasks: Map<string, Task>, reminders: Reminder[], from: number, to: number): { task: Task; reminder: Reminder; at: number }[] {
  const out: { task: Task; reminder: Reminder; at: number }[] = [];
  for (const r of reminders) {
    const task = tasks.get(r.taskId);
    if (!task) continue;
    const at = reminderTrigger(task, r, ALL_DAY_REMINDER_MINUTES, from);
    if (at != null && at > from && at <= to) out.push({ task, reminder: r, at });
  }
  return out;
}

/** Daily habit reminders on planned days that fall into (from, to] (ReminderScheduler.habitTriggers). */
export function habitTriggers(habits: Habit[], from: number, to: number): { habit: Habit; at: number; day: LocalDate }[] {
  const out: { habit: Habit; at: number; day: LocalDate }[] = [];
  const today = localDateOf(to);
  for (const h of habits) {
    if (h.reminderMinute == null || h.archived || h.deleted) continue;
    for (let i = -1; i <= 1; i++) {
      const day = plusDays(today, i);
      if (!isScheduled(h, day)) continue;
      const at = epochAt(day, h.reminderMinute);
      if (at > from && at <= to) out.push({ habit: h, at, day });
    }
  }
  return out;
}
