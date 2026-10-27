import {
  type DayOfWeek, type LocalDate, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY,
  date, dayOfWeek, lengthOfMonth, nextOrSame, parts, plusDays, plusMonths, plusWeeks, plusYears, previousOrSame, withDayOfMonth,
} from "./date";

/** Port of the app's core RepeatRule (core/.../RepeatRule.kt): keep the two in step. */
export type Freq = "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY";

/** A weekday, optionally with an ordinal inside the month: 2MO = second Monday, -1FR = last Friday. */
export interface WeekdayNum {
  day: DayOfWeek;
  ordinal: number;
}

/**
 * Subset of RFC 5545 RRULE: FREQ, INTERVAL, BYDAY, BYMONTHDAY (incl. -1 = last day), COUNT, UNTIL.
 * COUNT is the number of occurrences still left, decremented each time the task advances.
 */
export interface RepeatRule {
  freq: Freq;
  interval: number;
  byDay: WeekdayNum[];
  byMonthDay: number | null;
  count: number | null;
  until: LocalDate | null;
}

const CODES: Record<number, string> = { 1: "MO", 2: "TU", 3: "WE", 4: "TH", 5: "FR", 6: "SA", 7: "SU" };
const BY_CODE: Record<string, DayOfWeek> = { MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6, SU: 7 };

export const WEEKDAYS: DayOfWeek[] = [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY] as DayOfWeek[];

export function rule(freq: Freq, init: Partial<RepeatRule> = {}): RepeatRule {
  return { freq, interval: 1, byDay: [], byMonthDay: null, count: null, until: null, ...init };
}

export function toRRule(r: RepeatRule): string {
  const out = [`FREQ=${r.freq}`];
  if (r.interval > 1) out.push(`INTERVAL=${r.interval}`);
  if (r.byDay.length) out.push("BYDAY=" + r.byDay.map((w) => (w.ordinal !== 0 ? `${w.ordinal}` : "") + CODES[w.day]).join(","));
  if (r.byMonthDay != null) out.push(`BYMONTHDAY=${r.byMonthDay}`);
  if (r.count != null) out.push(`COUNT=${r.count}`);
  if (r.until != null) out.push("UNTIL=" + r.until.replace(/-/g, ""));
  return out.join(";");
}

export function parseRule(rrule: string | null | undefined): RepeatRule | null {
  if (!rrule || !rrule.trim()) return null;
  const map = new Map<string, string>();
  for (const p of rrule.replace(/^RRULE:/, "").split(";")) {
    const i = p.indexOf("=");
    if (i > 0) map.set(p.slice(0, i).toUpperCase(), p.slice(i + 1));
  }
  const f = map.get("FREQ")?.toUpperCase();
  if (f !== "DAILY" && f !== "WEEKLY" && f !== "MONTHLY" && f !== "YEARLY") return null;
  const byDay: WeekdayNum[] = [];
  for (const token of map.get("BYDAY")?.split(",") ?? []) {
    const t = token.trim().toUpperCase();
    const day = BY_CODE[t.slice(-2)];
    if (!day) continue;
    const ord = parseInt(t.slice(0, -2), 10);
    byDay.push({ day, ordinal: Number.isNaN(ord) ? 0 : ord });
  }
  const int = (k: string) => {
    const v = map.get(k);
    if (v == null) return null;
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? null : n;
  };
  const untilRaw = map.get("UNTIL")?.slice(0, 8);
  const until = untilRaw && /^\d{8}$/.test(untilRaw) ? `${untilRaw.slice(0, 4)}-${untilRaw.slice(4, 6)}-${untilRaw.slice(6, 8)}` : null;
  return rule(f, {
    interval: Math.max(1, int("INTERVAL") ?? 1),
    byDay,
    byMonthDay: int("BYMONTHDAY"),
    count: int("COUNT"),
    until,
  });
}

/** [day] of the month containing [anyDayOfMonth]; -1 = last day; clamped to month length. */
export function dayInMonth(anyDayOfMonth: LocalDate, day: number): LocalDate {
  const length = lengthOfMonth(anyDayOfMonth);
  const d = day < 0 ? length + day + 1 : day;
  return withDayOfMonth(anyDayOfMonth, Math.min(Math.max(d, 1), length));
}

function candidatesInMonth(anyDayOfMonth: LocalDate, days: WeekdayNum[]): LocalDate[] {
  const [y, m] = parts(anyDayOfMonth);
  const first = date(y, m, 1);
  const last = dayInMonth(first, -1);
  const out: LocalDate[] = [];
  for (const wd of days) {
    const d = wd.ordinal > 0 ? plusWeeks(nextOrSame(first, wd.day), wd.ordinal - 1) : previousOrSame(last, wd.day);
    if (parts(d)[1] === m) out.push(d);
  }
  return out.sort();
}

function nextWeekly(r: RepeatRule, from: LocalDate): LocalDate {
  const days = [...new Set(r.byDay.map((w) => w.day))].sort((a, b) => a - b);
  if (!days.length) return plusWeeks(from, r.interval);
  const today = dayOfWeek(from);
  const later = days.find((d) => d > today);
  if (later != null) return nextOrSame(from, later);
  const weekStart = plusWeeks(previousOrSame(from, MONDAY), r.interval);
  return nextOrSame(weekStart, days[0]);
}

function nextMonthly(r: RepeatRule, from: LocalDate): LocalDate {
  const ordinals = r.byDay.filter((w) => w.ordinal !== 0);
  if (ordinals.length) {
    const here = candidatesInMonth(from, ordinals).find((d) => d > from);
    if (here) return here;
    let month = plusMonths(withDayOfMonth(from, 1), r.interval);
    for (let i = 0; i < 48; i++) {
      const c = candidatesInMonth(month, ordinals)[0];
      if (c) return c;
      month = plusMonths(month, r.interval);
    }
    return plusMonths(from, r.interval);
  }
  const day = r.byMonthDay ?? parts(from)[2];
  return dayInMonth(plusMonths(withDayOfMonth(from, 1), r.interval), day);
}

function nextYearly(r: RepeatRule, from: LocalDate): LocalDate {
  const target = plusYears(withDayOfMonth(from, 1), r.interval);
  return dayInMonth(target, r.byMonthDay ?? parts(from)[2]);
}

/**
 * First occurrence strictly after [from]. Intervals are counted from [from], which is the current
 * occurrence (or the completion date for "repeat from completion").
 */
export function nextAfter(r: RepeatRule, from: LocalDate): LocalDate | null {
  if (r.count != null && r.count <= 1) return null;
  let next: LocalDate;
  switch (r.freq) {
    case "DAILY": next = plusDays(from, r.interval); break;
    case "WEEKLY": next = nextWeekly(r, from); break;
    case "MONTHLY": next = nextMonthly(r, from); break;
    case "YEARLY": next = nextYearly(r, from); break;
  }
  return r.until == null || next <= r.until ? next : null;
}

/** The rule to store after advancing once (COUNT shrinks). */
export function advanced(r: RepeatRule): RepeatRule {
  return r.count != null ? { ...r, count: r.count - 1 } : r;
}
