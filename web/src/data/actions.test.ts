import { expect, test } from "vitest";
import { plusDays } from "../core/date";
import { allDayDue, dueDay, timedDue, todayDate } from "../domain/dates";
import { matches } from "../domain/tasks";
import { Actions } from "./actions";
import { type Kind, type Tables, blank, idOf } from "./model";
import type { Rows } from "./store";

/** The rows in memory, the way the page's Store keeps them. */
function memory(): Rows {
  const maps = new Map<Kind, Map<string, unknown>>();
  const map = (k: Kind) => maps.get(k) ?? maps.set(k, new Map()).get(k)!;
  const rows: Rows = {
    get: <K extends Kind>(k: K, id: string | null | undefined) => (id == null ? undefined : map(k).get(id) as Tables[K] | undefined),
    all: <K extends Kind>(k: K) => [...map(k).values()] as Tables[K][],
    live: <K extends Kind>(k: K) => rows.all(k).filter((r) => !(r as { deleted: number }).deleted),
    put: <K extends Kind>(k: K, ...list: Tables[K][]) => {
      for (const r of list) map(k).set(idOf(k, r), r);
      return list;
    },
    seed: <K extends Kind>(k: K, r: Tables[K]) => { if (!map(k).has(idOf(k, r))) map(k).set(idOf(k, r), r); },
    purge: async (k, ids) => { for (const id of ids) map(k).delete(id); },
  };
  return rows;
}

const today = todayDate();

test("a task one won't do leaves the lists, keeps its place in its own list and can come back", () => {
  const store = memory();
  const actions = new Actions(store);
  const task = actions.createTask({ title: "Не сделаю" });
  const undo = actions.setDone(task, true, "WONT_DO");
  const stored = store.get("tasks", task.id)!;
  expect(stored.status).toBe("WONT_DO");
  expect(stored.completedAt).not.toBeNull();
  expect(matches("wontdo", stored, new Set(), today)).toBe(true);
  expect(matches("completed", stored, new Set(), today)).toBe(false);
  undo();
  expect(store.get("tasks", task.id)!.status).toBe("OPEN");
  actions.setDone(store.get("tasks", task.id)!, true, "WONT_DO");
  actions.setDone(store.get("tasks", task.id)!, false);
  expect(store.get("tasks", task.id)!.status).toBe("OPEN");
  expect(store.get("tasks", task.id)!.completedAt).toBeNull();
});

test("skipping one repeat of a task moves it on and records this one as not done", () => {
  const store = memory();
  const actions = new Actions(store);
  const due = allDayDue(today);
  const task = actions.createTask({ title: "Каждый день", dueAt: due.at, isAllDay: 1, timeZone: due.timeZone, repeatRule: "FREQ=DAILY" });
  actions.setDone(task, true, "WONT_DO");
  const moved = store.get("tasks", task.id)!;
  expect(moved.status).toBe("OPEN");
  expect(dueDay(moved)).toBe(plusDays(today, 1));
  const copy = store.all("tasks").find((t) => t.id !== task.id)!;
  expect(copy.status).toBe("WONT_DO");
  expect(copy.repeatRule).toBeNull();
});

test("a copy is a new open task with the same reminders, tags and checklist", () => {
  const store = memory();
  const actions = new Actions(store);
  const due = timedDue(today, 10 * 60);
  const task = actions.createTask({ title: "Оригинал", dueAt: due.at, timeZone: due.timeZone, status: "DONE", completedAt: 1 }, ["работа"]);
  actions.addReminder(task.id, 15);
  actions.addChecklistItem(task.id, "пункт");
  store.put("checklist_items", { ...actions.checklist(task.id)[0], checked: 1 });
  const copy = actions.duplicate(store.get("tasks", task.id)!);
  expect(copy.id).not.toBe(task.id);
  expect(copy.status).toBe("OPEN");
  expect(actions.tagNames(copy.id)).toEqual(["работа"]);
  expect(actions.reminders(copy.id).map((r) => r.offsetMinutes)).toEqual([15]);
  const items = actions.checklist(copy.id);
  expect(items.map((c) => c.title)).toEqual(["пункт"]);
  expect(items[0].checked).toBe(0);
  expect(items[0].id).not.toBe(actions.checklist(task.id)[0].id);
});

test("blank rows of a section are complete enough to be copied", () => {
  expect(blank.checklistItem({ taskId: "x" }).checked).toBe(0);
});

test("quick add finds a list by its name without the icon", () => {
  const store = memory();
  const actions = new Actions(store);
  const list = actions.createList("🏠 Дом", null, null);
  expect(actions.quickAdd("Полить цветы ~Дом", "inbox")!.listId).toBe(list.id);
  expect(actions.quickAdd("Убрать ~🏠 Дом", "inbox")!.title).toContain("Убрать");
});
