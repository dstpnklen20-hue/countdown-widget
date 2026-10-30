import { type LocalDate, parts } from "../core/date";
import { PERSONAL_CALENDAR_ID, type Task } from "../data/model";
import { dueDay } from "./dates";
import { isCountdown, isDone } from "./tasks";

/** Port of domain/Search.kt. */

export type SearchKind = "ALL" | "EVENTS" | "TASKS";
export type SearchPeriod = "ANY" | "FUTURE" | "PAST" | "MONTH" | "YEAR";

export const SEARCH_KINDS: { key: SearchKind; label: string }[] = [{ key: "ALL", label: "Всё" }, { key: "EVENTS", label: "События" }, { key: "TASKS", label: "Задачи" }];
export const SEARCH_PERIODS: { key: SearchPeriod; label: string }[] = [
  { key: "ANY", label: "Любые даты" }, { key: "FUTURE", label: "Впереди" }, { key: "PAST", label: "Прошедшие" },
  { key: "MONTH", label: "Этот месяц" }, { key: "YEAR", label: "Этот год" },
];

/**
 * A search: every word of [text] in the title, notes or place; none of the words of [without];
 * only [kind], only the [calendars] given (null: all) and the [period].
 */
export interface Query {
  text: string;
  without?: string;
  kind?: SearchKind;
  calendars?: Set<string> | null;
  period?: SearchPeriod;
}

const words = (s: string) => s.toLowerCase().split(/[ \t\n,]+/).filter(Boolean);

/**
 * Entries matching [q]: those whose title has every word first, then notes-only matches; open
 * ones before completed; then by date (dateless last).
 */
export function search(tasks: Task[], q: Query, today: LocalDate): Task[] {
  const want = words(q.text);
  const not = words(q.without ?? "");
  if (!want.length) return [];
  const kind = q.kind ?? "ALL";
  const period = q.period ?? "ANY";
  const found = tasks.filter((t) => {
    const text = [t.title, t.content, t.location ?? ""].join("\n").toLowerCase();
    if (!want.every((w) => text.includes(w)) || not.some((w) => text.includes(w))) return false;
    const event = !!t.isEvent || isCountdown(t);
    if (kind === "EVENTS" && !event) return false;
    if (kind === "TASKS" && event) return false;
    if (q.calendars && t.isEvent && !q.calendars.has(t.calendarId ?? PERSONAL_CALENDAR_ID)) return false;
    const day = dueDay(t);
    switch (period) {
      case "ANY": return true;
      // A repeating entry still has days ahead even when its first one is past.
      case "FUTURE": return day != null && (day >= today || t.repeatRule != null);
      case "PAST": return day != null && day < today;
      case "MONTH": return day != null && day.slice(0, 7) === today.slice(0, 7);
      case "YEAR": return day != null && parts(day)[0] === parts(today)[0];
    }
  });
  const inTitle = (t: Task) => want.every((w) => t.title.toLowerCase().includes(w));
  return found.sort((a, b) =>
    Number(isDone(a)) - Number(isDone(b)) ||
    Number(!inTitle(a)) - Number(!inTitle(b)) ||
    Number(a.dueAt == null) - Number(b.dueAt == null) ||
    (period === "PAST" ? (b.dueAt ?? 0) - (a.dueAt ?? 0) : (a.dueAt ?? 0) - (b.dueAt ?? 0)) ||
    b.updatedAt - a.updatedAt);
}
