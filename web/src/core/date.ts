/**
 * Calendar dates without a zone, as ISO strings "2026-10-27" (they compare correctly as strings),
 * and times of day as minutes after midnight. The same model as java.time.LocalDate/LocalTime in
 * the Android app's core, so the ported rules read alike.
 */
export type LocalDate = string;
/** Minutes after midnight, 0..1439. */
export type LocalTime = number;

/** ISO weekday: 1 = Monday … 7 = Sunday. */
export type DayOfWeek = 1 | 2 | 3 | 4 | 5 | 6 | 7;

export const MONDAY = 1, TUESDAY = 2, WEDNESDAY = 3, THURSDAY = 4, FRIDAY = 5, SATURDAY = 6, SUNDAY = 7;

const DAY_MS = 86_400_000;

export function date(year: number, month: number, day: number): LocalDate {
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

export function parts(d: LocalDate): [number, number, number] {
  return [Number(d.slice(0, 4)), Number(d.slice(5, 7)), Number(d.slice(8, 10))];
}

export function isValidDate(year: number, month: number, day: number): boolean {
  return month >= 1 && month <= 12 && day >= 1 && day <= lengthOfMonth(date(year, month, 1));
}

export function toEpochDay(d: LocalDate): number {
  const [y, m, day] = parts(d);
  return Math.round(Date.UTC(y, m - 1, day) / DAY_MS);
}

export function fromEpochDay(n: number): LocalDate {
  const t = new Date(n * DAY_MS);
  return date(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
}

export const plusDays = (d: LocalDate, n: number): LocalDate => fromEpochDay(toEpochDay(d) + n);
export const plusWeeks = (d: LocalDate, n: number): LocalDate => plusDays(d, 7 * n);
export const daysBetween = (a: LocalDate, b: LocalDate): number => toEpochDay(b) - toEpochDay(a);

export function dayOfWeek(d: LocalDate): DayOfWeek {
  // 1970-01-01 was a Thursday.
  return ((((toEpochDay(d) + 3) % 7) + 7) % 7 + 1) as DayOfWeek;
}

export function lengthOfMonth(d: LocalDate): number {
  const [y, m] = parts(d);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function withDayOfMonth(d: LocalDate, day: number): LocalDate {
  const [y, m] = parts(d);
  return date(y, m, day);
}

/** Like java.time: the day is clamped to the new month's length (31 Jan + 1 month = 28 Feb). */
export function plusMonths(d: LocalDate, n: number): LocalDate {
  const [y, m, day] = parts(d);
  const total = y * 12 + (m - 1) + n;
  const first = date(Math.floor(total / 12), (total % 12) + 1, 1);
  return withDayOfMonth(first, Math.min(day, lengthOfMonth(first)));
}

export const plusYears = (d: LocalDate, n: number): LocalDate => plusMonths(d, 12 * n);

export const minDate = (a: LocalDate, b: LocalDate): LocalDate => (a < b ? a : b);
export const maxDate = (a: LocalDate, b: LocalDate): LocalDate => (a > b ? a : b);

/** The [dow] on or after [d]. */
export function nextOrSame(d: LocalDate, dow: number): LocalDate {
  return plusDays(d, (dow - dayOfWeek(d) + 7) % 7);
}

/** The [dow] strictly after [d]. */
export function next(d: LocalDate, dow: number): LocalDate {
  return plusDays(d, ((dow - dayOfWeek(d) + 6) % 7) + 1);
}

export function previousOrSame(d: LocalDate, dow: number): LocalDate {
  return plusDays(d, -((dayOfWeek(d) - dow + 7) % 7));
}

export function time(hour: number, minute = 0): LocalTime {
  return hour * 60 + minute;
}

export function formatTime(t: LocalTime): string {
  const m = ((t % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

/** "HH:MM" → minutes, or null. */
export function parseTime(s: string): LocalTime | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(s.trim());
  if (!m) return null;
  const h = Number(m[1]), min = Number(m[2]);
  return h < 24 && min < 60 ? h * 60 + min : null;
}
