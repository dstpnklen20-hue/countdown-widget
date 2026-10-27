import { type LocalDate, plusDays, toEpochDay } from "../core/date";
import { advanced, nextAfter, parseRule, toRRule } from "../core/repeatRule";
import { describe } from "../core/repeatText";
import { INBOX_ID, type Task } from "../data/model";
import { dueDay, epochAt, localDateOf, localTimeOf, todayDate, zone } from "./dates";

/** Ports of domain/SmartLists.kt, Repeats.kt and parts of Timeline.kt. */

export const isDone = (t: Task) => t.status !== "OPEN";
export const isCountdown = (t: Task) => t.displayMode === "COUNTDOWN";
/** Events and countdowns are things that happen; the rest are tasks to do. */
export const happens = (t: Task) => !!t.isEvent || isCountdown(t);

export type FilterKey = "inbox" | "today" | "tomorrow" | "week" | "all" | "countdowns" | "completed" | `list:${string}` | `tag:${string}`;

export const SMART: { key: FilterKey; label: string; icon: string }[] = [
  { key: "inbox", label: "Входящие", icon: "inbox" },
  { key: "today", label: "Сегодня", icon: "today" },
  { key: "tomorrow", label: "Завтра", icon: "sun" },
  { key: "week", label: "7 дней", icon: "week" },
  { key: "all", label: "Все", icon: "all" },
  { key: "countdowns", label: "Отсчёты", icon: "hourglass" },
  { key: "completed", label: "Выполнено", icon: "done" },
];

export function isOverdue(t: Task, now: number, today: LocalDate): boolean {
  if (isDone(t)) return false;
  const day = dueDay(t);
  if (day == null) return false;
  return t.isAllDay ? day < today : (t.dueAt ?? Infinity) < now && day <= today;
}

export function matches(filter: FilterKey, t: Task, tagIds: Set<string>, today: LocalDate): boolean {
  // Countdowns are events to wait for, not work: they live in their own list (and the calendar).
  if (isCountdown(t)) return filter === "countdowns";
  // Events (sleep, lunch) only take time in the calendar; there is nothing to do about them.
  if (t.isEvent) return false;
  const day = dueDay(t);
  switch (filter) {
    case "inbox": return t.listId === INBOX_ID;
    case "all": return true;
    case "countdowns": return false;
    case "completed": return isDone(t);
    case "today": return day != null && (day === today || (!isDone(t) && day < today));
    case "tomorrow": return day === plusDays(today, 1);
    case "week": return day != null && day < plusDays(today, 7) && (day >= today || !isDone(t));
  }
  if (filter.startsWith("list:")) return t.listId === filter.slice(5);
  if (filter.startsWith("tag:")) return tagIds.has(filter.slice(4));
  return false;
}

export type GroupKind = "OVERDUE" | "TODAY" | "TOMORROW" | "DAY" | "LATER" | "NO_DATE" | "PRIORITY" | "LIST" | "ALL" | "DONE";

export interface TaskGroup {
  kind: GroupKind;
  date: LocalDate | null;
  tasks: Task[];
  priority?: number;
  listId?: string;
  key: string;
}

export type TaskSort = "DATE" | "PRIORITY" | "LIST" | "TITLE" | "CREATED";

export const SORTS: { key: TaskSort; label: string }[] = [
  { key: "DATE", label: "По дате" },
  { key: "PRIORITY", label: "По приоритету" },
  { key: "LIST", label: "По спискам" },
  { key: "TITLE", label: "По названию" },
  { key: "CREATED", label: "Сначала новые" },
];

function cmp(a: number | null, b: number | null): number {
  if (a === b) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return a - b;
}

const taskOrder = (a: Task, b: Task) =>
  cmp(a.dueAt, b.dueAt) || b.priority - a.priority || a.sortOrder - b.sortOrder || a.createdAt - b.createdAt;

const collator = new Intl.Collator("ru", { sensitivity: "base" });

const group = (kind: GroupKind, tasks: Task[], extra: Partial<TaskGroup> = {}): TaskGroup => ({
  kind, date: null, tasks, ...extra, key: `${kind}:${extra.date ?? extra.priority ?? extra.listId ?? ""}`,
});

/**
 * Splits already-filtered tasks into TickTick-like sections, completed ones always at the bottom
 * (SmartLists.kt, groupTasks).
 */
export function groupTasks(filter: FilterKey, tasks: Task[], now: number, today: LocalDate, sort: TaskSort = "DATE", listOrder: Map<string, number> = new Map()): TaskGroup[] {
  const doneAt = (t: Task) => t.completedAt ?? t.updatedAt;
  const byList = (list: Task[]) => {
    const m = new Map<string, Task[]>();
    for (const t of list) m.set(t.listId, [...(m.get(t.listId) ?? []), t]);
    return [...m.entries()]
      .sort((a, b) => (listOrder.get(a[0]) ?? 1e9) - (listOrder.get(b[0]) ?? 1e9))
      .map(([listId, ts]) => group("LIST", ts, { listId }));
  };
  if (filter === "completed") {
    const done = [...tasks].sort((a, b) => doneAt(b) - doneAt(a));
    if (!done.length) return [];
    if (sort === "DATE") {
      const m = new Map<string, Task[]>();
      for (const t of done) {
        const d = localDateOf(doneAt(t));
        m.set(d, [...(m.get(d) ?? []), t]);
      }
      return [...m.entries()].map(([date, ts]) => group("DAY", ts, { date }));
    }
    if (sort === "LIST") return byList(done);
    return [group("DONE", done)];
  }
  const open = tasks.filter((t) => !isDone(t));
  const done = tasks.filter(isDone).sort((a, b) => doneAt(b) - doneAt(a));
  let groups: TaskGroup[];
  switch (sort) {
    case "DATE": groups = groupByDate(open, now, today); break;
    case "PRIORITY": {
      const m = new Map<number, Task[]>();
      for (const t of [...open].sort(taskOrder)) m.set(t.priority, [...(m.get(t.priority) ?? []), t]);
      groups = [...m.entries()].sort((a, b) => b[0] - a[0]).map(([priority, ts]) => group("PRIORITY", ts, { priority }));
      break;
    }
    case "LIST": groups = byList([...open].sort(taskOrder)); break;
    case "TITLE": groups = open.length ? [group("ALL", [...open].sort((a, b) => collator.compare(a.title.trim(), b.title.trim())))] : []; break;
    case "CREATED": groups = open.length ? [group("ALL", [...open].sort((a, b) => b.createdAt - a.createdAt))] : []; break;
  }
  return done.length ? [...groups, group("DONE", done)] : groups;
}

function groupByDate(open: Task[], now: number, today: LocalDate): TaskGroup[] {
  const order: GroupKind[] = ["OVERDUE", "TODAY", "TOMORROW", "DAY", "LATER", "NO_DATE"];
  const buckets = new Map<string, { kind: GroupKind; date: LocalDate | null; tasks: Task[] }>();
  for (const t of [...open].sort(taskOrder)) {
    const day = dueDay(t);
    let kind: GroupKind, date: LocalDate | null = null;
    if (day == null) kind = "NO_DATE";
    else if (isOverdue(t, now, today) || day < today) kind = "OVERDUE";
    else if (day === today) { kind = "TODAY"; date = day; }
    else if (day === plusDays(today, 1)) { kind = "TOMORROW"; date = day; }
    else if (day < plusDays(today, 7)) { kind = "DAY"; date = day; }
    else kind = "LATER";
    const key = `${kind}:${date ?? ""}`;
    if (!buckets.has(key)) buckets.set(key, { kind, date, tasks: [] });
    buckets.get(key)!.tasks.push(t);
  }
  return [...buckets.values()]
    .sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind) || (a.date ?? "").localeCompare(b.date ?? ""))
    .map((b) => group(b.kind, b.tasks, { date: b.date }));
}

// --- Length of a task at a time (Timeline.kt) ---

/**
 * How long a task with a time lasts (minutes), when it has a start before its end on the same day
 * or overnight; null for a moment in time, an all-day task or a span of a day or more.
 */
export function durationMinutes(t: Task): number | null {
  if (t.dueAt == null || t.startAt == null || t.isAllDay || t.startAt >= t.dueAt || t.dueAt - t.startAt >= 86_400_000) return null;
  return Math.floor((t.dueAt - t.startAt) / 60_000);
}

/** When a task with a time begins: its start when it lasts a while, else its due time. */
export const timeStart = (t: Task): number | null => (durationMinutes(t) != null ? t.startAt : t.dueAt);

// --- Repeats (Repeats.kt) ---

function taskZone(t: Task): string {
  return t.timeZone ?? zone();
}

/**
 * The same task moved to its next occurrence, or null when the series is over. Keeps the time of
 * day in the task's own zone; startAt shifts by the same amount as dueAt.
 */
export function nextOccurrence(t: Task, completedAt: number): Task | null {
  let r = parseRule(t.repeatRule);
  if (!r || t.dueAt == null) return null;
  const z = taskZone(t);
  const dueDate = localDateOf(t.dueAt, z);
  const dueTime = localTimeOf(t.dueAt, z);
  const base = t.repeatFrom === "COMPLETION" ? localDateOf(completedAt, z) : dueDate;
  let nextDate = nextAfter(r, base);
  if (!nextDate) return null;
  r = advanced(r);
  // Catch up an overdue series so completing it lands in the future, like TickTick.
  const today = todayDate();
  while (t.repeatFrom === "DUE" && nextDate < today) {
    nextDate = nextAfter(r, nextDate);
    if (!nextDate) return null;
    r = advanced(r);
  }
  const nextDue = epochAt(nextDate, dueTime, z);
  const shift = nextDue - t.dueAt;
  return { ...t, dueAt: nextDue, startAt: t.startAt != null ? t.startAt + shift : null, repeatRule: toRRule(r) };
}

export function repeatDescription(t: Task): string | null {
  const r = parseRule(t.repeatRule);
  return r ? describe(r) : null;
}

/** Occurrences a repeating event skips (Series.kt). */
export function skippedDays(t: Task): Set<LocalDate> {
  return new Set((t.exDates ?? "").split(",").map((s) => s.trim()).filter((s) => /^\d{4}-\d{2}-\d{2}$/.test(s)));
}

export const epochDay = toEpochDay;
