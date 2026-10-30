import { expect, test } from "vitest";
import { dueDay, localTimeOf, timedDue } from "../domain/dates";
import { nameUuid, parseIcs, triggerMinutes, writeIcs } from "./ics";
import { blank } from "./model";

test("ids of imported entries are the app's (Java UUID.nameUUIDFromBytes)", () => {
  // Expected value printed by Java 17 for the same name.
  expect(nameUuid("ics:event-1@google.com")).toBe("b807304f-0377-3c29-b94e-e18c4241c828");
});

test("alarm triggers", () => {
  expect(triggerMinutes("-PT15M")).toBe(15);
  expect(triggerMinutes("-P1D")).toBe(1440);
  expect(triggerMinutes("-PT1H30M")).toBe(90);
  expect(triggerMinutes("PT0S")).toBe(0);
  expect(triggerMinutes("PT15M")).toBeNull();
});

test("a Google Calendar event with a repeat, a skipped day and an alarm", () => {
  const text = [
    "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:event-1@google.com",
    "DTSTART;TZID=Europe/Moscow:20261005T090000", "DTEND;TZID=Europe/Moscow:20261005T100000",
    "RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE;TZID=Europe/Moscow:20261012T090000",
    "SUMMARY:Планёрка\\, отдел", "LOCATION:Офис", "DESCRIPTION:строка 1\\nстрока 2",
    "BEGIN:VALARM", "TRIGGER:-PT10M", "END:VALARM", "END:VEVENT", "END:VCALENDAR",
  ].join("\r\n");
  const [item] = parseIcs(text, "Europe/Moscow");
  expect(item.task.id).toBe("b807304f-0377-3c29-b94e-e18c4241c828");
  expect(item.task.title).toBe("Планёрка, отдел");
  expect(item.task.content).toBe("строка 1\nстрока 2");
  expect(item.task.exDates).toBe("2026-10-12");
  expect(item.task.dueAt! - item.task.startAt!).toBe(3_600_000);
  expect(item.reminders).toEqual([10]);
  expect(item.task.repeatRule).toBe("FREQ=WEEKLY;BYDAY=MO");
});

test("written and read back, an entry stays the same", () => {
  const start = timedDue("2026-11-02", 18 * 60).at;
  const ev = blank.task({ title: "Тренировка; зал", isEvent: 1, startAt: start, dueAt: start + 90 * 60_000, repeatRule: "FREQ=WEEKLY;BYDAY=MO", exDates: "2026-11-09", location: "Зал" });
  const allDay = blank.task({ title: "Отпуск", isEvent: 1, isAllDay: 1, startAt: timedDue("2026-12-01", 0).at, dueAt: timedDue("2026-12-05", 0).at });
  const todo = blank.task({ title: "Сдать отчёт", dueAt: timedDue("2026-11-03", 12 * 60).at, status: "DONE" });
  const back = parseIcs(writeIcs([{ task: ev, reminders: [0, 30] }, { task: allDay, reminders: [] }, { task: todo, reminders: [] }]));
  expect(back).toHaveLength(3);
  expect(back[0].task.title).toBe("Тренировка; зал");
  expect(localTimeOf(back[0].task.startAt!)).toBe(18 * 60);
  expect(back[0].task.dueAt! - back[0].task.startAt!).toBe(90 * 60_000);
  expect(back[0].task.exDates).toBe("2026-11-09");
  expect(back[0].reminders).toEqual([0, 30]);
  expect(back[1].task.isAllDay).toBe(1);
  expect(dueDay(back[1].task)).toBe("2026-12-05");
  expect(back[2].task.isEvent).toBe(0);
  expect(back[2].task.status).toBe("DONE");
});
