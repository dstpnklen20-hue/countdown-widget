import type { LocalDate } from "../core/date";
import { plusDays, toEpochDay } from "../core/date";
import { QuickAddParser } from "../core/quickAdd";
import { toRRule } from "../core/repeatRule";
import { type Due, allDayDue, dueDay, nowMinute, timedDue, todayDate } from "../domain/dates";
import { type SeriesScope, atOccurrence, deleteFromSeries, editSeries } from "../domain/series";
import { type FilterKey, nextOccurrence } from "../domain/tasks";
import {
  type CalendarLayer, type ChecklistItem, type Habit, INBOX_ID, PERSONAL_CALENDAR_ID, type Reminder, type Task, type TaskList,
  blank, newId, now,
} from "./model";

/** What a reminder is, without its identity: two equal ones are the same reminder. */
export interface ReminderSpec { offsetMinutes: number; kind: Reminder["kind"] }

/** Colour of the "Личное" calendar the app seeds (DatabaseSeeder.PERSONAL_CALENDAR_COLOR). */
const PERSONAL_CALENDAR_COLOR = 0xff039be5 | 0;
import type { Rows } from "./store";

/**
 * Everything the page changes, written the way the app's repositories write it
 * (data/TaskRepository.kt, HabitRepository.kt), so phones see the same rows they would make.
 */
export class Actions {
  constructor(private store: Rows) {}

  private maxSort(kind: "tasks" | "task_lists" | "habits" | "checklist_items", filter: (r: any) => boolean = () => true): number {
    let max = 0;
    for (const r of this.store.all(kind)) if (filter(r)) max = Math.max(max, (r as { sortOrder: number }).sortOrder);
    return max;
  }

  /**
   * The rows the app seeds on every device with the same ids (DatabaseSeeder.kt): an account used
   * on the web first gets them here.
   */
  ensureBasics(): void {
    this.store.seed("task_lists", blank.list({
      id: INBOX_ID, name: "Входящие", isInbox: 1, sortOrder: Number.MIN_SAFE_INTEGER, createdAt: 0, updatedAt: 0,
    }));
    this.store.seed("calendars", blank.calendar({
      id: PERSONAL_CALENDAR_ID, name: "Личное", color: PERSONAL_CALENDAR_COLOR, sortOrder: Number.MIN_SAFE_INTEGER, createdAt: 0, updatedAt: 0,
    }));
  }

  // --- Tasks ---

  createTask(init: Partial<Task>, tagNames: string[] = []): Task {
    const [task] = this.store.put("tasks", blank.task({ ...init, sortOrder: this.maxSort("tasks") + 1 }));
    if (tagNames.length) this.setTags(task.id, tagNames);
    return task;
  }

  /**
   * Quick add with natural-language parsing; tasks inherit list or date from the view they are
   * added in (TaskRepository.quickAdd).
   */
  quickAdd(text: string, filter: FilterKey): Task | null {
    if (!text.trim()) return null;
    const parsed = new QuickAddParser(todayDate(), nowMinute()).parse(text);
    const lists = this.store.live("task_lists");
    const listId = (parsed.listName && lists.find((l) => l.name.toLowerCase() === parsed.listName!.toLowerCase())?.id)
      ?? (filter.startsWith("list:") ? filter.slice(5) : INBOX_ID);
    let due: Due | null = parsed.date ? (parsed.time != null ? timedDue(parsed.date, parsed.time) : allDayDue(parsed.date)) : null;
    if (!due) {
      if (filter === "today" || filter === "week") due = allDayDue(todayDate());
      else if (filter === "tomorrow") due = allDayDue(plusDays(todayDate(), 1));
    }
    const filterTag = filter.startsWith("tag:") ? this.store.get("tags", filter.slice(4))?.name : undefined;
    const task = this.createTask(
      {
        title: parsed.title || text.trim(),
        listId,
        priority: parsed.priority ?? 0,
        dueAt: due?.at ?? null,
        isAllDay: due?.isAllDay ? 1 : 0,
        timeZone: due?.timeZone ?? null,
        repeatRule: parsed.repeat ? toRRule(parsed.repeat) : null,
        displayMode: filter === "countdowns" ? "COUNTDOWN" : "NORMAL",
      },
      [...new Set([...parsed.tags, ...(filterTag ? [filterTag] : [])])],
    );
    if (due && !due.isAllDay) this.addReminder(task.id, 0);
    return task;
  }

  update(task: Task): void {
    this.store.put("tasks", task);
  }

  /** A new date and time; a task that lasts a while starts then and keeps its length (TaskDetailViewModel.setDue). */
  setDue(task: Task, due: Due | null, keepLength: number | null): void {
    let next: Task;
    if (due && !due.isAllDay && keepLength != null) {
      next = { ...task, startAt: due.at, dueAt: due.at + keepLength * 60_000, isAllDay: 0, timeZone: due.timeZone };
    } else {
      next = { ...task, startAt: null, dueAt: due?.at ?? null, isAllDay: due?.isAllDay ? 1 : 0, timeZone: due?.timeZone ?? null };
    }
    this.update(next);
    // Like TickTick: giving a task a time switches on an "at time" reminder by default.
    if (due && !due.isAllDay && !this.reminders(task.id).length) this.addReminder(task.id, 0);
  }

  /**
   * Completing a repeating task keeps it open and moves it to the next occurrence; a completed copy
   * is stored for history (TaskRepository.setDone).
   */
  setDone(task: Task, done: boolean): () => void {
    const at = now();
    const back = () => this.update(task);
    if (!done) {
      this.update({ ...task, status: "OPEN", completedAt: null });
      return back;
    }
    const next = nextOccurrence(task, at);
    if (!next) {
      this.update({ ...task, status: "DONE", completedAt: at });
      return back;
    }
    const [copy] = this.store.put("tasks",
      { ...task, id: newId(), status: "DONE", completedAt: at, repeatRule: null, displayMode: "NORMAL", createdAt: at, updatedAt: at },
      next);
    const checked = this.checklist(task.id).filter((c) => c.checked);
    if (checked.length) this.store.put("checklist_items", ...checked.map((c) => ({ ...c, checked: 0 as const })));
    // Undo: the series goes back to this occurrence and the completed copy goes away.
    return () => {
      back();
      void this.store.purge("tasks", [copy.id]);
      if (checked.length) this.store.put("checklist_items", ...checked);
    };
  }

  /** Moves the task and its subtasks to the trash. */
  delete(task: Task): void {
    const rows = this.store.all("tasks").filter((t) => (t.id === task.id || t.parentId === task.id) && !t.deleted);
    this.store.put("tasks", ...rows.map((t) => ({ ...t, deleted: 1 as const })));
  }

  /** Back from the trash, with the subtasks deleted with it; into Inbox if its list is gone too. */
  restore(task: Task): void {
    const list = this.store.get("task_lists", task.listId);
    const lost = !list || !!list.deleted;
    const rows = this.store.all("tasks").filter((t) =>
      t.id === task.id || (t.parentId === task.id && t.deleted && t.updatedAt >= task.updatedAt - 5_000));
    this.store.put("tasks", ...rows.map((t) => ({ ...t, deleted: 0 as const, ...(lost ? { listId: INBOX_ID, sectionId: null } : {}) })));
  }

  async purge(tasks: Task[]): Promise<void> {
    const ids = new Set(tasks.map((t) => t.id));
    for (const t of this.store.all("tasks")) if (t.parentId && ids.has(t.parentId)) ids.add(t.id);
    await this.store.purge("tasks", [...ids]);
  }

  // --- Subtasks, checklist, tags, reminders ---

  addSubtask(parent: Task, title: string): void {
    this.createTask({ title, listId: parent.listId, parentId: parent.id });
  }

  checklist(taskId: string): ChecklistItem[] {
    return this.store.live("checklist_items").filter((c) => c.taskId === taskId).sort((a, b) => a.sortOrder - b.sortOrder);
  }

  addChecklistItem(taskId: string, title: string): void {
    this.store.put("checklist_items", blank.checklistItem({ taskId, title, sortOrder: this.maxSort("checklist_items", (c) => c.taskId === taskId) + 1 }));
  }

  updateChecklistItem(item: ChecklistItem): void {
    this.store.put("checklist_items", item);
  }

  deleteChecklistItem(item: ChecklistItem): void {
    this.store.put("checklist_items", { ...item, deleted: 1 });
  }

  tagNames(taskId: string): string[] {
    const links = this.store.live("task_tags").filter((l) => l.taskId === taskId);
    return links.map((l) => this.store.get("tags", l.tagId)).filter((t) => t && !t.deleted).map((t) => t!.name);
  }

  /** The task's tags become exactly [names] (created when new, matched without case). */
  setTags(taskId: string, names: string[]): void {
    const tags = this.store.live("tags");
    const wanted = new Set<string>();
    for (const raw of names) {
      const name = raw.trim().replace(/^#/, "").trim();
      if (!name) continue;
      let tag = tags.find((t) => t.name.toLowerCase() === name.toLowerCase());
      if (!tag) {
        [tag] = this.store.put("tags", blank.tag({ name }));
        tags.push(tag);
      }
      wanted.add(tag.id);
    }
    const links = this.store.all("task_tags").filter((l) => l.taskId === taskId);
    const changes = [];
    for (const l of links) {
      const keep = wanted.has(l.tagId);
      if (keep === !l.deleted) continue;
      changes.push({ ...l, deleted: keep ? 0 as const : 1 as const });
    }
    for (const id of wanted) if (!links.some((l) => l.tagId === id)) changes.push(blank.taskTag(taskId, id));
    if (changes.length) {
      this.store.put("task_tags", ...changes);
      const task = this.store.get("tasks", taskId);
      if (task) this.update(task);
    }
  }

  reminders(taskId: string): Reminder[] {
    return this.store.live("reminders").filter((r) => r.taskId === taskId);
  }

  addReminder(taskId: string, offsetMinutes: number, kind: Reminder["kind"] = "NOTIFY"): void {
    if (this.reminders(taskId).some((r) => r.offsetMinutes === offsetMinutes && r.absoluteAt == null)) return;
    this.store.put("reminders", blank.reminder({ taskId, offsetMinutes, kind }));
  }

  deleteReminder(r: Reminder): void {
    this.store.put("reminders", { ...r, deleted: 1 });
  }

  // --- Lists ---

  createList(name: string, color: number | null): TaskList {
    return this.store.put("task_lists", blank.list({ name, color, sortOrder: this.maxSort("task_lists") + 1 }))[0];
  }

  updateList(list: TaskList): void {
    this.store.put("task_lists", list);
  }

  /** Deletes the list with its tasks (Inbox stays). */
  deleteList(list: TaskList): void {
    if (list.isInbox) return;
    const tasks = this.store.all("tasks").filter((t) => t.listId === list.id && !t.deleted);
    this.store.put("tasks", ...tasks.map((t) => ({ ...t, deleted: 1 as const })));
    this.store.put("task_lists", { ...list, deleted: 1 });
  }

  // --- Events ---

  calendar(id: string | null): CalendarLayer | undefined {
    const c = this.store.get("calendars", id ?? PERSONAL_CALENDAR_ID);
    return c && !c.deleted ? c : undefined;
  }

  /** Reminders a new event gets from its calendar: before the start, or at the all-day time. */
  defaultReminders(calendarId: string | null, allDay: boolean): ReminderSpec[] {
    const cal = this.calendar(calendarId);
    const offset = allDay ? cal?.defaultAllDayReminder : cal?.defaultReminder;
    return offset != null ? [{ offsetMinutes: offset, kind: "NOTIFY" }] : [];
  }

  /** A new event gets its calendar's default reminder (TaskRepository.defaultReminders). */
  createEvent(event: Partial<Task>): Task {
    const task = this.createTask({ ...event, isEvent: 1 });
    for (const r of this.defaultReminders(task.calendarId, !!task.isAllDay)) this.addReminder(task.id, r.offsetMinutes, r.kind);
    return task;
  }

  /** Makes the task's reminders exactly [wanted]: removes the others, adds the missing ones. */
  setReminders(taskId: string, wanted: ReminderSpec[]): void {
    const current = this.reminders(taskId).filter((r) => r.absoluteAt == null);
    const same = (r: Reminder, w: ReminderSpec) => r.offsetMinutes === w.offsetMinutes && r.kind === w.kind;
    const gone = current.filter((r) => !wanted.some((w) => same(r, w)));
    if (gone.length) this.store.put("reminders", ...gone.map((r) => ({ ...r, deleted: 1 as const })));
    const added = wanted.filter((w, i) => !current.some((r) => same(r, w)) && wanted.findIndex((x) => x.offsetMinutes === w.offsetMinutes && x.kind === w.kind) === i);
    if (added.length) this.store.put("reminders", ...added.map((w) => blank.reminder({ taskId, offsetMinutes: w.offsetMinutes, kind: w.kind })));
  }

  /**
   * Saves an event from the editor (TaskRepository.saveEvent): a new one ([master] null), or the
   * occurrence of [master] due on [occurrence] for the [scope] the user chose. Rows the change
   * creates get [reminders]; a changed series gets them too. Returns the id of the row that now
   * holds the edited occurrence.
   */
  saveEvent(edited: Task, reminders: ReminderSpec[], master?: Task, occurrence?: LocalDate, scope: SeriesScope = "ALL"): string {
    if (!master) {
      const [created] = this.store.put("tasks", { ...edited, sortOrder: this.maxSort("tasks") + 1 });
      this.setReminders(created.id, reminders);
      return created.id;
    }
    const change = editSeries(master, occurrence ?? dueDay(master) ?? todayDate(), edited, scope, now());
    let resultId = edited.id;
    for (const row of change.save) {
      if (!this.store.get("tasks", row.id)) {
        this.store.put("tasks", { ...row, sortOrder: this.maxSort("tasks") + 1 });
        this.setReminders(row.id, reminders);
        resultId = row.id;
      } else {
        this.store.put("tasks", row);
        if (row.id === edited.id && (scope === "ALL" || master.repeatRule == null)) this.setReminders(row.id, reminders);
      }
    }
    if (master.repeatRule == null || scope === "ALL") resultId = master.id;
    return resultId;
  }

  /**
   * Deletes the occurrence of [master] due on [occurrence] for [scope] (TaskRepository.deleteEvent);
   * a deleted series takes the occurrences moved out of it along. Returns how to undo it.
   */
  deleteEvent(master: Task, occurrence: LocalDate, scope: SeriesScope): () => void {
    const change = deleteFromSeries(master, occurrence, scope);
    for (const row of change.save) this.store.put("tasks", row);
    const ids = new Set(change.delete);
    const rows = this.store.all("tasks").filter((t) => !t.deleted && (ids.has(t.id) || (t.seriesId != null && ids.has(t.seriesId))));
    if (rows.length) this.store.put("tasks", ...rows.map((t) => ({ ...t, deleted: 1 as const })));
    return () => {
      const back = rows.map((r) => ({ ...(this.store.get("tasks", r.id) ?? r), deleted: 0 as const }));
      if (back.length) this.store.put("tasks", ...back);
      if (change.save.length) this.store.put("tasks", { ...master, updatedAt: this.store.get("tasks", master.id)?.updatedAt ?? master.updatedAt });
    };
  }

  /** A copy of the task (an event as it is on [occurrence] when given), with its reminders and tags. */
  duplicate(task: Task, occurrence?: LocalDate): Task {
    const source = occurrence && task.repeatRule ? { ...atOccurrence(task, occurrence), repeatRule: null, exDates: null } : task;
    const at = now();
    const [copy] = this.store.put("tasks", {
      ...source, id: newId(), status: "OPEN", completedAt: null, seriesId: null, sortOrder: this.maxSort("tasks") + 1, createdAt: at, updatedAt: at,
    });
    this.setReminders(copy.id, this.reminders(task.id).map((r) => ({ offsetMinutes: r.offsetMinutes ?? 0, kind: r.kind })));
    const tags = this.tagNames(task.id);
    if (tags.length) this.setTags(copy.id, tags);
    return copy;
  }

  // --- Habits ---

  saveHabit(habit: Habit, isNew: boolean): void {
    this.store.put("habits", isNew ? { ...habit, sortOrder: this.maxSort("habits") + 1 } : habit);
  }

  deleteHabit(habit: Habit): void {
    this.store.put("habits", { ...habit, deleted: 1 });
  }

  /** The habit's count on [day]; check-ins are one per habit and day (the newest wins). */
  checkIn(habitId: string, day: LocalDate) {
    const epochDay = toEpochDay(day);
    return this.store.all("habit_checkins")
      .filter((c) => c.habitId === habitId && c.day === epochDay)
      .sort((a, b) => b.updatedAt - a.updatedAt)[0];
  }

  setCount(habitId: string, day: LocalDate, count: number): void {
    const existing = this.checkIn(habitId, day);
    this.store.put("habit_checkins", existing
      ? { ...existing, count, deleted: 0 }
      : blank.checkIn({ habitId, day: toEpochDay(day), count }));
  }

  // --- Focus ---

  recordFocus(startedAt: number, endedAt: number, taskId: string | null): void {
    this.store.put("focus_sessions", blank.focus({ startedAt, endedAt, taskId, kind: "FOCUS" }));
  }
}
