import { expect, test } from "vitest";
import { plusDays } from "../core/date";
import { type Reminder, type Task, blank } from "../data/model";
import { calendarEntries } from "./calendar";
import { localTimeOf, timedDue } from "./dates";
import { reminderTrigger } from "./reminders";
import { atOccurrence, deleteFromSeries, editSeries, shifted } from "./series";

/** The same checks as SeriesTest.kt in the app. */
const monday = "2026-10-05";

const daily = (rule = "FREQ=DAILY"): Task => blank.task({
  title: "Планёрка", isEvent: 1, repeatRule: rule,
  startAt: timedDue(monday, 9 * 60).at, dueAt: timedDue(monday, 10 * 60).at,
});

const at = (t: Task, day: string, from: number, to: number): Task =>
  ({ ...t, startAt: timedDue(day, from * 60).at, dueAt: timedDue(day, to * 60).at });

/** Day → start times (hours) of what the calendar shows. */
function days(tasks: Task[], to = plusDays(monday, 6)): Map<string, number[]> {
  const out = new Map<string, number[]>();
  for (const [day, list] of calendarEntries(tasks, monday, to)) {
    out.set(day, list.map((e) => localTimeOf(atOccurrence(e.task, e.occurrence).startAt!) / 60));
  }
  return out;
}

test("deleting one occurrence skips only that day", () => {
  const shown = days(deleteFromSeries(daily(), plusDays(monday, 2), "ONE").save);
  expect(shown.size).toBe(6);
  expect(shown.has(plusDays(monday, 2))).toBe(false);
});

test("moving one occurrence leaves the rest in place", () => {
  const master = daily();
  const wednesday = plusDays(monday, 2);
  const change = editSeries(master, wednesday, at(master, wednesday, 14, 15), "ONE", 1);
  const shown = days(change.save);
  expect(shown.get(wednesday)).toEqual([14]);
  expect(shown.get(plusDays(monday, 3))).toEqual([9]);
  expect(change.save[1].seriesId).toBe(master.id);
  expect(change.save[1].repeatRule).toBeNull();
});

test("changing following splits the series", () => {
  const master = daily();
  const thursday = plusDays(monday, 3);
  const shown = days(editSeries(master, thursday, at(master, thursday, 18, 19), "FOLLOWING", 1).save);
  expect(shown.get(plusDays(monday, 2))).toEqual([9]);
  expect(shown.get(thursday)).toEqual([18]);
  expect(shown.get(plusDays(monday, 6))).toEqual([18]);
});

test("following keeps the total of a counted series", () => {
  const master = daily("FREQ=DAILY;COUNT=5");
  const change = editSeries(master, plusDays(monday, 2), at(master, plusDays(monday, 2), 18, 19), "FOLLOWING", 1);
  expect(days(change.save).size).toBe(5);
});

test("changing all moves the whole series", () => {
  const master = daily();
  const wednesday = plusDays(monday, 2);
  // Wednesday's occurrence moved to 7:00 on Thursday: every one moves a day later, to 7:00.
  const change = editSeries(master, wednesday, at(master, plusDays(wednesday, 1), 7, 8), "ALL", 1);
  const shown = days(change.save);
  expect(shown.has(monday)).toBe(false);
  expect(shown.get(plusDays(monday, 1))).toEqual([7]);
  expect(change.save).toHaveLength(1);
  expect(change.save[0].id).toBe(master.id);
});

test("deleting following ends the series", () => {
  expect(days(deleteFromSeries(daily(), plusDays(monday, 3), "FOLLOWING").save).size).toBe(3);
  expect(deleteFromSeries(daily(), monday, "FOLLOWING").delete).toHaveLength(1);
});

test("a block dragged by an hour, or stretched", () => {
  const master = daily();
  const moved = shifted(master, plusDays(monday, 1), 60, 60);
  expect(localTimeOf(moved.startAt!)).toBe(10 * 60);
  expect(localTimeOf(moved.dueAt!)).toBe(11 * 60);
  const longer = shifted(master, monday, 0, 30);
  expect(longer.dueAt! - longer.startAt!).toBe(90 * 60_000);
  // A task with only a time gets a start when stretched.
  const task = blank.task({ title: "t", dueAt: timedDue(monday, 12 * 60).at });
  const stretched = shifted(task, monday, 0, 30);
  expect(stretched.startAt).toBe(task.dueAt);
  expect(stretched.dueAt! - stretched.startAt!).toBe(60 * 60_000);
});

const reminder = (init: Partial<Reminder>): Reminder => blank.reminder({ taskId: "x", offsetMinutes: 0, ...init });

test("a snoozed reminder of a repeating event fires today, not tomorrow", () => {
  const after = timedDue(monday, 9 * 60 + 1).at;
  const snoozed = reminder({ snoozedUntil: after + 10 * 60_000 });
  expect(reminderTrigger(daily(), snoozed, undefined, after)).toBe(after + 10 * 60_000);
  expect(reminderTrigger(daily(), snoozed, undefined, after + 11 * 60_000)).toBe(timedDue(plusDays(monday, 1), 9 * 60).at);
});

test("a reminder skips a deleted occurrence", () => {
  const master = deleteFromSeries(daily(), plusDays(monday, 1), "ONE").save[0];
  const after = timedDue(monday, 9 * 60 + 30).at;
  expect(reminderTrigger(master, reminder({ taskId: master.id }), undefined, after)).toBe(timedDue(plusDays(monday, 2), 9 * 60).at);
});
