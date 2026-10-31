import { DateTime } from "luxon";
import type { LocalDate, LocalTime } from "../core/date";
import type { Task } from "../data/model";

/** Port of domain/Dates.kt. The browser's zone plays the role of the phone's zone. */
let zoneCache = { at: 0, zone: "" };

/** The computer's zone; asked again once a minute (a laptop may travel). */
export const zone = (): string => {
  const t = Date.now();
  if (t - zoneCache.at > 60_000) zoneCache = { at: t, zone: Intl.DateTimeFormat().resolvedOptions().timeZone };
  return zoneCache.zone;
};

export interface Due {
  at: number;
  isAllDay: boolean;
  timeZone: string;
}

const dt = (ms: number, z: string) => DateTime.fromMillis(ms, { zone: z });

export function epochAt(day: LocalDate, t: LocalTime, z: string = zone()): number {
  return DateTime.fromISO(day, { zone: z }).set({ hour: Math.floor(t / 60), minute: t % 60 }).toMillis();
}

export const allDayDue = (day: LocalDate, z: string = zone()): Due => ({ at: epochAt(day, 0, z), isAllDay: true, timeZone: z });
export const timedDue = (day: LocalDate, t: LocalTime, z: string = zone()): Due => ({ at: epochAt(day, t, z), isAllDay: false, timeZone: z });

export const localDateOf = (ms: number, z: string = zone()): LocalDate => dt(ms, z).toISODate()!;
export const localTimeOf = (ms: number, z: string = zone()): LocalTime => {
  const d = dt(ms, z);
  return d.hour * 60 + d.minute;
};

export const todayDate = (): LocalDate => DateTime.now().setZone(zone()).toISODate()!;
export const nowMinute = (): LocalTime => {
  const d = DateTime.now().setZone(zone());
  return d.hour * 60 + d.minute;
};

function validZone(z: string | null | undefined): string | null {
  return z && DateTime.now().setZone(z).isValid ? z : null;
}

/** Calendar day of a task's due date. All-day dates stay fixed in the zone they were set in. */
export function dueDay(task: Pick<Task, "dueAt" | "isAllDay" | "timeZone">, deviceZone: string = zone()): LocalDate | null {
  if (task.dueAt == null) return null;
  // Rows are never changed in place (an edit makes a new object), so the answer can be kept.
  const cached = dueDays.get(task);
  if (cached && cached.zone === deviceZone && cached.dueAt === task.dueAt) return cached.day;
  const z = task.isAllDay ? validZone(task.timeZone) ?? deviceZone : deviceZone;
  const day = localDateOf(task.dueAt, z);
  dueDays.set(task, { zone: deviceZone, dueAt: task.dueAt, day });
  return day;
}

const dueDays = new WeakMap<object, { zone: string; dueAt: number; day: LocalDate }>();

/** The day [ms] falls on for an entry: all-day ones in their own zone. */
export function dayOf(task: Pick<Task, "isAllDay" | "timeZone">, ms: number): LocalDate {
  return localDateOf(ms, task.isAllDay ? validZone(task.timeZone) ?? zone() : zone());
}
