import { DateTime } from "luxon";
import { type LocalDate, daysBetween, plusDays } from "../core/date";
import { advanced, nextAfter, parseRule, toRRule } from "../core/repeatRule";
import { type Task, newId } from "../data/model";
import { dueDay, zone } from "./dates";
import { skippedDays } from "./tasks";
import { DEFAULT_BLOCK_MINUTES } from "./calendar";

/** Port of domain/Series.kt: which occurrences of a repeating event a change applies to, as Google Calendar asks. */
export type SeriesScope = "ONE" | "FOLLOWING" | "ALL";

export const SCOPES: { key: SeriesScope; label: string }[] = [
  { key: "ONE", label: "Только это событие" },
  { key: "FOLLOWING", label: "Это и последующие" },
  { key: "ALL", label: "Все события" },
];

const toExDates = (days: Iterable<LocalDate>): string | null => {
  const list = [...new Set(days)].sort();
  return list.length ? list.join(",") : null;
};

export const withSkipped = (t: Task, day: LocalDate): Task => ({ ...t, exDates: toExDates([...skippedDays(t), day]) });

/**
 * Due days of a repeating entry from its first one up to [until], skipped ones included (they
 * still count for COUNT, as in RFC 5545). A single day for an entry that does not repeat.
 */
export function occurrenceDays(t: Task, until: LocalDate, max = 5_000): LocalDate[] {
  const first = dueDay(t);
  if (!first) return [];
  const out = [first];
  let r = parseRule(t.repeatRule);
  if (!r) return out;
  let day = first;
  while (out.length < max) {
    const next = nextAfter(r, day);
    if (!next || next > until) break;
    r = advanced(r);
    day = next;
    out.push(day);
  }
  return out;
}

const plusDaysAt = (at: number, days: number) => DateTime.fromMillis(at, { zone: zone() }).plus({ days }).toMillis();
const plusMinutesAt = (at: number, minutes: number) => DateTime.fromMillis(at, { zone: zone() }).plus({ minutes }).toMillis();

/** The entry as it is on the occurrence due on [day]: the same length, moved to that day. */
export function atOccurrence(t: Task, day: LocalDate): Task {
  const first = dueDay(t);
  if (!first) return t;
  const days = daysBetween(first, day);
  if (days === 0) return t;
  return { ...t, startAt: t.startAt != null ? plusDaysAt(t.startAt, days) : null, dueAt: t.dueAt != null ? plusDaysAt(t.dueAt, days) : null };
}

/**
 * The occurrence due on [occurrence] with its start moved by [startMinutes] and its end by
 * [endMinutes] (a block dragged or stretched on the time grid). A task with only a due time is a
 * block of DEFAULT_BLOCK_MINUTES from it: stretching it gives it a start.
 */
export function shifted(t: Task, occurrence: LocalDate, startMinutes: number, endMinutes: number): Task {
  const occ = atOccurrence(t, occurrence);
  const due = occ.dueAt;
  if (due == null) return occ;
  const start = occ.startAt != null && occ.startAt < due ? occ.startAt : null;
  if (start != null) {
    const s = plusMinutesAt(start, startMinutes);
    return { ...occ, startAt: s, dueAt: Math.max(plusMinutesAt(due, endMinutes), s + 15 * 60_000) };
  }
  if (startMinutes === endMinutes) return { ...occ, dueAt: plusMinutesAt(due, startMinutes) };
  const end = Math.max(plusMinutesAt(due, DEFAULT_BLOCK_MINUTES + endMinutes), due + 15 * 60_000);
  return { ...occ, startAt: due, dueAt: end };
}

/** What one change of a series does: rows to store as they are now, and ids that go to the trash. */
export interface SeriesChange { save: Task[]; delete: string[] }

/**
 * Saves [edited], the occurrence of [master] due on [occurrence] as the user changed it.
 * ONE takes it out of the series as an entry of its own; FOLLOWING ends the series the day before
 * and starts a new one from it; ALL changes the series, moving it by as many days as this
 * occurrence moved and giving it the new times.
 */
export function editSeries(master: Task, occurrence: LocalDate, edited: Task, scope: SeriesScope, now: number): SeriesChange {
  const first = dueDay(master);
  const repeats = master.repeatRule != null && first != null;
  let effective = scope;
  if (!repeats) effective = "ALL";
  else if (scope === "FOLLOWING" && occurrence <= first!) effective = "ALL";
  switch (effective) {
    case "ONE":
      return {
        save: [
          withSkipped(master, occurrence),
          { ...edited, id: newId(), repeatRule: null, exDates: null, seriesId: master.id, createdAt: now, updatedAt: now },
        ],
        delete: [],
      };
    case "ALL": {
      if (!repeats) return { save: [{ ...edited, id: master.id }], delete: [] };
      const editedDay = dueDay(edited);
      if (!editedDay) return { save: [{ ...edited, id: master.id }], delete: [] };
      // The series keeps its first day, moved by as many days as this occurrence moved.
      const moved = atOccurrence(edited, plusDays(editedDay, -daysBetween(first!, occurrence)));
      return {
        save: [{ ...moved, id: master.id, exDates: master.exDates, seriesId: master.seriesId, createdAt: master.createdAt, updatedAt: master.updatedAt }],
        delete: [],
      };
    }
    case "FOLLOWING": {
      const [ended, rest] = splitSeries(master, occurrence);
      const sameRule = edited.repeatRule === master.repeatRule;
      const skipped = [...skippedDays(master)].filter((d) => d >= occurrence);
      return {
        save: [
          ended,
          {
            ...edited, id: newId(), repeatRule: sameRule ? rest : edited.repeatRule, exDates: toExDates(skipped), seriesId: null,
            createdAt: now, updatedAt: now,
          },
        ],
        delete: [],
      };
    }
  }
}

/** Deletes the occurrence of [master] due on [occurrence], the ones after it too, or the whole series. */
export function deleteFromSeries(master: Task, occurrence: LocalDate, scope: SeriesScope): SeriesChange {
  const first = dueDay(master);
  const repeats = master.repeatRule != null && first != null;
  if (!repeats || scope === "ALL") return { save: [], delete: [master.id] };
  if (scope === "ONE") return { save: [withSkipped(master, occurrence)], delete: [] };
  if (occurrence <= first!) return { save: [], delete: [master.id] };
  return { save: [splitSeries(master, occurrence)[0]], delete: [] };
}

/**
 * Ends [master] before [occurrence]: with UNTIL the day before, or for a rule counted with COUNT,
 * with as many occurrences as there were before. Returns the ended series and the rule (RRULE
 * text) for the rest of it: the same rule with the remaining COUNT.
 */
function splitSeries(master: Task, occurrence: LocalDate): [Task, string | null] {
  const r = parseRule(master.repeatRule);
  if (!r) return [master, null];
  const before = occurrenceDays(master, plusDays(occurrence, -1)).length;
  const ended = r.count != null ? { ...r, count: before } : { ...r, until: plusDays(occurrence, -1) };
  const rest = r.count != null ? { ...r, count: Math.max(r.count - before, 1) } : r;
  return [
    { ...master, repeatRule: toRRule(ended), exDates: toExDates([...skippedDays(master)].filter((d) => d < occurrence)) },
    toRRule(rest),
  ];
}
