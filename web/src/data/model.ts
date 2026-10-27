/**
 * Rows of the app's tables (app/schemas/.../7.json) exactly as they travel through sync: column
 * names as keys, flags as 0/1, times as epoch milliseconds, colours as signed ARGB ints.
 *
 * A phone replaces its whole row with the one it receives (RowStore.merge), so every write must
 * carry every column: edits start from the stored row, new rows from [blank]. When the app gets
 * a new column, add it here too.
 */
export type Flag = 0 | 1;

export interface Synced {
  id: string;
  createdAt: number;
  updatedAt: number;
  deleted: Flag;
}

export interface Folder extends Synced { name: string; sortOrder: number }

export interface TaskList extends Synced {
  name: string;
  color: number | null;
  folderId: string | null;
  sortOrder: number;
  viewMode: "LIST" | "KANBAN";
  isInbox: Flag;
}

export interface Section extends Synced { listId: string; name: string; sortOrder: number }

export type TaskStatus = "OPEN" | "DONE" | "WONT_DO";

export interface Task extends Synced {
  listId: string;
  sectionId: string | null;
  parentId: string | null;
  title: string;
  content: string;
  priority: number;
  status: TaskStatus;
  startAt: number | null;
  dueAt: number | null;
  isAllDay: Flag;
  timeZone: string | null;
  repeatRule: string | null;
  repeatFrom: "DUE" | "COMPLETION";
  completedAt: number | null;
  sortOrder: number;
  displayMode: "NORMAL" | "COUNTDOWN";
  isEvent: Flag;
  color: number | null;
  location: string | null;
  calendarId: string | null;
  exDates: string | null;
  seriesId: string | null;
  eventType: string | null;
  startZone: string | null;
  endZone: string | null;
}

export interface ChecklistItem extends Synced { taskId: string; title: string; checked: Flag; sortOrder: number }

export interface Tag extends Synced { name: string; color: number | null; parentId: string | null; sortOrder: number }

/** Keyed by taskId|tagId, no id of its own. */
export interface TaskTag { taskId: string; tagId: string; updatedAt: number; deleted: Flag }

export interface Reminder extends Synced {
  taskId: string;
  offsetMinutes: number | null;
  absoluteAt: number | null;
  snoozedUntil: number | null;
  kind: "NOTIFY" | "ALARM";
}

export interface FocusSession extends Synced {
  taskId: string | null;
  kind: "FOCUS" | "SHORT_BREAK" | "LONG_BREAK";
  startedAt: number;
  endedAt: number;
  durationMs: number;
}

export interface Habit extends Synced {
  name: string;
  emoji: string;
  color: number | null;
  /** "MO,WE,FR"; empty = every day. */
  days: string;
  goal: number;
  reminderMinute: number | null;
  archived: Flag;
  sortOrder: number;
}

/** [day] is an epoch day. */
export interface HabitCheckIn extends Synced { habitId: string; day: number; count: number }

export interface CalendarLayer extends Synced {
  name: string;
  color: number;
  defaultReminder: number | null;
  defaultAllDayReminder: number | null;
  sortOrder: number;
}

export const INBOX_ID = "inbox";
export const PERSONAL_CALENDAR_ID = "personal";

export interface Tables {
  folders: Folder;
  task_lists: TaskList;
  sections: Section;
  calendars: CalendarLayer;
  tasks: Task;
  checklist_items: ChecklistItem;
  tags: Tag;
  task_tags: TaskTag;
  reminders: Reminder;
  focus_sessions: FocusSession;
  habits: Habit;
  habit_checkins: HabitCheckIn;
}

export type Kind = keyof Tables;

/** Parents before children, as the app merges them. */
export const KINDS: Kind[] = [
  "folders", "task_lists", "sections", "calendars", "tasks", "checklist_items", "tags", "task_tags",
  "reminders", "focus_sessions", "habits", "habit_checkins",
];

export function idOf<K extends Kind>(kind: K, row: Tables[K]): string {
  return kind === "task_tags" ? `${(row as TaskTag).taskId}|${(row as TaskTag).tagId}` : (row as Synced).id;
}

export const newId = (): string => crypto.randomUUID();
export const now = (): number => Date.now();

/**
 * The stamp for an edit of a row stamped [previous]: now, but always later than [previous]. Sync
 * keeps the newer version, so an edit must beat the one it changes even when this computer's
 * clock is behind the phone that wrote it (Entities.kt, stampAfter).
 */
export const stampAfter = (previous: number): number => Math.max(now(), previous + 1);

function base(): Synced {
  const t = now();
  return { id: newId(), createdAt: t, updatedAt: t, deleted: 0 };
}

/** Complete new rows with the app's defaults (Entities.kt). */
export const blank = {
  folder: (init: Partial<Folder>): Folder => ({ ...base(), name: "", sortOrder: 0, ...init }),
  list: (init: Partial<TaskList>): TaskList => ({
    ...base(), name: "", color: null, folderId: null, sortOrder: 0, viewMode: "LIST", isInbox: 0, ...init,
  }),
  section: (init: Partial<Section> & { listId: string }): Section => ({ ...base(), name: "", sortOrder: 0, ...init }),
  task: (init: Partial<Task>): Task => ({
    ...base(), listId: INBOX_ID, sectionId: null, parentId: null, title: "", content: "", priority: 0, status: "OPEN",
    startAt: null, dueAt: null, isAllDay: 0, timeZone: null, repeatRule: null, repeatFrom: "DUE", completedAt: null,
    sortOrder: 0, displayMode: "NORMAL", isEvent: 0, color: null, location: null, calendarId: null, exDates: null,
    seriesId: null, eventType: null, startZone: null, endZone: null, ...init,
  }),
  checklistItem: (init: Partial<ChecklistItem> & { taskId: string }): ChecklistItem => ({
    ...base(), title: "", checked: 0, sortOrder: 0, ...init,
  }),
  tag: (init: Partial<Tag>): Tag => ({ ...base(), name: "", color: null, parentId: null, sortOrder: 0, ...init }),
  taskTag: (taskId: string, tagId: string): TaskTag => ({ taskId, tagId, updatedAt: now(), deleted: 0 }),
  reminder: (init: Partial<Reminder> & { taskId: string }): Reminder => ({
    ...base(), offsetMinutes: 0, absoluteAt: null, snoozedUntil: null, kind: "NOTIFY", ...init,
  }),
  focus: (init: Partial<FocusSession> & { startedAt: number; endedAt: number }): FocusSession => ({
    ...base(), taskId: null, kind: "FOCUS", durationMs: init.endedAt - init.startedAt, ...init,
  }),
  habit: (init: Partial<Habit>): Habit => ({
    ...base(), name: "", emoji: "✅", color: null, days: "", goal: 1, reminderMinute: null, archived: 0, sortOrder: 0, ...init,
  }),
  checkIn: (init: Partial<HabitCheckIn> & { habitId: string; day: number }): HabitCheckIn => ({ ...base(), count: 1, ...init }),
  calendar: (init: Partial<CalendarLayer>): CalendarLayer => ({
    ...base(), name: "", color: 0xff4285f4 | 0, defaultReminder: 30, defaultAllDayReminder: 24 * 60, sortOrder: 0, ...init,
  }),
};

/** "#rrggbb" for a signed ARGB int. */
export function cssColor(argb: number | null | undefined): string | undefined {
  if (argb == null) return undefined;
  return "#" + ((argb >>> 0) & 0xffffff).toString(16).padStart(6, "0");
}

/** Signed ARGB int for "#rrggbb", as the app stores colours. */
export function argbOf(css: string): number {
  return (0xff000000 | parseInt(css.slice(1), 16)) | 0;
}
