import { expect, test } from "vitest";
import { blank } from "../data/model";
import { calendarRows } from "./calendar";

const at = Date.UTC(2026, 10, 5, 9);
const parent = blank.task({ title: "Ремонт" });

test("a subtask with a date of its own takes part in the calendar", () => {
  const dated = blank.task({ title: "Купить краску", parentId: parent.id, dueAt: at });
  const undated = blank.task({ title: "Выбрать цвет", parentId: parent.id });
  const titles = calendarRows([parent, dated, undated]).map((t) => t.title);
  expect(titles).toEqual(["Ремонт", "Купить краску"]);
});

test("an open subtask goes away with its finished parent, a finished one stays in the history", () => {
  const closed = { ...parent, status: "DONE" as const };
  const open = blank.task({ title: "Открытая", parentId: parent.id, dueAt: at });
  const finished = blank.task({ title: "Сделанная", parentId: parent.id, dueAt: at, status: "DONE" });
  expect(calendarRows([closed, open, finished]).map((t) => t.title)).toEqual(["Ремонт", "Сделанная"]);
});

test("a subtask without a live parent stays out", () => {
  const orphan = blank.task({ title: "Сама по себе", parentId: "gone", dueAt: at });
  expect(calendarRows([orphan])).toEqual([]);
});
