import { useEffect, useMemo, useRef, useState } from "react";
import {
  type LocalDate, dayOfWeek, formatTime, parts, plusDays, plusMonths, plusWeeks, plusYears, previousOrSame, withDayOfMonth,
} from "../core/date";
import { type CalendarLayer, cssColor, PERSONAL_CALENDAR_ID, type Task, argbOf } from "../data/model";
import { allDayDue, epochAt, nowMinute, timedDue } from "../domain/dates";
import { type CalendarEntry, MINUTES_PER_DAY, calendarEntries, isVirtual, russianHolidays } from "../domain/calendar";
import { type SeriesScope, SCOPES, shifted } from "../domain/series";
import { happens, isDone } from "../domain/tasks";
import { Dialog, Swatches } from "./common";
import { DEFAULT_HOUR_PX, EntryChip, MAX_HOUR_PX, MIN_HOUR_PX, TimeGrid, entryEnd, entryTitle, onColor } from "./TimeGrid";
import { useData, useLocal, useNow, useToday } from "./context";
import type { EventDraft } from "./EventEditor";
import { CALENDAR_COLORS, MONTHS_NOM, PRIORITY_COLORS, WEEKDAYS_SHORT, formatDay, formatFullDate, monthTitle, periodTitle, reminderLabel } from "./format";
import { Icon } from "./icons";
import { CalendarFile } from "./CalendarFile";

type Mode = "schedule" | "day" | "3days" | "week" | "month" | "year";
const MODES: { key: Mode; label: string }[] = [
  { key: "schedule", label: "Расписание" }, { key: "day", label: "День" }, { key: "3days", label: "3 дня" },
  { key: "week", label: "Неделя" }, { key: "month", label: "Месяц" }, { key: "year", label: "Год" },
];
const SCHEDULE_DAYS = 60;

/** Calendar choices kept in this browser, like the app's calendar settings (AppSettings.kt). */
export function useCalendarSettings() {
  const [hidden, setHidden] = useLocal<string[]>("cal.hidden", []);
  const [firstDay, setFirstDay] = useLocal<number>("cal.firstDay", 1);
  const [eventMinutes, setEventMinutes] = useLocal<number>("cal.eventMinutes", 60);
  const [defaultCalendar, setDefaultCalendar] = useLocal<string>("cal.defaultCalendar", PERSONAL_CALENDAR_ID);
  const [dimPast, setDimPast] = useLocal("cal.dimPast", true);
  const [weekends, setWeekends] = useLocal("cal.weekends", true);
  const [showDone, setShowDone] = useLocal("cal.showDone", true);
  const [showTasks, setShowTasks] = useLocal("calendarTasks", true);
  const [showHolidays, setShowHolidays] = useLocal("calendarHolidays", true);
  const [hourPx, setHourPx] = useLocal<number>("cal.hourPx", DEFAULT_HOUR_PX);
  return {
    hourPx, setHourPx, hidden, setHidden, firstDay, setFirstDay, eventMinutes, setEventMinutes, defaultCalendar, setDefaultCalendar,
    dimPast, setDimPast, weekends, setWeekends, showDone, setShowDone, showTasks, setShowTasks, showHolidays, setShowHolidays,
  };
}
type Settings = ReturnType<typeof useCalendarSettings>;

/** A block moved or stretched on the grid, waiting for "this one / following / all". */
interface PendingShift { entry: CalendarEntry; start: number; end: number }

export function CalendarView({ onOpen, onNewEvent, onMenu, onSearch, preview }: {
  onOpen: (t: Task, occurrence?: string) => void; onNewEvent: (d: EventDraft) => void; onMenu: () => void; onSearch: () => void;
  /** The new event while its editor is open: it stays visible in the calendar. */
  preview: Task | null;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow(60_000);
  const s = useCalendarSettings();
  const [mode, setMode] = useLocal<Mode>("calendarMode", "week");
  // Always opens on today, as on the phone: a week left open last time looks like a shifted calendar.
  const [anchor, setAnchor] = useState<LocalDate>(today);
  const shownToday = useRef(today);
  useEffect(() => {
    // Midnight passed with today on the screen: follow it.
    if (shownToday.current !== today && anchor === shownToday.current) setAnchor(today);
    shownToday.current = today;
  }, [today]); // eslint-disable-line react-hooks/exhaustive-deps
  const [layers, setLayers] = useState(false);
  const [pending, setPending] = useState<PendingShift | null>(null);
  const [menu, setMenu] = useState<{ entry: CalendarEntry; x: number; y: number } | null>(null);
  const [askDelete, setAskDelete] = useState<CalendarEntry | null>(null);
  const [scheduleDays, setScheduleDays] = useState(SCHEDULE_DAYS);

  const weekStart = (d: LocalDate) => previousOrSame(d, s.firstDay);
  const range = useMemo((): [LocalDate, LocalDate] => {
    switch (mode) {
      case "month": {
        const first = weekStart(withDayOfMonth(anchor, 1));
        return [first, plusDays(first, 41)];
      }
      case "week": { const st = weekStart(anchor); return [st, plusDays(st, 6)]; }
      case "3days": return [anchor, plusDays(anchor, 2)];
      case "day": return [anchor, anchor];
      case "schedule": return [anchor, plusDays(anchor, scheduleDays - 1)];
      case "year": { const y = `${parts(anchor)[0]}-01-01`; return [y, plusDays(plusYears(y, 1), -1)]; }
    }
  }, [mode, anchor, s.firstDay, scheduleDays]); // eslint-disable-line react-hooks/exhaustive-deps

  const calendars = store.live("calendars").sort((a, b) => a.sortOrder - b.sortOrder);
  const colorOf = useMemo(() => {
    const cals = new Map(store.all("calendars").map((c) => [c.id, c.color]));
    const lists = new Map(store.all("task_lists").map((l) => [l.id, l.color]));
    return (t: Task): string => cssColor(t.color)
      ?? (t.isEvent ? cssColor(cals.get(t.calendarId ?? PERSONAL_CALENDAR_ID)) ?? "var(--primary)"
        : cssColor(lists.get(t.listId)) ?? PRIORITY_COLORS[t.priority] ?? "var(--primary)");
  }, [store.version]); // eslint-disable-line react-hooks/exhaustive-deps

  const stored = useMemo(() => {
    const hidden = new Set(s.hidden);
    const tasks = store.live("tasks").filter((t) => t.dueAt != null && !t.parentId &&
      (t.isEvent ? !hidden.has(t.calendarId ?? PERSONAL_CALENDAR_ID) : happens(t) || (s.showTasks && (s.showDone || !isDone(t)))));
    const all = s.showHolidays ? [...tasks, ...russianHolidays(parts(today)[0] - 2)] : tasks;
    return calendarEntries(all, range[0], range[1]);
  }, [store.version, range, s.showTasks, s.showHolidays, s.showDone, s.hidden, today]); // eslint-disable-line react-hooks/exhaustive-deps
  // The event being created is added on top: the days it doesn't touch keep their lists, and their columns don't redraw.
  const entries = useMemo(() => {
    if (!preview) return stored;
    const merged = new Map(stored);
    for (const [day, list] of calendarEntries([preview], range[0], range[1])) merged.set(day, [...(merged.get(day) ?? []), ...list]);
    return merged;
  }, [stored, preview, range]);

  const shift = (n: number) => {
    switch (mode) {
      case "month": setAnchor(plusMonths(withDayOfMonth(anchor, 1), n)); break;
      case "week": setAnchor(plusWeeks(anchor, n)); break;
      case "3days": setAnchor(plusDays(anchor, 3 * n)); break;
      case "day": setAnchor(plusDays(anchor, n)); break;
      case "schedule": setAnchor(plusDays(anchor, 7 * n)); break;
      case "year": setAnchor(plusYears(withDayOfMonth(anchor, 1), n)); break;
    }
  };

  // Arrow keys move between periods.
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).closest("input, textarea, select, [role=dialog]")) return;
      if (e.key === "ArrowLeft") shift(-1);
      if (e.key === "ArrowRight") shift(1);
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  });

  // A swipe to the side on a touch screen does the same (the app's paging).
  const swipe = useRef<{ x: number; y: number } | null>(null);
  const onTouchStart = (e: React.TouchEvent) => { swipe.current = { x: e.touches[0].clientX, y: e.touches[0].clientY }; };
  const onTouchEnd = (e: React.TouchEvent) => {
    const start = swipe.current;
    swipe.current = null;
    if (!start || dragging.current) return;
    const dx = e.changedTouches[0].clientX - start.x, dy = e.changedTouches[0].clientY - start.y;
    if (Math.abs(dx) > 70 && Math.abs(dx) > 2 * Math.abs(dy)) shift(dx < 0 ? 1 : -1);
  };
  const dragging = useRef(false);

  const calendarIdForNew = calendars.some((c) => c.id === s.defaultCalendar) ? s.defaultCalendar : PERSONAL_CALENDAR_ID;
  const newEvent = (day: LocalDate, minute: number | null, endMinute?: number, x?: number) => {
    const start = minute != null ? timedDue(day, minute) : allDayDue(day);
    const calendarId = calendarIdForNew === PERSONAL_CALENDAR_ID ? null : calendarIdForNew;
    onNewEvent({
      task: null,
      x,
      init: minute != null
        ? { startAt: start.at, dueAt: endMinute != null ? epochAt(day, endMinute) : start.at + s.eventMinutes * 60_000, isAllDay: 0, timeZone: start.timeZone, calendarId }
        : { startAt: start.at, dueAt: start.at, isAllDay: 1, timeZone: start.timeZone, calendarId },
    });
  };
  const openEntry = (e: CalendarEntry) => !isVirtual(e.task) && onOpen(e.task, e.occurrence);
  const openDay = (d: LocalDate) => { setAnchor(d); setMode("day"); };
  const current = (e: CalendarEntry) => store.get("tasks", e.task.id) ?? e.task;
  const toggleEntry = (e: CalendarEntry) => {
    const t = current(e);
    const done = !isDone(t);
    const undo = actions.setDone(t, done);
    if (done) toast({ text: `«${t.title}» выполнена`, undo });
  };
  const openMenu = (e: CalendarEntry, ev: React.MouseEvent) => {
    ev.preventDefault();
    ev.stopPropagation();
    if (!isVirtual(e.task)) setMenu({ entry: e, x: ev.clientX, y: ev.clientY });
  };
  const removeEntry = (e: CalendarEntry, scope: SeriesScope) => {
    const t = current(e);
    if (t.isEvent) {
      toast({ text: "Событие удалено", undo: actions.deleteEvent(t, e.occurrence, scope) });
    } else {
      actions.delete(t);
      toast({ text: "Задача удалена", undo: () => actions.restore({ ...t, deleted: 1, updatedAt: Date.now() - 1000 }) });
    }
  };

  /** A block dropped somewhere else or stretched: moved by [start] minutes, its end by [end]. */
  const onShift = (entry: CalendarEntry, start: number, end: number) => {
    const t = store.get("tasks", entry.task.id) ?? entry.task;
    if (t.isEvent && t.repeatRule) {
      setPending({ entry, start, end });
      return;
    }
    if (entry.projected) {
      toast({ text: "Это будущий повтор задачи: перенесите саму задачу" });
      return;
    }
    const before = t;
    actions.update(shifted(t, entry.occurrence, start, end));
    toast({ text: `«${t.title}» перенесено`, undo: () => actions.update({ ...before, updatedAt: store.get("tasks", t.id)?.updatedAt ?? before.updatedAt }) });
  };
  const applyPending = (scope: SeriesScope) => {
    if (!pending) return;
    const master = store.get("tasks", pending.entry.task.id) ?? pending.entry.task;
    const reminders = actions.reminders(master.id).filter((r) => r.absoluteAt == null).map((r) => ({ offsetMinutes: r.offsetMinutes ?? 0, kind: r.kind }));
    actions.saveEvent(shifted(master, pending.entry.occurrence, pending.start, pending.end), reminders, master, pending.entry.occurrence, scope);
    setPending(null);
  };

  let days: LocalDate[] = [];
  if (mode === "day") days = [anchor];
  else if (mode === "3days") days = [0, 1, 2].map((i) => plusDays(anchor, i));
  else if (mode === "week") days = [0, 1, 2, 3, 4, 5, 6].map((i) => plusDays(range[0], i)).filter((d) => s.weekends || dayOfWeek(d) < 6);

  const title = mode === "year" ? String(parts(anchor)[0])
    : mode === "month" ? monthTitle(withDayOfMonth(anchor, 1), today)
      : mode === "schedule" ? monthTitle(anchor, today) : periodTitle(range[0], range[1], today);
  const todayShown = mode === "year" ? parts(today)[0] === parts(anchor)[0]
    : mode === "month" ? today.slice(0, 7) === anchor.slice(0, 7) : today >= range[0] && today <= range[1];
  const firstHour = mode === "month" || mode === "year" || mode === "schedule" || !days.includes(today) ? (days[0] ?? today) : today;

  return (
    <>
      <div className="main-head cal-toolbar">
        <button className="icon-btn desktop-only" title="Разделы" aria-label="Разделы" onClick={onMenu}><Icon name="menu" /></button>
        <h1 style={{ marginRight: 8 }}>{title}</h1>
        <button className="icon-btn" aria-label="Назад" onClick={() => shift(-1)}><Icon name="left" /></button>
        <button className={`btn ${todayShown ? "" : "away"}`} title={todayShown ? undefined : "Открыта другая неделя — вернуться к сегодня"} onClick={() => setAnchor(today)}>Сегодня</button>
        <button className="icon-btn" aria-label="Вперёд" onClick={() => shift(1)}><Icon name="right" /></button>
        <div className="spacer" />
        <button className="icon-btn" title="Поиск" aria-label="Поиск" onClick={onSearch}><Icon name="search" /></button>
        <div style={{ position: "relative" }}>
          <button className="icon-btn" title="Календари и вид" aria-label="Календари и вид" onClick={() => setLayers(!layers)}><Icon name="layers" /></button>
          {layers && <LayersMenu s={s} calendars={calendars} onClose={() => setLayers(false)} />}
        </div>
        <select className="field mobile-select" value={mode} aria-label="Вид" onChange={(e) => setMode(e.target.value as Mode)}>
          {MODES.map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
        </select>
        <div className="seg desktop-seg">
          {MODES.map((m) => <button key={m.key} className={mode === m.key ? "on" : ""} onClick={() => setMode(m.key)}>{m.label}</button>)}
        </div>
        <button className="btn primary" onClick={() => newEvent(firstHour, Math.min(nowMinute() - (nowMinute() % 60) + 60, 23 * 60))}>
          <Icon name="plus" size={18} /><span className="desktop-only-inline">Событие</span>
        </button>
      </div>
      <div className="main-body cal-body" style={{ paddingBottom: mode === "month" || mode === "year" || mode === "schedule" ? 40 : 0 }}
        onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
        {mode === "month" && (
          <MonthGrid first={range[0]} month={parts(withDayOfMonth(anchor, 1))[1]} today={today} now={now} entries={entries} colorOf={colorOf} dimPast={s.dimPast}
            onDay={openDay} onNew={(d, x) => newEvent(d, null, undefined, x)} onOpen={openEntry} onToggle={toggleEntry} onMenu={openMenu} />
        )}
        {mode === "year" && <YearView year={parts(anchor)[0]} firstDay={s.firstDay} today={today} entries={entries}
          onDay={openDay} onMonth={(m) => { setAnchor(m); setMode("month"); }} />}
        {mode === "schedule" && <ScheduleView from={anchor} days={scheduleDays} today={today} now={now} entries={entries} colorOf={colorOf} dimPast={s.dimPast}
          onOpen={openEntry} onMenu={openMenu} onDay={openDay} onMore={() => setScheduleDays(scheduleDays + SCHEDULE_DAYS)} />}
        {days.length > 0 && (
          <TimeGrid days={days} today={today} now={now} entries={entries} colorOf={colorOf} dimPast={s.dimPast} hourPx={s.hourPx} setHourPx={s.setHourPx} dragging={dragging}
            onDay={openDay} onNew={newEvent} onOpen={openEntry} onToggle={toggleEntry} onMenu={openMenu} onShift={onShift} />
        )}
      </div>
      {menu && (
        <EntryMenu x={menu.x} y={menu.y} onClose={() => setMenu(null)} items={[
          { label: "Открыть", icon: "edit", onClick: () => openEntry(menu.entry) },
          ...(!menu.entry.task.isEvent && !menu.entry.projected && menu.entry.task.displayMode !== "COUNTDOWN"
            ? [{ label: isDone(menu.entry.task) ? "Вернуть в работу" : "Выполнить", icon: "done", onClick: () => toggleEntry(menu.entry) }] : []),
          { label: "Копия", icon: "tasks", onClick: () => toast({ text: `Копия: «${actions.duplicate(current(menu.entry), menu.entry.occurrence).title}»` }) },
          {
            label: "Удалить", icon: "trash", danger: true,
            onClick: () => (menu.entry.task.isEvent && menu.entry.task.repeatRule ? setAskDelete(menu.entry) : removeEntry(menu.entry, "ALL")),
          },
        ]} />
      )}
      {askDelete && (
        <Dialog title={`Удалить «${askDelete.task.title}»`} onClose={() => setAskDelete(null)} actions={<button className="btn" onClick={() => setAskDelete(null)}>Отмена</button>}>
          <div className="menu" style={{ position: "static", boxShadow: "none" }}>
            {SCOPES.map((sc) => <button key={sc.key} className={sc.key === "ALL" ? "error-text" : undefined} onClick={() => { removeEntry(askDelete, sc.key); setAskDelete(null); }}>{sc.label}</button>)}
          </div>
        </Dialog>
      )}
      {pending && (
        <Dialog title="Перенести повторяющееся событие" onClose={() => setPending(null)} actions={<button className="btn" onClick={() => setPending(null)}>Отмена</button>}>
          <div className="menu" style={{ position: "static", boxShadow: "none" }}>
            {SCOPES.map((sc) => <button key={sc.key} onClick={() => applyPending(sc.key)}>{sc.label}</button>)}
          </div>
        </Dialog>
      )}
    </>
  );
}

/** The right-click menu of a block. */
function EntryMenu({ x, y, items, onClose }: { x: number; y: number; onClose: () => void; items: { label: string; icon: string; danger?: boolean; onClick: () => void }[] }) {
  useEffect(() => {
    const close = () => onClose();
    const key = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("mousedown", close);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("mousedown", close);
      window.removeEventListener("keydown", key);
    };
  }, [onClose]);
  return (
    <div className="menu" role="menu" style={{ position: "fixed", left: Math.min(x, window.innerWidth - 220), top: Math.min(y, window.innerHeight - 200) }}
      onMouseDown={(e) => e.stopPropagation()} onContextMenu={(e) => e.preventDefault()}>
      {items.map((it) => (
        <button key={it.label} role="menuitem" className={it.danger ? "error-text" : undefined} onClick={() => { onClose(); it.onClick(); }}>
          <Icon name={it.icon} size={18} />{it.label}
        </button>
      ))}
    </div>
  );
}

// --- Layers: calendars, what to show, settings ---

const TIMED_DEFAULTS = [null, 0, 10, 30, 60, 24 * 60];
const ALL_DAY_DEFAULTS = [null, 0, 24 * 60, 2 * 24 * 60, 7 * 24 * 60];

function LayersMenu({ s, calendars, onClose }: { s: Settings; calendars: CalendarLayer[]; onClose: () => void }) {
  const [editing, setEditing] = useState<CalendarLayer | "new" | null>(null);
  const [file, setFile] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (editing || file) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && onClose();
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [editing, file, onClose]);
  const toggle = (id: string) => s.setHidden(s.hidden.includes(id) ? s.hidden.filter((x) => x !== id) : [...s.hidden, id]);
  return (
    <div ref={ref} className="menu layers" style={{ right: 0, top: "100%" }}>
      <div className="side-title">Календари</div>
      {calendars.map((c) => (
        <div key={c.id} className="layer-row">
          <label className="row">
            <input type="checkbox" checked={!s.hidden.includes(c.id)} onChange={() => toggle(c.id)} style={{ accentColor: cssColor(c.color) }} />
            <span className="dot" style={{ background: cssColor(c.color) }} />{c.name}
          </label>
          <button className="icon-btn" aria-label={`Изменить «${c.name}»`} onClick={() => setEditing(c)}><Icon name="edit" size={16} /></button>
        </div>
      ))}
      <button onClick={() => setEditing("new")}><Icon name="plus" size={16} />Новый календарь</button>
      <div className="side-title">Показывать</div>
      <label className="row layer-check"><input type="checkbox" checked={s.showTasks} onChange={(e) => s.setShowTasks(e.target.checked)} />Задачи</label>
      <label className="row layer-check"><input type="checkbox" checked={s.showDone} onChange={(e) => s.setShowDone(e.target.checked)} />Выполненные задачи</label>
      <label className="row layer-check"><input type="checkbox" checked={s.showHolidays} onChange={(e) => s.setShowHolidays(e.target.checked)} />Праздники России</label>
      <label className="row layer-check"><input type="checkbox" checked={s.weekends} onChange={(e) => s.setWeekends(e.target.checked)} />Выходные в «Неделе»</label>
      <label className="row layer-check"><input type="checkbox" checked={s.dimPast} onChange={(e) => s.setDimPast(e.target.checked)} />Приглушать прошедшее</label>
      <div className="side-title">Настройки</div>
      <label className="row layer-check">Первый день недели
        <select className="field" style={{ width: "auto" }} value={s.firstDay} onChange={(e) => s.setFirstDay(Number(e.target.value))}>
          <option value={1}>Понедельник</option><option value={7}>Воскресенье</option><option value={6}>Суббота</option>
        </select>
      </label>
      <label className="row layer-check">Новое событие
        <select className="field" style={{ width: "auto" }} value={s.eventMinutes} onChange={(e) => s.setEventMinutes(Number(e.target.value))}>
          {[15, 30, 45, 60, 90, 120].map((m) => <option key={m} value={m}>{m} мин</option>)}
        </select>
      </label>
      <label className="row layer-check">Календарь новых
        <select className="field" style={{ width: "auto", maxWidth: 140 }} value={s.defaultCalendar} onChange={(e) => s.setDefaultCalendar(e.target.value)}>
          {calendars.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </label>
      <div className="row layer-check">Масштаб часа
        <span className="row">
          <button className="btn small" style={{ width: "auto" }} aria-label="Мельче" onClick={() => s.setHourPx(Math.max(s.hourPx - 8, MIN_HOUR_PX))}>−</button>
          <span>{s.hourPx}</span>
          <button className="btn small" style={{ width: "auto" }} aria-label="Крупнее" onClick={() => s.setHourPx(Math.min(s.hourPx + 8, MAX_HOUR_PX))}>+</button>
        </span>
      </div>
      <div className="muted small" style={{ padding: "0 10px 6px" }}>Или Ctrl + колёсико над сеткой</div>
      <button onClick={() => setFile(true)}><Icon name="sync" size={16} />Импорт и экспорт .ics</button>
      {editing && <CalendarDialog calendar={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {file && <CalendarFile onClose={() => setFile(false)} />}
    </div>
  );
}

function CalendarDialog({ calendar, onClose }: { calendar: CalendarLayer | null; onClose: () => void }) {
  const { actions, toast } = useData();
  const [name, setName] = useState(calendar?.name ?? "");
  const [color, setColor] = useState<number>(calendar?.color ?? argbOf(CALENDAR_COLORS[7]));
  const [timed, setTimed] = useState<number | null>(calendar ? calendar.defaultReminder : 30);
  const [allDay, setAllDay] = useState<number | null>(calendar ? calendar.defaultAllDayReminder : 24 * 60);
  const save = () => {
    if (!name.trim()) return;
    if (calendar) actions.updateCalendar({ ...calendar, name: name.trim(), color, defaultReminder: timed, defaultAllDayReminder: allDay });
    else actions.createCalendar(name.trim(), color, timed, allDay);
    onClose();
  };
  const remove = () => {
    if (!calendar || !confirm(`Удалить календарь «${calendar.name}»? Его события уйдут в корзину.`)) return;
    const undo = actions.deleteCalendar(calendar);
    onClose();
    toast({ text: `Календарь «${calendar.name}» удалён`, undo });
  };
  return (
    <Dialog title={calendar ? "Календарь" : "Новый календарь"} onClose={onClose} actions={<>
      {calendar && calendar.id !== PERSONAL_CALENDAR_ID && <button className="btn danger" onClick={remove}>Удалить</button>}
      <div className="spacer" />
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" disabled={!name.trim()} onClick={save}>Сохранить</button>
    </>}>
      <div className="form">
        <input className="field" autoFocus value={name} placeholder="Название" onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && save()} />
        <Swatches value={color} onChange={(c) => c != null && setColor(c)} allowNone={false} palette={CALENDAR_COLORS} />
        <label className="row">Уведомление для событий
          <select className="field" style={{ width: "auto" }} value={timed ?? ""} onChange={(e) => setTimed(e.target.value === "" ? null : Number(e.target.value))}>
            {TIMED_DEFAULTS.map((m) => <option key={String(m)} value={m ?? ""}>{m == null ? "Нет" : reminderLabel(m, false)}</option>)}
          </select>
        </label>
        <label className="row">Для событий на весь день
          <select className="field" style={{ width: "auto" }} value={allDay ?? ""} onChange={(e) => setAllDay(e.target.value === "" ? null : Number(e.target.value))}>
            {ALL_DAY_DEFAULTS.map((m) => <option key={String(m)} value={m ?? ""}>{m == null ? "Нет" : reminderLabel(m, true)}</option>)}
          </select>
        </label>
      </div>
    </Dialog>
  );
}

// --- Month, year, schedule ---

function MonthGrid({ first, month, today, now, entries, colorOf, dimPast, onDay, onNew, onOpen, onToggle, onMenu }: {
  first: LocalDate; month: number; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate, x: number) => void; onOpen: (e: CalendarEntry) => void;
  onToggle: (e: CalendarEntry) => void; onMenu: (e: CalendarEntry, ev: React.MouseEvent) => void;
}) {
  const cells = Array.from({ length: 42 }, (_, i) => plusDays(first, i));
  return (
    <div className="month" aria-label={MONTHS_NOM[month - 1]}>
      {cells.slice(0, 7).map((d) => <div key={d} className="dow">{WEEKDAYS_SHORT[dayOfWeek(d) - 1]}</div>)}
      {cells.map((d) => {
        const list = entries.get(d) ?? [];
        return (
          <div key={d} className={`cell ${parts(d)[1] !== month ? "other" : ""} ${d === today ? "today" : ""}`} onClick={(ev) => onNew(d, ev.clientX)}>
            <button className="num" onClick={(e) => { e.stopPropagation(); onDay(d); }}>{parts(d)[2]}</button>
            {list.slice(0, 4).map((e, i) => <EntryChip key={i} e={e} color={colorOf(e.task)} past={dimPast && entryEnd(e) < now} onOpen={onOpen} onToggle={onToggle} onMenu={onMenu} />)}
            {list.length > 4 && <button className="more-link" onClick={(ev) => { ev.stopPropagation(); onDay(d); }}>ещё {list.length - 4}</button>}
          </div>
        );
      })}
    </div>
  );
}

function YearView({ year, firstDay, today, entries, onDay, onMonth }: {
  year: number; firstDay: number; today: LocalDate; entries: Map<LocalDate, CalendarEntry[]>;
  onDay: (d: LocalDate) => void; onMonth: (first: LocalDate) => void;
}) {
  return (
    <div className="year">
      {MONTHS_NOM.map((name, i) => {
        const first = `${year}-${String(i + 1).padStart(2, "0")}-01`;
        const start = previousOrSame(first, firstDay);
        const cells = Array.from({ length: 42 }, (_, k) => plusDays(start, k));
        return (
          <div key={name} className="mini">
            <button className="mini-title" onClick={() => onMonth(first)}>{name}</button>
            <div className="mini-grid">
              {cells.slice(0, 7).map((d) => <span key={d} className="w">{WEEKDAYS_SHORT[dayOfWeek(d) - 1][0]}</span>)}
              {cells.map((d) => parts(d)[1] !== i + 1 ? <span key={d} /> : (
                <button key={d} className={`${d === today ? "today" : ""} ${entries.get(d)?.length ? "busy" : ""}`} onClick={() => onDay(d)}>{parts(d)[2]}</button>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** Days ahead as a list of their entries, like the app's «Расписание». */
function ScheduleView({ from, days, today, now, entries, colorOf, dimPast, onOpen, onMenu, onDay, onMore }: {
  from: LocalDate; days: number; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  onOpen: (e: CalendarEntry) => void; onMenu: (e: CalendarEntry, ev: React.MouseEvent) => void; onDay: (d: LocalDate) => void; onMore: () => void;
}) {
  const list = Array.from({ length: days }, (_, i) => plusDays(from, i)).filter((d) => entries.get(d)?.length || d === today);
  return (
    <div className="schedule">
      {list.map((d, i) => (
        <div key={d}>
          {(i === 0 || parts(list[i - 1])[1] !== parts(d)[1]) && <div className="sched-month">{MONTHS_NOM[parts(d)[1] - 1]} {parts(d)[0]}</div>}
          <div className={`sched-day ${d === today ? "today" : ""}`}>
            <button className="sched-date" onClick={() => onDay(d)} title={formatFullDate(d)}>
              <span className="w">{WEEKDAYS_SHORT[dayOfWeek(d) - 1]}</span>
              <span className="n">{parts(d)[2]}</span>
            </button>
            <div className="sched-list">
              {!(entries.get(d)?.length) && <div className="muted small" style={{ padding: "8px 0" }}>Ничего не запланировано</div>}
              {(entries.get(d) ?? []).map((e, k) => {
                const color = colorOf(e.task);
                const filled = happens(e.task);
                return (
                  <button key={k} className={`sched-entry ${isDone(e.task) && !e.task.isEvent ? "done" : ""}`} onClick={() => onOpen(e)} onContextMenu={(ev) => onMenu(e, ev)}
                    style={{ background: filled ? color : `color-mix(in srgb, ${color} 45%, var(--bg))`, color: filled ? onColor(color) : "var(--text)", opacity: dimPast && entryEnd(e) < now ? 0.55 : undefined }}>
                    <span className="t">{entryTitle(e)}</span>
                    <span className="small">{e.start == null ? "Весь день" : `${formatTime(e.start)}–${formatTime((e.end ?? e.start) % MINUTES_PER_DAY)}`}{e.task.location ? ` · ${e.task.location}` : ""}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      ))}
      <button className="btn ghost" style={{ margin: "12px auto", display: "flex" }} onClick={onMore}>Показать дальше</button>
      <p className="muted small" style={{ textAlign: "center" }}>По {formatDay(plusDays(from, days - 1), today).toLowerCase()}</p>
    </div>
  );
}
