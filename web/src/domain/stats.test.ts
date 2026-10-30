import { expect, test } from "vitest";
import { blank } from "../data/model";
import { timedDue } from "./dates";
import { countPerDay, countPerMonth, eventMinutesByCalendar, focusMinutesPerDay, minusMonths, monthRange } from "./stats";

test("counts per day and month", () => {
  const at = (d: string, h: number) => timedDue(d, h * 60).at;
  const moments = [at("2026-10-05", 9), at("2026-10-05", 20), at("2026-10-07", 12), at("2026-09-30", 12)];
  expect(countPerDay(moments, "2026-10-05", 3)).toEqual([2, 0, 1]);
  expect(countPerMonth(moments, ["2026-09", "2026-10"])).toEqual([1, 3]);
  const s = blank.focus({ startedAt: at("2026-10-06", 10), endedAt: at("2026-10-06", 10) + 25 * 60_000 });
  expect(focusMinutesPerDay([s], "2026-10-05", 3)).toEqual([0, 25, 0]);
});

test("month ranges", () => {
  expect(monthRange("2025-11", "2026-02")).toEqual(["2025-11", "2025-12", "2026-01", "2026-02"]);
  expect(minusMonths("2026-03", 5)).toBe("2025-10");
});

test("hours of a repeating event per calendar", () => {
  const start = timedDue("2026-10-05", 9 * 60).at;
  const ev = blank.task({ title: "Работа", isEvent: 1, calendarId: "work", repeatRule: "FREQ=DAILY", startAt: start, dueAt: start + 2 * 3_600_000 });
  expect(eventMinutesByCalendar([ev], "2026-10-05", "2026-10-11").get("work")).toBe(7 * 120);
});
