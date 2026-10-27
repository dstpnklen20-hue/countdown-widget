import { DateTime } from "luxon";
import { type LocalDate, date, daysBetween, parts, plusDays } from "../core/date";
import { advanced, nextAfter, parseRule } from "../core/repeatRule";
import { blank, type Task } from "../data/model";
import { allDayDue, dueDay, localTimeOf, zone } from "./dates";
import { isDone, skippedDays } from "./tasks";

/** Port of domain/CalendarProjection.kt, Timeline.kt (layout) and Holidays.kt. */

export const MINUTES_PER_DAY = 1440;
/** Tasks with a time but no length take this much of the timeline. */
export const DEFAULT_BLOCK_MINUTES = 30;
/** Entries longer than this are not spread over the days between (likely a typo in the dates). */
const MAX_SPAN_DAYS = 62;

/**
 * A task shown on a calendar day; [projected] marks a future repeat that does not exist yet.
 * A timed entry covers minutes [start]..[end] (end exclusive) of that day, null for all-day ones.
 * Something running past midnight shows on every day it touches, as [part] of [parts].
 */
export interface CalendarEntry {
  task: Task;
  date: LocalDate;
  projected: boolean;
  start: number | null;
  end: number | null;
  part: number;
  parts: number;
  /** Due day of the occurrence this is part of. */
  occurrence: LocalDate;
}

interface Span { from: DateTime; to: DateTime }

/** Start and end of the occurrence due on [day] (local wall time). */
function startAndEnd(t: Task, day: LocalDate): Span | null {
  if (t.dueAt == null) return null;
  const startAt = t.startAt != null && t.startAt <= t.dueAt && (t.dueAt - t.startAt) / 86_400_000 <= MAX_SPAN_DAYS ? t.startAt : null;
  const midnight = (d: LocalDate) => DateTime.fromISO(d, { zone: "utc" });
  if (t.isAllDay) {
    if (startAt == null) return null;
    const first = dueDay({ ...t, dueAt: startAt });
    const due = dueDay(t);
    if (!first || !due) return null;
    return { from: midnight(plusDays(day, -daysBetween(first, due))), to: midnight(day) };
  }
  const dueTime = localTimeOf(t.dueAt);
  const due = midnight(day).plus({ minutes: dueTime });
  if (startAt == null || startAt === t.dueAt) {
    // No length: a default block from the due time, cut at midnight.
    const endOfDay = midnight(plusDays(day, 1));
    const end = due.plus({ minutes: DEFAULT_BLOCK_MINUTES });
    return { from: due, to: end < endOfDay ? end : endOfDay };
  }
  return { from: due.minus({ milliseconds: t.dueAt - startAt }), to: due };
}

function spanDays(t: Task): number {
  const day = dueDay(t);
  if (!day) return 0;
  const s = startAndEnd(t, day);
  if (!s) return 0;
  return Math.min(Math.max(daysBetween(s.from.toISODate()!, s.to.toISODate()!), 0), MAX_SPAN_DAYS);
}

function occurrenceEntries(t: Task, day: LocalDate, projected: boolean): CalendarEntry[] {
  const range = startAndEnd(t, day);
  const entry = (d: LocalDate, part: number, parts: number, start: number | null = null, end: number | null = null): CalendarEntry =>
    ({ task: t, date: d, projected, start, end, part, parts, occurrence: day });
  if (t.isAllDay || t.dueAt == null) {
    if (!range) return [entry(day, 1, 1)];
    const first = range.from.toISODate()!;
    const n = daysBetween(first, day) + 1;
    return Array.from({ length: n }, (_, i) => entry(plusDays(first, i), i + 1, n));
  }
  const { from, to } = range!;
  // A block that ends exactly at midnight does not touch the next day.
  const toDay = to.toISODate()!;
  const toMinute = to.hour * 60 + to.minute;
  const lastDay = toMinute === 0 && to > from ? plusDays(toDay, -1) : toDay;
  const firstDay = from.toISODate()!;
  const n = daysBetween(firstDay, lastDay) + 1;
  return Array.from({ length: n }, (_, i) => {
    const d = plusDays(firstDay, i);
    const s = d === firstDay ? from.hour * 60 + from.minute : 0;
    const e = d === toDay ? toMinute : MINUTES_PER_DAY;
    return entry(d, i + 1, n, s, Math.max(e, s));
  });
}

/**
 * Entries for [start]..[end] inclusive. Repeating open tasks and events are expanded into their
 * future occurrences (not for "repeat from completion", whose dates are unknown in advance).
 */
export function calendarEntries(tasks: Task[], start: LocalDate, end: LocalDate, maxPerTask = 400): Map<LocalDate, CalendarEntry[]> {
  const out: CalendarEntry[] = [];
  for (const t of tasks) {
    const day = dueDay(t);
    if (!day) continue;
    const last = plusDays(end, spanDays(t));
    const add = (occ: LocalDate, projected: boolean) => {
      for (const e of occurrenceEntries(t, occ, projected)) if (e.date >= start && e.date <= end) out.push(e);
    };
    const skipped = skippedDays(t);
    if (day <= last && !skipped.has(day)) add(day, false);
    if ((isDone(t) && !t.isEvent) || t.repeatFrom === "COMPLETION") continue;
    let r = parseRule(t.repeatRule);
    if (!r) continue;
    let current = day;
    for (let steps = 0; steps < maxPerTask; steps++) {
      const next = nextAfter(r, current);
      if (!next || next > last) break;
      r = advanced(r);
      current = next;
      if (next >= start && !skipped.has(next)) add(next, true);
    }
  }
  const byDay = new Map<LocalDate, CalendarEntry[]>();
  for (const e of out) byDay.set(e.date, [...(byDay.get(e.date) ?? []), e]);
  for (const list of byDay.values()) {
    list.sort((a, b) =>
      Number(isDone(a.task) && !a.task.isEvent) - Number(isDone(b.task) && !b.task.isEvent) ||
      Number(a.start != null) - Number(b.start != null) ||
      (a.start ?? 0) - (b.start ?? 0) ||
      b.task.priority - a.task.priority);
  }
  return byDay;
}

// --- Layout of blocks on a day (Timeline.kt) ---

/** A block starting at least this long after the one it overlaps sits on top of it, indented. */
const NEST_AFTER_MINUTES = 30;

export interface Placed<T> { item: T; start: number; end: number; lane: number; lanes: number; depth: number }

/**
 * Lays blocks out like Google Calendar: overlapping blocks share the width in lanes; a block that
 * starts 30+ minutes after the latest one of a busy lane is drawn over it, indented.
 */
export function layoutBlocks<T>(blocks: { item: T; start: number; end: number }[]): Placed<T>[] {
  const sorted = [...blocks].sort((a, b) => a.start - b.start || b.end - a.end);
  const out: Placed<T>[] = [];
  let group: { b: (typeof blocks)[number]; lane: number; depth: number }[] = [];
  let lanes: (typeof blocks)[number][][] = [];
  let groupEnd = -1;
  const flush = () => {
    const count = Math.max(lanes.length, 1);
    for (const g of group) out.push({ item: g.b.item, start: g.b.start, end: g.b.end, lane: g.lane, lanes: count, depth: g.depth });
    group = [];
    lanes = [];
  };
  for (const b of sorted) {
    if (b.start >= groupEnd && group.length) flush();
    lanes = lanes.map((running) => running.filter((x) => x.end > b.start));
    const free = lanes.findIndex((l) => l.length === 0);
    const nest = free >= 0 ? -1 : lanes.findIndex((l) => l[l.length - 1].start + NEST_AFTER_MINUTES <= b.start);
    let lane: number;
    if (free >= 0) lane = free;
    else if (nest >= 0) lane = nest;
    else { lane = lanes.length; lanes.push([]); }
    const depth = lanes[lane].length;
    lanes[lane].push(b);
    group.push({ b, lane, depth });
    groupEnd = Math.max(group.length === 1 ? b.end : groupEnd, b.end);
  }
  if (group.length) flush();
  return out;
}

// --- Holidays (shown, not stored) ---

export const VIRTUAL_ID_PREFIX = "virtual:";
export const isVirtual = (t: Task) => t.id.startsWith(VIRTUAL_ID_PREFIX);
const HOLIDAY_COLOR = 0xff0b8043 | 0;

/** Russian public holidays (Labour Code, art. 112): first day, last day, name. */
const RUSSIAN_HOLIDAYS: [string, string, string][] = [
  ["01-01", "01-06", "Новогодние каникулы"],
  ["01-07", "01-07", "Рождество Христово"],
  ["01-08", "01-08", "Новогодние каникулы"],
  ["02-23", "02-23", "День защитника Отечества"],
  ["03-08", "03-08", "Международный женский день"],
  ["05-01", "05-01", "Праздник Весны и Труда"],
  ["05-09", "05-09", "День Победы"],
  ["06-12", "06-12", "День России"],
  ["11-04", "11-04", "День народного единства"],
];

export function russianHolidays(fromYear: number): Task[] {
  const z = zone();
  return RUSSIAN_HOLIDAYS.map(([first, last, name]) => {
    const [, fm, fd] = parts(`2000-${first}`);
    const [, lm, ld] = parts(`2000-${last}`);
    return blank.task({
      id: `${VIRTUAL_ID_PREFIX}holiday:--${first}`,
      title: name, isEvent: 1, isAllDay: 1,
      startAt: allDayDue(date(fromYear, fm, fd), z).at,
      dueAt: allDayDue(date(fromYear, lm, ld), z).at,
      timeZone: z, repeatRule: "FREQ=YEARLY", color: HOLIDAY_COLOR, createdAt: 0, updatedAt: 0,
    });
  });
}
