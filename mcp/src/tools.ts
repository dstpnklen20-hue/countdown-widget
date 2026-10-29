import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { type LocalDate, formatTime, parseTime, plusDays } from "../../web/src/core/date";
import { Actions } from "../../web/src/data/actions";
import { INBOX_ID, PERSONAL_CALENDAR_ID, type Task } from "../../web/src/data/model";
import { calendarEntries } from "../../web/src/domain/calendar";
import { allDayDue, dayOf, dueDay, localDateOf, localTimeOf, timedDue, todayDate, zone } from "../../web/src/domain/dates";
import { type FilterKey, durationMinutes, isDone, isOverdue, matches, repeatDescription } from "../../web/src/domain/tasks";
import type { NodeStore } from "./nodeStore";

/**
 * The tools Claude sees. Every write goes through the site's Actions, so rows are complete and
 * stamped like the app writes them (repeats, default reminders, the trash); then they are sent
 * before the answer.
 */

const PRIORITY_NAMES = ["нет", "низкий", "средний", "высокий"];
const SHORT = 8;

class UserError extends Error {}

const text = (s: string) => ({ content: [{ type: "text" as const, text: s }] });

const DATE = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "дата в виде ГГГГ-ММ-ДД");
const TIME = z.string().regex(/^\d{1,2}:\d{2}$/, "время в виде ЧЧ:ММ");

export function registerTools(server: McpServer, store: NodeStore) {
  const actions = new Actions(store);
  let basicsChecked = false;

  /** Every tool: catch up with the server first, answer, then send what changed. */
  const run = <A>(fn: (args: A) => string | Promise<string>) => async (args: A) => {
    try {
      await store.sync();
      if (!basicsChecked) {
        actions.ensureBasics();
        basicsChecked = true;
      }
      const out = await fn(args);
      await store.push();
      return text(out);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      return { ...text(e instanceof UserError ? message : `Ошибка: ${message}`), isError: true };
    }
  };

  // --- Helpers ---

  const lists = () => store.live("task_lists").sort((a, b) => a.sortOrder - b.sortOrder);
  const calendars = () => store.live("calendars").sort((a, b) => a.sortOrder - b.sortOrder);
  const listName = (id: string) => store.get("task_lists", id)?.name ?? "?";

  function listIdOf(name: string): string {
    const n = name.trim().replace(/^~/, "").toLowerCase();
    const found = lists().find((l) => l.name.toLowerCase() === n) ?? lists().find((l) => l.name.toLowerCase().includes(n));
    if (!found) throw new UserError(`Нет списка «${name}». Есть: ${lists().map((l) => l.name).join(", ")}.`);
    return found.id;
  }

  function calendarIdOf(name: string): string | null {
    const n = name.trim().toLowerCase();
    const found = calendars().find((c) => c.name.toLowerCase() === n) ?? calendars().find((c) => c.name.toLowerCase().includes(n));
    if (!found) throw new UserError(`Нет календаря «${name}». Есть: ${calendars().map((c) => c.name).join(", ")}.`);
    return found.id === PERSONAL_CALENDAR_ID ? null : found.id;
  }

  /** A task by its id or the start of it (as shown in answers). */
  function taskOf(id: string): Task {
    const key = id.trim().replace(/^\[|\]$/g, "");
    const exact = store.get("tasks", key);
    if (exact && !exact.deleted) return exact;
    const found = store.live("tasks").filter((t) => t.id.startsWith(key));
    if (key.length >= 4 && found.length === 1) return found[0];
    if (found.length > 1) throw new UserError(`Под «${id}» подходит несколько записей — укажите id длиннее.`);
    throw new UserError(`Нет задачи или события с id «${id}» (возможно, она удалена).`);
  }

  const tagNames = (t: Task) => actions.tagNames(t.id);

  function when(t: Task): string {
    if (t.dueAt == null) return "без даты";
    const day = dueDay(t)!;
    if (t.isAllDay) {
      const first = t.startAt != null && t.startAt < t.dueAt ? dayOf(t, t.startAt) : null;
      return first && first !== day ? `${first} – ${day} (весь день)` : `${day} (весь день)`;
    }
    const length = durationMinutes(t);
    if (t.startAt != null && t.startAt < t.dueAt && (t.isEvent || length != null)) {
      const from = localDateOf(t.startAt), to = localDateOf(t.dueAt);
      const range = `${formatTime(localTimeOf(t.startAt))}–${formatTime(localTimeOf(t.dueAt))}`;
      return from === to ? `${from} ${range}` : `${from} ${formatTime(localTimeOf(t.startAt))} – ${to} ${formatTime(localTimeOf(t.dueAt))}`;
    }
    return `${day} ${formatTime(localTimeOf(t.dueAt))}`;
  }

  function line(t: Task, withDate = true): string {
    const mark = t.isEvent ? "📅" : t.displayMode === "COUNTDOWN" ? "⏳" : isDone(t) ? "☑" : "☐";
    const bits = [`[${t.id.slice(0, SHORT)}] ${mark} ${t.title || "(без названия)"}`];
    if (withDate) bits.push(when(t));
    if (!t.isEvent) bits.push(`список «${listName(t.listId)}»`);
    else if (t.calendarId) bits.push(`календарь «${store.get("calendars", t.calendarId)?.name ?? "?"}»`);
    if (t.priority) bits.push(`приоритет ${PRIORITY_NAMES[t.priority] ?? t.priority}`);
    const tags = tagNames(t);
    if (tags.length) bits.push(tags.map((n) => `#${n}`).join(" "));
    const repeat = repeatDescription(t);
    if (repeat) bits.push(`повтор: ${repeat}`);
    if (t.location) bits.push(`📍 ${t.location}`);
    if (!isDone(t) && !t.isEvent && isOverdue(t, Date.now(), todayDate())) bits.push("ПРОСРОЧЕНО");
    return bits.join(" · ");
  }

  /** Date and time fields of a tool call → the task's startAt/dueAt (as the site's editors set them). */
  function timing(a: { date?: string; time?: string; end_time?: string; end_date?: string; all_day?: boolean }, isEvent: boolean): Partial<Task> {
    if (!a.date) {
      if (a.time || a.end_time || a.end_date) throw new UserError("Укажите date, если задаёте время.");
      return {};
    }
    const start = a.time != null ? parseTime(a.time) : null;
    if (a.time != null && start == null) throw new UserError(`Непонятное время «${a.time}».`);
    const end = a.end_time != null ? parseTime(a.end_time) : null;
    if (a.end_time != null && end == null) throw new UserError(`Непонятное время «${a.end_time}».`);
    const allDay = a.all_day ?? start == null;
    if (allDay) {
      const first = allDayDue(a.date);
      const lastDate = a.end_date && a.end_date > a.date ? a.end_date : a.date;
      const last = allDayDue(lastDate);
      return {
        startAt: lastDate !== a.date || isEvent ? first.at : null, dueAt: last.at, isAllDay: 1, timeZone: first.timeZone,
        startZone: null, endZone: null,
      };
    }
    const from = timedDue(a.date, start ?? 9 * 60);
    if (end == null && !a.end_date) {
      // An event without an end lasts an hour; a task is a moment in time.
      return isEvent
        ? { startAt: from.at, dueAt: from.at + 3_600_000, isAllDay: 0, timeZone: from.timeZone }
        : { startAt: null, dueAt: from.at, isAllDay: 0, timeZone: from.timeZone };
    }
    // An end at or before the start on the same day is the next morning (as in the app).
    let endDate: LocalDate = a.end_date ?? a.date;
    const endMinute = end ?? start ?? 0;
    if (!a.end_date && endMinute <= (start ?? 0)) endDate = plusDays(a.date, 1);
    const to = timedDue(endDate, endMinute);
    if (to.at <= from.at) throw new UserError("Конец раньше начала.");
    return { startAt: from.at, dueAt: to.at, isAllDay: 0, timeZone: from.timeZone };
  }

  // --- Reading ---

  server.registerTool("tiktak_overview", {
    title: "Списки, календари и теги",
    description: "Списки задач (с числом открытых задач), календари и теги аккаунта Tik Tak. Вызови, чтобы узнать точные названия перед созданием или переносом.",
    inputSchema: {},
    annotations: { readOnlyHint: true },
  }, run(() => {
    const open = store.live("tasks").filter((t) => !t.isEvent && !isDone(t) && t.displayMode !== "COUNTDOWN");
    const out = [`Аккаунт Tik Tak, пояс ${zone()}, сегодня ${todayDate()}.`, "", "Списки:"];
    for (const l of lists()) out.push(`- ${l.name}${l.isInbox ? " (Входящие)" : ""}: ${open.filter((t) => t.listId === l.id).length} открытых`);
    out.push("", "Календари (для событий):");
    for (const c of calendars()) out.push(`- ${c.name}`);
    const tags = store.live("tags").map((t) => `#${t.name}`);
    out.push("", `Теги: ${tags.length ? tags.join(" ") : "нет"}`);
    return out.join("\n");
  }));

  server.registerTool("tiktak_agenda", {
    title: "Расписание на дни",
    description:
      "Задачи и события Tik Tak по дням за период, как в календаре приложения: повторы разворачиваются, " +
      "многодневные события показаны на каждом дне. Если период включает сегодня, сверху — просроченные задачи. " +
      "По умолчанию — сегодня.",
    inputSchema: {
      from: DATE.optional().describe("Первый день, ГГГГ-ММ-ДД (по умолчанию сегодня)"),
      days: z.number().int().min(1).max(62).optional().describe("Сколько дней показать (по умолчанию 1)"),
      include_done: z.boolean().optional().describe("Показывать выполненные задачи (по умолчанию да)"),
    },
    annotations: { readOnlyHint: true },
  }, run((a: { from?: string; days?: number; include_done?: boolean }) => {
    const today = todayDate();
    const from = a.from ?? today;
    const to = plusDays(from, (a.days ?? 1) - 1);
    const tasks = store.live("tasks").filter((t) => a.include_done !== false || t.isEvent || !isDone(t));
    const byDay = calendarEntries(tasks, from, to);
    const out: string[] = [];
    if (from <= today && today <= to) {
      const overdue = store.live("tasks").filter((t) => !t.isEvent && t.displayMode !== "COUNTDOWN" && isOverdue(t, Date.now(), today) && dueDay(t)! < today);
      if (overdue.length) out.push("Просрочено:", ...overdue.map((t) => `  ${line(t)}`), "");
    }
    for (let d = from; d <= to; d = plusDays(d, 1)) {
      const entries = byDay.get(d) ?? [];
      out.push(`${d}${d === today ? " (сегодня)" : ""}:`);
      if (!entries.length) out.push("  —");
      for (const e of entries) {
        const time = e.start != null ? `${formatTime(e.start)}–${formatTime(e.end ?? e.start)} ` : e.task.isAllDay ? "весь день " : "";
        const part = e.parts > 1 ? ` (день ${e.part} из ${e.parts})` : "";
        const projected = e.projected ? " (повтор)" : "";
        out.push(`  ${time}${line(e.task, false)}${part}${projected}`);
      }
    }
    return out.join("\n");
  }));

  server.registerTool("tiktak_tasks", {
    title: "Задачи по разделу",
    description:
      "Задачи Tik Tak как в разделах приложения. filter: inbox (Входящие), today (сегодня и просроченные), tomorrow, " +
      "week (7 дней), all (все с датой и без), no_date, completed, countdowns (отсчёты). " +
      "Можно сузить до списка (list) или тега (tag). События сюда не входят — для них tiktak_agenda.",
    inputSchema: {
      filter: z.enum(["inbox", "today", "tomorrow", "week", "all", "no_date", "completed", "countdowns"]).optional()
        .describe("Раздел (по умолчанию all)"),
      list: z.string().optional().describe("Название списка"),
      tag: z.string().optional().describe("Название тега"),
      include_done: z.boolean().optional().describe("Добавить выполненные (по умолчанию нет, кроме completed)"),
      limit: z.number().int().min(1).max(500).optional().describe("Не больше стольких задач (по умолчанию 100)"),
    },
    annotations: { readOnlyHint: true },
  }, run((a: { filter?: string; list?: string; tag?: string; include_done?: boolean; limit?: number }) => {
    const today = todayDate();
    const filter = a.filter ?? "all";
    const tagId = a.tag ? store.live("tags").find((t) => t.name.toLowerCase() === a.tag!.replace(/^#/, "").toLowerCase())?.id : undefined;
    if (a.tag && !tagId) throw new UserError(`Нет тега «${a.tag}».`);
    const listId = a.list ? listIdOf(a.list) : undefined;
    const tagged = new Set(store.live("task_tags").filter((l) => l.tagId === tagId).map((l) => l.taskId));
    let tasks = store.live("tasks").filter((t) => {
      if (filter === "no_date") return !t.isEvent && t.displayMode !== "COUNTDOWN" && t.dueAt == null;
      return matches(filter as FilterKey, t, new Set(), today);
    });
    if (listId) tasks = tasks.filter((t) => t.listId === listId);
    if (tagId) tasks = tasks.filter((t) => tagged.has(t.id));
    if (filter !== "completed" && !a.include_done) tasks = tasks.filter((t) => !isDone(t));
    tasks.sort((x, y) => Number(isDone(x)) - Number(isDone(y)) || (x.dueAt ?? Infinity) - (y.dueAt ?? Infinity) || y.priority - x.priority);
    const limit = a.limit ?? 100;
    if (!tasks.length) return "Задач нет.";
    const shown = tasks.slice(0, limit).map((t) => line(t));
    if (tasks.length > limit) shown.push(`… и ещё ${tasks.length - limit}`);
    return shown.join("\n");
  }));

  server.registerTool("tiktak_search", {
    title: "Поиск",
    description: "Ищет задачи и события Tik Tak по словам в названии, описании, месте и тегах.",
    inputSchema: {
      query: z.string().min(1).describe("Что искать"),
      include_done: z.boolean().optional().describe("Искать и среди выполненных (по умолчанию да)"),
    },
    annotations: { readOnlyHint: true },
  }, run((a: { query: string; include_done?: boolean }) => {
    const words = a.query.toLowerCase().split(/\s+/).filter(Boolean);
    const found = store.live("tasks").filter((t) => {
      if (a.include_done === false && isDone(t) && !t.isEvent) return false;
      const hay = [t.title, t.content, t.location ?? "", ...tagNames(t)].join(" ").toLowerCase();
      return words.every((w) => hay.includes(w));
    }).sort((x, y) => Number(isDone(x)) - Number(isDone(y)) || (y.dueAt ?? 0) - (x.dueAt ?? 0));
    if (!found.length) return "Ничего не найдено.";
    return found.slice(0, 50).map((t) => line(t)).join("\n") + (found.length > 50 ? `\n… и ещё ${found.length - 50}` : "");
  }));

  server.registerTool("tiktak_get", {
    title: "Подробности",
    description: "Всё о задаче или событии Tik Tak: описание, подзадачи, чек-лист, напоминания.",
    inputSchema: { id: z.string().describe("id (можно первые 8 символов)") },
    annotations: { readOnlyHint: true },
  }, run((a: { id: string }) => {
    const t = taskOf(a.id);
    const out = [line(t), `id: ${t.id}`];
    if (t.content.trim()) out.push("", "Описание:", t.content.trim());
    const subtasks = store.live("tasks").filter((s) => s.parentId === t.id);
    if (subtasks.length) out.push("", "Подзадачи:", ...subtasks.map((s) => `  ${line(s)}`));
    const items = actions.checklist(t.id);
    if (items.length) out.push("", "Чек-лист:", ...items.map((c) => `  ${c.checked ? "☑" : "☐"} ${c.title}`));
    const reminders = actions.reminders(t.id).map((r) =>
      r.absoluteAt != null ? `в ${localDateOf(r.absoluteAt)} ${formatTime(localTimeOf(r.absoluteAt))}`
        : r.offsetMinutes ? `за ${r.offsetMinutes} мин` : "в момент начала");
    if (reminders.length) out.push("", `Напоминания: ${reminders.join(", ")}`);
    if (t.completedAt) out.push(`Выполнено: ${localDateOf(t.completedAt)} ${formatTime(localTimeOf(t.completedAt))}`);
    return out.join("\n");
  }));

  // --- Writing ---

  const priority = z.number().int().min(0).max(3).optional().describe("Приоритет: 0 нет, 1 низкий, 2 средний, 3 высокий");
  const repeat = z.string().optional().describe("Повтор в формате RRULE без префикса, напр. FREQ=DAILY, FREQ=WEEKLY;BYDAY=MO,WE, FREQ=MONTHLY;BYMONTHDAY=1");

  server.registerTool("tiktak_quick_add", {
    title: "Быстро добавить задачу",
    description:
      "Добавляет задачу Tik Tak одной фразой, как строка быстрого ввода в приложении: распознаёт дату и время, " +
      "повтор, !приоритет, #тег, ~список. Пример: «завтра в 10 позвонить маме !высокий #семья ~Личное», " +
      "«каждый понедельник в 9 планёрка».",
    inputSchema: { text: z.string().min(1).describe("Фраза по-русски или по-английски") },
  }, run((a: { text: string }) => {
    const t = actions.quickAdd(a.text, "inbox");
    if (!t) throw new UserError("Пустой текст.");
    return `Добавлено: ${line(store.get("tasks", t.id)!)}`;
  }));

  server.registerTool("tiktak_create_task", {
    title: "Создать задачу",
    description:
      "Создаёт задачу Tik Tak с точными полями. Без date — задача без даты; date без time — на весь день; " +
      "time — к определённому времени (с напоминанием в это время, как в приложении); time + end_time — задача с длительностью.",
    inputSchema: {
      title: z.string().min(1),
      notes: z.string().optional().describe("Описание"),
      list: z.string().optional().describe("Название списка (по умолчанию Входящие)"),
      date: DATE.optional(),
      time: TIME.optional(),
      end_time: TIME.optional(),
      priority,
      tags: z.array(z.string()).optional(),
      repeat,
      parent_id: z.string().optional().describe("Сделать подзадачей задачи с этим id"),
    },
  }, run((a: { title: string; notes?: string; list?: string; date?: string; time?: string; end_time?: string; priority?: number; tags?: string[]; repeat?: string; parent_id?: string }) => {
    const parent = a.parent_id ? taskOf(a.parent_id) : undefined;
    const t = actions.createTask({
      title: a.title.trim(), content: a.notes ?? "", listId: parent?.listId ?? (a.list ? listIdOf(a.list) : INBOX_ID),
      parentId: parent?.id ?? null, priority: a.priority ?? 0, repeatRule: a.repeat?.replace(/^RRULE:/i, "") || null,
      ...timing(a, false),
    }, a.tags ?? []);
    if (t.dueAt != null && !t.isAllDay) actions.addReminder(t.id, 0);
    return `Создано: ${line(store.get("tasks", t.id)!)}`;
  }));

  server.registerTool("tiktak_create_event", {
    title: "Создать событие",
    description:
      "Создаёт событие в календаре Tik Tak (встреча, сон, обед — без галочки «выполнено»). time без end_time — на час; " +
      "без time — на весь день (end_date — последний день многодневного). Напоминание ставится по умолчанию календаря.",
    inputSchema: {
      title: z.string().min(1),
      date: DATE,
      time: TIME.optional().describe("Начало"),
      end_time: TIME.optional().describe("Конец (раньше начала = на следующий день)"),
      end_date: DATE.optional().describe("День окончания, если не тот же"),
      all_day: z.boolean().optional(),
      calendar: z.string().optional().describe("Название календаря (по умолчанию Личное)"),
      location: z.string().optional(),
      notes: z.string().optional(),
      repeat,
    },
  }, run((a: { title: string; date: string; time?: string; end_time?: string; end_date?: string; all_day?: boolean; calendar?: string; location?: string; notes?: string; repeat?: string }) => {
    const t = actions.createEvent({
      title: a.title.trim(), content: a.notes ?? "", location: a.location?.trim() || null,
      calendarId: a.calendar ? calendarIdOf(a.calendar) : null, repeatRule: a.repeat?.replace(/^RRULE:/i, "") || null,
      ...timing(a, true),
    });
    return `Создано: ${line(store.get("tasks", t.id)!)}`;
  }));

  server.registerTool("tiktak_update", {
    title: "Изменить задачу или событие",
    description:
      "Меняет задачу или событие Tik Tak: передай только то, что меняется. Дата и время задаются вместе (date + time/end_time/end_date, " +
      "как при создании); clear_date убирает дату у задачи. У повторяющегося события меняется вся серия.",
    inputSchema: {
      id: z.string(),
      title: z.string().optional(),
      notes: z.string().optional(),
      list: z.string().optional().describe("Перенести в список"),
      calendar: z.string().optional().describe("Перенести в календарь (для событий)"),
      date: DATE.optional(),
      time: TIME.optional(),
      end_time: TIME.optional(),
      end_date: DATE.optional(),
      all_day: z.boolean().optional(),
      clear_date: z.boolean().optional(),
      priority,
      tags: z.array(z.string()).optional().describe("Новый полный набор тегов"),
      location: z.string().optional(),
      repeat: z.string().optional().describe("RRULE, или пустая строка — убрать повтор"),
    },
  }, run((a: { id: string; title?: string; notes?: string; list?: string; calendar?: string; date?: string; time?: string; end_time?: string; end_date?: string; all_day?: boolean; clear_date?: boolean; priority?: number; tags?: string[]; location?: string; repeat?: string }) => {
    const t = taskOf(a.id);
    let next: Task = { ...t };
    if (a.title != null) next.title = a.title.trim();
    if (a.notes != null) next.content = a.notes;
    if (a.list != null) next = { ...next, listId: listIdOf(a.list), sectionId: null };
    if (a.calendar != null) next.calendarId = calendarIdOf(a.calendar);
    if (a.priority != null) next.priority = a.priority;
    if (a.location != null) next.location = a.location.trim() || null;
    if (a.repeat != null) next.repeatRule = a.repeat.replace(/^RRULE:/i, "") || null;
    if (a.clear_date) {
      if (t.isEvent) throw new UserError("У события нельзя убрать дату — удалите его или перенесите.");
      next = { ...next, startAt: null, dueAt: null, isAllDay: 0, timeZone: null };
    } else if (a.date) {
      next = { ...next, ...timing(a, !!t.isEvent) };
    } else if (a.time || a.end_time || a.end_date) {
      // Only the time changes: keep the day the entry is on now.
      const day = t.dueAt != null ? (t.startAt != null && t.startAt < t.dueAt ? dayOf(t, t.startAt) : dueDay(t)!) : null;
      if (!day) throw new UserError("У задачи нет даты — укажите date.");
      next = { ...next, ...timing({ ...a, date: day }, !!t.isEvent) };
    }
    actions.update(next);
    if (a.tags) actions.setTags(t.id, a.tags);
    if (next.dueAt != null && !next.isAllDay && t.dueAt !== next.dueAt && !t.isEvent && !actions.reminders(t.id).length) actions.addReminder(t.id, 0);
    return `Изменено: ${line(store.get("tasks", t.id)!)}`;
  }));

  server.registerTool("tiktak_complete", {
    title: "Отметить выполненной",
    description: "Отмечает задачу Tik Tak выполненной (повторяющаяся переходит к следующему разу) или снова открытой (done=false).",
    inputSchema: { id: z.string(), done: z.boolean().optional().describe("По умолчанию true") },
  }, run((a: { id: string; done?: boolean }) => {
    const t = taskOf(a.id);
    if (t.isEvent) throw new UserError("Это событие — у событий нет отметки «выполнено».");
    actions.setDone(t, a.done !== false);
    const after = store.get("tasks", t.id)!;
    if (a.done !== false && !isDone(after)) return `Повторяющаяся задача выполнена, следующий раз: ${line(after)}`;
    return `${a.done === false ? "Снова открыта" : "Выполнено"}: ${line(after)}`;
  }));

  server.registerTool("tiktak_delete", {
    title: "Удалить в корзину",
    description:
      "Перемещает задачу (с подзадачами) или событие Tik Tak в корзину — оттуда его можно вернуть в приложении. " +
      "Для повторяющегося события можно удалить один день: occurrence = ГГГГ-ММ-ДД.",
    inputSchema: {
      id: z.string(),
      occurrence: DATE.optional().describe("Только этот день повторяющегося события"),
    },
    annotations: { destructiveHint: true },
  }, run((a: { id: string; occurrence?: string }) => {
    const t = taskOf(a.id);
    if (a.occurrence) {
      if (!t.isEvent || !t.repeatRule) throw new UserError("occurrence — только для повторяющихся событий.");
      actions.deleteEvent(t, a.occurrence, true);
      return `Пропущен день ${a.occurrence} в серии: ${line(t, false)}`;
    }
    if (t.isEvent) actions.deleteEvent(t, dueDay(t) ?? todayDate(), false);
    else actions.delete(t);
    return `В корзине: ${line(t)}`;
  }));

  server.registerTool("tiktak_checklist_add", {
    title: "Пункт чек-листа",
    description: "Добавляет пункты в чек-лист задачи Tik Tak.",
    inputSchema: { id: z.string(), items: z.array(z.string().min(1)).min(1) },
  }, run((a: { id: string; items: string[] }) => {
    const t = taskOf(a.id);
    for (const item of a.items) actions.addChecklistItem(t.id, item.trim());
    return `Добавлено пунктов: ${a.items.length} → ${line(t, false)}`;
  }));
}
