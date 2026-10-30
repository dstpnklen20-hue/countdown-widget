import { expect, test } from "vitest";
import { plusDays } from "../core/date";
import { blank } from "../data/model";
import { calendarEntries, layoutBlocks } from "./calendar";
import { allDayDue, dueDay, timedDue, todayDate } from "./dates";
import { durationMinutes, groupTasks, matches, nextOccurrence, timeStart } from "./tasks";

const today = todayDate();

test("a task at a time that lasts a while starts at its start", () => {
  const start = timedDue(today, 10 * 60).at;
  const t = blank.task({ title: "t", startAt: start, dueAt: start + 90 * 60_000 });
  expect(durationMinutes(t)).toBe(90);
  expect(timeStart(t)).toBe(start);
  // A start days before the end (a multi-day event made a task) is no length.
  expect(durationMinutes({ ...t, startAt: start - 3 * 86_400_000 })).toBeNull();
});

test("completing a daily task moves it to tomorrow, keeping its time and length", () => {
  const due = timedDue(today, 8 * 60);
  const t = blank.task({ title: "зарядка", dueAt: due.at, startAt: due.at - 30 * 60_000, timeZone: due.timeZone, repeatRule: "FREQ=DAILY;COUNT=3" });
  const next = nextOccurrence(t, Date.now())!;
  expect(dueDay(next)).toBe(plusDays(today, 1));
  expect(next.dueAt! - next.startAt!).toBe(30 * 60_000);
  expect(next.repeatRule).toBe("FREQ=DAILY;COUNT=2");
  expect(nextOccurrence({ ...t, repeatRule: "FREQ=DAILY;COUNT=1" }, Date.now())).toBeNull();
});

test("smart lists and date groups", () => {
  const overdue = blank.task({ title: "a", ...dueOf(allDayDue(plusDays(today, -2))) });
  const todays = blank.task({ title: "b", ...dueOf(allDayDue(today)) });
  const later = blank.task({ title: "c", ...dueOf(allDayDue(plusDays(today, 20))) });
  const event = blank.task({ title: "e", isEvent: 1, ...dueOf(allDayDue(today)) });
  expect(matches("today", overdue, new Set(), today)).toBe(true);
  expect(matches("today", later, new Set(), today)).toBe(false);
  expect(matches("all", event, new Set(), today)).toBe(false);
  const groups = groupTasks("all", [later, todays, overdue], Date.now(), today);
  expect(groups.map((g) => g.kind)).toEqual(["OVERDUE", "TODAY", "LATER"]);
});

test("calendar spreads repeats and overnight events", () => {
  const start = timedDue(today, 23 * 60).at;
  const sleep = blank.task({ title: "сон", isEvent: 1, startAt: start, dueAt: start + 7 * 3_600_000, repeatRule: "FREQ=DAILY" });
  const entries = calendarEntries([sleep], today, plusDays(today, 2));
  const first = entries.get(today)!;
  expect(first.map((e) => [e.start, e.end])).toEqual([[23 * 60, 1440]]);
  const next = entries.get(plusDays(today, 1))!;
  // The morning part of today's sleep and the evening of the next one.
  expect(next.map((e) => [e.start, e.end, e.projected])).toEqual([[0, 6 * 60, false], [23 * 60, 1440, true]]);
});

test("overlapping blocks share lanes; a much later one nests", () => {
  const placed = layoutBlocks([
    { item: "a", start: 600, end: 720 },
    { item: "b", start: 600, end: 660 },
    { item: "c", start: 690, end: 750 },
  ]);
  const byItem = Object.fromEntries(placed.map((p) => [p.item, p]));
  expect(byItem.a.lanes).toBe(2);
  expect(byItem.b.lane).toBe(1);
  expect(byItem.c.lane).toBe(1); // b has ended by 11:30: c takes its lane
});

function dueOf(d: { at: number; isAllDay: boolean; timeZone: string }) {
  return { dueAt: d.at, isAllDay: (d.isAllDay ? 1 : 0) as 0 | 1, timeZone: d.timeZone };
}

test("habit streaks skip days it is not planned for", async () => {
  const { habitStats } = await import("./habits");
  const { toEpochDay } = await import("../core/date");
  // Mon, Wed, Fri; done on 3 planned days in a row, then a missed Friday, then Monday.
  const h = blank.habit({ name: "Зарядка", days: "MO,WE,FR", createdAt: Date.parse("2026-09-01T00:00:00Z") });
  const counts = new Map(["2026-10-05", "2026-10-07", "2026-10-09", "2026-10-19"].map((d) => [toEpochDay(d), 1]));
  const s = habitStats(h, counts, "2026-10-20");
  expect(s.current).toBe(1);
  expect(s.best).toBe(3);
  expect(s.total).toBe(4);
});
