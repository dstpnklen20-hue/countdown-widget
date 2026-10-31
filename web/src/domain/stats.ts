import { type LocalDate, plusDays, toEpochDay } from "../core/date";
import type { FocusSession, Task } from "../data/model";
import { calendarEntries } from "./calendar";
import { localDateOf } from "./dates";

/** Port of domain/Stats.kt. Months are "YYYY-MM". */

/** Days [from] .. [from] + [days] - 1, oldest first. */
export const dayRange = (from: LocalDate, days: number): LocalDate[] => Array.from({ length: days }, (_, i) => plusDays(from, i));

/** How many of [moments] (epoch ms) fall on each day of the range. */
export function countPerDay(moments: number[], from: LocalDate, days: number): number[] {
  const counts = new Array<number>(days).fill(0);
  const first = toEpochDay(from);
  for (const m of moments) {
    const i = toEpochDay(localDateOf(m)) - first;
    if (i >= 0 && i < days) counts[i]++;
  }
  return counts;
}

/** Whole minutes of focus per day of the range, by the day each session started. */
export function focusMinutesPerDay(sessions: FocusSession[], from: LocalDate, days: number): number[] {
  const ms = new Array<number>(days).fill(0);
  const first = toEpochDay(from);
  for (const s of sessions) {
    const i = toEpochDay(localDateOf(s.startedAt)) - first;
    if (i >= 0 && i < days) ms[i] += s.durationMs;
  }
  return ms.map((x) => Math.floor(x / 60_000));
}

/** Months [from] .. [to] inclusive, oldest first. */
export function monthRange(from: string, to: string): string[] {
  const out: string[] = [];
  let [y, m] = from.split("-").map(Number);
  for (let guard = 0; guard < 1200; guard++) {
    const key = `${y}-${String(m).padStart(2, "0")}`;
    if (key > to) break;
    out.push(key);
    if (++m > 12) { m = 1; y++; }
  }
  return out;
}

/** Months [months] moved back by [n]: "2026-10" minus 5 = "2026-05". */
export function minusMonths(month: string, n: number): string {
  const [y, m] = month.split("-").map(Number);
  const total = y * 12 + (m - 1) - n;
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, "0")}`;
}

const monthOf = (ms: number) => localDateOf(ms).slice(0, 7);

/** How many of [moments] fall in each of [months]. */
export function countPerMonth(moments: number[], months: string[]): number[] {
  const index = new Map(months.map((m, i) => [m, i]));
  const counts = new Array<number>(months.length).fill(0);
  for (const m of moments) {
    const i = index.get(monthOf(m));
    if (i != null) counts[i]++;
  }
  return counts;
}

/** Whole minutes of focus in each of [months], by the day each session started. */
export function focusMinutesPerMonth(sessions: FocusSession[], months: string[]): number[] {
  const index = new Map(months.map((m, i) => [m, i]));
  const ms = new Array<number>(months.length).fill(0);
  for (const s of sessions) {
    const i = index.get(monthOf(s.startedAt));
    if (i != null) ms[i] += s.durationMs;
  }
  return ms.map((x) => Math.floor(x / 60_000));
}

/**
 * Minutes of timed events per calendar (null key: the default one) over [from]..[to], repeats
 * included, as Google Calendar's time insights. All-day events take no hours and are left out.
 */
export function eventMinutesByCalendar(tasks: Task[], from: LocalDate, to: LocalDate): Map<string | null, number> {
  const out = new Map<string | null, number>();
  for (const list of calendarEntries(tasks.filter((t) => t.isEvent && !t.deleted), from, to).values()) {
    for (const e of list) {
      if (e.start == null || e.end == null) continue;
      out.set(e.task.calendarId, (out.get(e.task.calendarId) ?? 0) + e.end - e.start);
    }
  }
  return out;
}
