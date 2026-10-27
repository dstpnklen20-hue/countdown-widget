import { type LocalDate, dayOfWeek, formatTime, parts, plusDays } from "../core/date";
import { plural } from "../core/repeatText";
import type { Task } from "../data/model";
import { dayOf, dueDay, localTimeOf } from "../domain/dates";
import { durationMinutes } from "../domain/tasks";

/** Ports of ui/Formats.kt: dates and times read the same as in the app. */

const MONTHS_GEN = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
const MONTHS_SHORT = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"];
export const MONTHS_NOM = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"];
const WEEKDAYS_FULL = ["Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"];
export const WEEKDAYS_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"];

export function formatDay(day: LocalDate, today: LocalDate): string {
  if (day === today) return "Сегодня";
  if (day === plusDays(today, 1)) return "Завтра";
  if (day === plusDays(today, -1)) return "Вчера";
  if (day > today && day < plusDays(today, 7)) return WEEKDAYS_FULL[dayOfWeek(day) - 1];
  const [y, m, d] = parts(day);
  return y === parts(today)[0] ? `${d} ${MONTHS_SHORT[m - 1]}` : `${d} ${MONTHS_SHORT[m - 1]} ${y}`;
}

/** "Вторник, 27 октября 2026". */
export function formatFullDate(day: LocalDate): string {
  const [y, m, d] = parts(day);
  return `${WEEKDAYS_FULL[dayOfWeek(day) - 1]}, ${d} ${MONTHS_GEN[m - 1]} ${y}`;
}

/** When an event happens: "Сегодня, 06:35–07:05", "1 окт, 23:00 – 2 окт, 05:45", "1 окт – 3 окт". */
export function formatEventSpan(t: Task, today: LocalDate): string | null {
  if (t.dueAt == null) return null;
  const start = t.startAt != null && t.startAt <= t.dueAt ? t.startAt : null;
  if (start == null) return formatDueOnly(t, today);
  const endDay = dueDay(t)!;
  const startDay = dayOf(t, start);
  if (t.isAllDay) return startDay === endDay ? formatDay(endDay, today) : `${formatDay(startDay, today)} – ${formatDay(endDay, today)}`;
  const s = formatTime(localTimeOf(start)), e = formatTime(localTimeOf(t.dueAt));
  return startDay === endDay ? `${formatDay(startDay, today)}, ${s}–${e}` : `${formatDay(startDay, today)}, ${s} – ${formatDay(endDay, today)}, ${e}`;
}

function formatDueOnly(t: Task, today: LocalDate): string | null {
  const day = dueDay(t);
  if (!day) return null;
  const date = formatDay(day, today);
  return t.isAllDay ? date : `${date}, ${formatTime(localTimeOf(t.dueAt!))}`;
}

/** "Сегодня, 18:00"; a task that lasts a while shows its span, "Сегодня, 10:00–11:00". */
export function formatDue(t: Task, today: LocalDate): string | null {
  return durationMinutes(t) != null ? formatEventSpan(t, today) : formatDueOnly(t, today);
}

export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} мин`;
  return minutes % 60 === 0 ? `${minutes / 60} ч` : `${Math.floor(minutes / 60)} ч ${minutes % 60} мин`;
}

/** "Через 3 дня", "Сегодня", "2 дня назад" for countdowns. */
export function daysLeft(day: LocalDate, today: LocalDate): string {
  const n = Math.round((Date.parse(day) - Date.parse(today)) / 86_400_000);
  if (n === 0) return "Сегодня";
  if (n === 1) return "Завтра";
  const abs = Math.abs(n);
  const word = `${abs} ${plural(abs, "день", "дня", "дней")}`;
  return n > 0 ? `Через ${word}` : `${word} назад`;
}

export function monthTitle(day: LocalDate, today: LocalDate): string {
  const [y, m] = parts(day);
  return MONTHS_NOM[m - 1] + (y !== parts(today)[0] ? ` ${y}` : "");
}

export const PRIORITY_COLORS: Record<number, string> = { 3: "#e53935", 2: "#fb8c00", 1: "#1e88e5" };
export const PRIORITY_NAMES: Record<number, string> = { 3: "Высокий", 2: "Средний", 1: "Низкий", 0: "Без приоритета" };
export const PRIORITY_CATEGORY: Record<number, string> = {
  3: "Срочно и важно", 2: "Важно, не срочно", 1: "Срочно, не важно", 0: "Не срочно и не важно",
};

/** Colours offered for lists, tasks and events (LIST_COLORS in Formats.kt). */
export const PALETTE = ["#e53935", "#fb8c00", "#fdd835", "#43a047", "#00acc1", "#1e88e5", "#5e35b1", "#d81b60"];

export const REMINDER_PRESETS: { label: string; minutes: number }[] = [
  { label: "В момент начала", minutes: 0 },
  { label: "За 5 минут", minutes: 5 },
  { label: "За 15 минут", minutes: 15 },
  { label: "За 30 минут", minutes: 30 },
  { label: "За 1 час", minutes: 60 },
  { label: "За 1 день", minutes: 24 * 60 },
];

export function reminderLabel(minutes: number, allDay: boolean): string {
  if (allDay) {
    if (minutes <= 0) return "В день события";
    const days = Math.round(minutes / 1440);
    return days === 1 ? "За 1 день" : `За ${days} ${plural(days, "день", "дня", "дней")}`;
  }
  if (minutes === 0) return "В момент начала";
  if (minutes < 60) return `За ${minutes} ${plural(minutes, "минуту", "минуты", "минут")}`;
  if (minutes % 1440 === 0) { const d = minutes / 1440; return `За ${d} ${plural(d, "день", "дня", "дней")}`; }
  if (minutes % 60 === 0) { const h = minutes / 60; return `За ${h} ${plural(h, "час", "часа", "часов")}`; }
  return `За ${formatDuration(minutes)}`;
}
