import { type LocalDate, dayOfWeek, fromEpochDay, plusDays, toEpochDay, withDayOfMonth } from "../core/date";
import type { Habit } from "../data/model";
import { localDateOf } from "./dates";

/** Port of domain/Habits.kt. Days are stored as "MO,WE,FR"; empty means every day. */
const CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];

export function habitDays(codes: string): Set<number> {
  const set = new Set(codes.split(",").map((c) => CODES.indexOf(c.trim().toUpperCase()) + 1).filter((d) => d > 0));
  return set.size ? set : new Set([1, 2, 3, 4, 5, 6, 7]);
}

/** "" for every day (or none chosen), else the codes in week order. */
export function habitDaysCode(days: Set<number>): string {
  return days.size === 7 || days.size === 0 ? "" : [1, 2, 3, 4, 5, 6, 7].filter((d) => days.has(d)).map((d) => CODES[d - 1]).join(",");
}

export const isScheduled = (h: Habit, day: LocalDate) => habitDays(h.days).has(dayOfWeek(day));

export interface HabitStats { current: number; best: number; total: number; rate: number }

/**
 * Streaks count consecutive scheduled days that reached the goal; unscheduled days neither
 * extend nor break a streak. An unfinished today does not break the current streak yet.
 * [counts]: epoch day → count.
 */
export function habitStats(h: Habit, counts: Map<number, number>, today: LocalDate): HabitStats {
  const done = (d: LocalDate) => (counts.get(toEpochDay(d)) ?? 0) >= h.goal;
  let current = 0;
  let day = done(today) ? today : plusDays(today, -1);
  for (let guard = 0; guard < 3660; guard++) {
    if (isScheduled(h, day)) {
      if (done(day)) current++;
      else break;
    }
    day = plusDays(day, -1);
  }
  const doneDays = [...counts.entries()].filter(([, c]) => c >= h.goal).map(([d]) => d).sort((a, b) => a - b);
  let best = 0;
  if (doneDays.length) {
    let run = 0;
    for (let d = fromEpochDay(doneDays[0]); d <= fromEpochDay(doneDays[doneDays.length - 1]); d = plusDays(d, 1)) {
      if (!isScheduled(h, d)) continue;
      if (done(d)) best = Math.max(best, ++run);
      else run = 0;
    }
  }
  // A habit created mid-month is only judged from the day it was created.
  const created = localDateOf(h.createdAt);
  const monthStart = withDayOfMonth(today, 1) > created ? withDayOfMonth(today, 1) : created;
  let scheduled = 0, hit = 0;
  for (let d = monthStart; d <= today; d = plusDays(d, 1)) {
    if (!isScheduled(h, d)) continue;
    scheduled++;
    if (done(d)) hit++;
  }
  return { current, best: Math.max(best, current), total: doneDays.length, rate: scheduled ? Math.floor((hit * 100) / scheduled) : 0 };
}
