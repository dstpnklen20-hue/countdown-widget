import { useEffect, useMemo, useRef, useState } from "react";
import {
  type LocalDate, dayOfWeek, formatTime, parts, plusDays, plusMonths, plusWeeks, plusYears, previousOrSame, withDayOfMonth,
} from "../core/date";
import { type CalendarLayer, cssColor, PERSONAL_CALENDAR_ID, type Task, argbOf } from "../data/model";
import { allDayDue, epochAt, localTimeOf, nowMinute, timedDue } from "../domain/dates";
import { type CalendarEntry, MINUTES_PER_DAY, calendarEntries, isVirtual, layoutBlocks, russianHolidays } from "../domain/calendar";
import { type SeriesScope, SCOPES, shifted } from "../domain/series";
import { happens, isDone } from "../domain/tasks";
import { Dialog, Swatches } from "./common";
import { useData, useLocal, useNow, useToday } from "./context";
import type { EventDraft } from "./EventEditor";
import { CALENDAR_COLORS, MONTHS_NOM, PRIORITY_COLORS, WEEKDAYS_SHORT, formatDay, formatFullDate, monthTitle, reminderLabel } from "./format";
import { Icon } from "./icons";
import { CalendarFile } from "./CalendarFile";

type Mode = "schedule" | "day" | "3days" | "week" | "month" | "year";
const MODES: { key: Mode; label: string }[] = [
  { key: "schedule", label: "Расписание" }, { key: "day", label: "День" }, { key: "3days", label: "3 дня" },
  { key: "week", label: "Неделя" }, { key: "month", label: "Месяц" }, { key: "year", label: "Год" },
];
const HOUR_PX = 48;
/** Taps and drags on the grid land on quarter hours, as in the app. */
const SNAP = 15;
/** All-day entries shown per day before "+N". */
const ALL_DAY_ROWS = 2;
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
  return {
    hidden, setHidden, firstDay, setFirstDay, eventMinutes, setEventMinutes, defaultCalendar, setDefaultCalendar,
    dimPast, setDimPast, weekends, setWeekends, showDone, setShowDone, showTasks, setShowTasks, showHolidays, setShowHolidays,
  };
}
type Settings = ReturnType<typeof useCalendarSettings>;

/** A block moved or stretched on the grid, waiting for "this one / following / all". */
interface PendingShift { entry: CalendarEntry; start: number; end: number }

export function CalendarView({ onOpen, onNewEvent, onMenu, onSearch }: {
  onOpen: (t: Task, occurrence?: string) => void; onNewEvent: (d: EventDraft) => void; onMenu: () => void; onSearch: () => void;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow(60_000);
  const s = useCalendarSettings();
  const [mode, setMode] = useLocal<Mode>("calendarMode", "week");
  const [anchor, setAnchor] = useLocal<LocalDate>("calendarDay", today);
  const [layers, setLayers] = useState(false);
  const [pending, setPending] = useState<PendingShift | null>(null);
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

  const entries = useMemo(() => {
    const hidden = new Set(s.hidden);
    const tasks = store.live("tasks").filter((t) => t.dueAt != null && !t.parentId &&
      (t.isEvent ? !hidden.has(t.calendarId ?? PERSONAL_CALENDAR_ID) : happens(t) || (s.showTasks && (s.showDone || !isDone(t)))));
    const all = s.showHolidays ? [...tasks, ...russianHolidays(parts(today)[0] - 2)] : tasks;
    return calendarEntries(all, range[0], range[1]);
  }, [store.version, range, s.showTasks, s.showHolidays, s.showDone, s.hidden, today]); // eslint-disable-line react-hooks/exhaustive-deps

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
  const newEvent = (day: LocalDate, minute: number | null, endMinute?: number) => {
    const start = minute != null ? timedDue(day, minute) : allDayDue(day);
    const calendarId = calendarIdForNew === PERSONAL_CALENDAR_ID ? null : calendarIdForNew;
    onNewEvent({
      task: null,
      init: minute != null
        ? { startAt: start.at, dueAt: endMinute != null ? epochAt(day, endMinute) : start.at + s.eventMinutes * 60_000, isAllDay: 0, timeZone: start.timeZone, calendarId }
        : { startAt: start.at, dueAt: start.at, isAllDay: 1, timeZone: start.timeZone, calendarId },
    });
  };
  const openEntry = (e: CalendarEntry) => !isVirtual(e.task) && onOpen(e.task, e.occurrence);
  const openDay = (d: LocalDate) => { setAnchor(d); setMode("day"); };

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
    : mode === "month" ? monthTitle(withDayOfMonth(anchor, 1), today) : monthTitle(range[0], today);
  const firstHour = mode === "month" || mode === "year" || mode === "schedule" || !days.includes(today) ? (days[0] ?? today) : today;

  return (
    <>
      <div className="main-head cal-toolbar">
        <button className="icon-btn desktop-only" title="Разделы" aria-label="Разделы" onClick={onMenu}><Icon name="menu" /></button>
        <h1 style={{ marginRight: 8 }}>{title}</h1>
        <button className="icon-btn" aria-label="Назад" onClick={() => shift(-1)}><Icon name="left" /></button>
        <button className="btn" onClick={() => setAnchor(today)}>Сегодня</button>
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
            onDay={openDay} onNew={(d) => newEvent(d, null)} onOpen={openEntry} />
        )}
        {mode === "year" && <YearView year={parts(anchor)[0]} firstDay={s.firstDay} today={today} entries={entries}
          onDay={openDay} onMonth={(m) => { setAnchor(m); setMode("month"); }} />}
        {mode === "schedule" && <ScheduleView from={anchor} days={scheduleDays} today={today} now={now} entries={entries} colorOf={colorOf} dimPast={s.dimPast}
          onOpen={openEntry} onDay={openDay} onMore={() => setScheduleDays(scheduleDays + SCHEDULE_DAYS)} />}
        {days.length > 0 && (
          <TimeGrid days={days} today={today} now={now} entries={entries} colorOf={colorOf} dimPast={s.dimPast} dragging={dragging}
            onDay={openDay} onNew={newEvent} onOpen={openEntry} onShift={onShift} />
        )}
      </div>
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

function entryTitle(e: CalendarEntry): string {
  const t = e.parts > 1 ? `${e.task.title} (${e.part}/${e.parts})` : e.task.title;
  return e.task.eventType === "FOCUS" ? `◎ ${t}` : t;
}

/** End of an entry's occurrence on the clock, to dim what is over. */
function entryEnd(e: CalendarEntry): number {
  if (e.start == null) return epochAt(plusDays(e.date, 1), 0);
  return epochAt(e.date, 0) + (e.end ?? e.start) * 60_000;
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

function MonthGrid({ first, month, today, now, entries, colorOf, dimPast, onDay, onNew, onOpen }: {
  first: LocalDate; month: number; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate) => void; onOpen: (e: CalendarEntry) => void;
}) {
  const cells = Array.from({ length: 42 }, (_, i) => plusDays(first, i));
  return (
    <div className="month" aria-label={MONTHS_NOM[month - 1]}>
      {cells.slice(0, 7).map((d) => <div key={d} className="dow">{WEEKDAYS_SHORT[dayOfWeek(d) - 1]}</div>)}
      {cells.map((d) => {
        const list = entries.get(d) ?? [];
        return (
          <div key={d} className={`cell ${parts(d)[1] !== month ? "other" : ""} ${d === today ? "today" : ""}`} onClick={() => onNew(d)}>
            <button className="num" onClick={(e) => { e.stopPropagation(); onDay(d); }}>{parts(d)[2]}</button>
            {list.slice(0, 4).map((e, i) => <EntryChip key={i} e={e} color={colorOf(e.task)} past={dimPast && entryEnd(e) < now} onOpen={onOpen} />)}
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
function ScheduleView({ from, days, today, now, entries, colorOf, dimPast, onOpen, onDay, onMore }: {
  from: LocalDate; days: number; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  onOpen: (e: CalendarEntry) => void; onDay: (d: LocalDate) => void; onMore: () => void;
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
                  <button key={k} className={`sched-entry ${isDone(e.task) && !e.task.isEvent ? "done" : ""}`} onClick={() => onOpen(e)}
                    style={{ background: filled ? color : `color-mix(in srgb, ${color} 18%, transparent)`, color: filled ? "#fff" : "var(--text)", opacity: dimPast && entryEnd(e) < now ? 0.55 : undefined }}>
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

function EntryChip({ e, color, past, onOpen }: { e: CalendarEntry; color: string; past?: boolean; onOpen: (e: CalendarEntry) => void }) {
  const t = e.task;
  const filled = happens(t);
  return (
    <button className={`chip-entry ${isDone(t) && !t.isEvent ? "done" : ""} ${t.eventType === "AWAY" ? "away" : ""}`}
      style={{ ...(filled ? { background: color, color: "#fff" } : { background: `color-mix(in srgb, ${color} 18%, transparent)`, color: "var(--text)" }), opacity: past ? 0.55 : undefined }}
      onClick={(ev) => { ev.stopPropagation(); onOpen(e); }} title={t.title}>
      {e.start != null && !t.isAllDay ? `${formatTime(e.start)} ` : ""}{entryTitle(e)}
    </button>
  );
}

// --- Day, 3 days, week: the time grid ---

interface Drag {
  entry: CalendarEntry;
  /** Move the whole block, or only its end. */
  mode: "move" | "resize";
  x: number;
  y: number;
  dMin: number;
  dDay: number;
  moved: boolean;
}

/** A range being drawn on an empty column with the mouse: a new event of that length. */
interface Draw { day: LocalDate; from: number; to: number }

function TimeGrid({ days, today, now, entries, colorOf, dimPast, dragging, onDay, onNew, onOpen, onShift }: {
  days: LocalDate[]; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  dragging: React.MutableRefObject<boolean>;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate, minute: number | null, end?: number) => void; onOpen: (e: CalendarEntry) => void;
  onShift: (e: CalendarEntry, start: number, end: number) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const grid = useRef<HTMLDivElement>(null);
  const nowMin = localTimeOf(now);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [draw, setDraw] = useState<Draw | null>(null);
  const [allDayOpen, setAllDayOpen] = useState(false);
  const lastPointer = useRef("mouse");
  // Open at the current hour (an hour above it).
  useEffect(() => {
    scroller.current?.scrollTo({ top: Math.max(0, (Math.floor(nowMin / 60) - 1) * HOUR_PX) });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const minuteAt = (clientY: number) => {
    const top = grid.current!.getBoundingClientRect().top;
    return Math.min(Math.max(Math.floor((((clientY - top) / HOUR_PX) * 60) / SNAP) * SNAP, 0), MINUTES_PER_DAY - SNAP);
  };

  // While a block is held: follow the pointer, drop it on release.
  useEffect(() => {
    if (!drag) return;
    const colWidth = (grid.current?.querySelector(".daycol") as HTMLElement | null)?.offsetWidth ?? 100;
    const index = days.indexOf(drag.entry.date);
    const move = (e: PointerEvent) => {
      const dMin = Math.round((((e.clientY - drag.y) / HOUR_PX) * 60) / SNAP) * SNAP;
      const dDay = drag.mode === "move" ? Math.min(Math.max(Math.round((e.clientX - drag.x) / colWidth), -index), days.length - 1 - index) : 0;
      const moved = drag.moved || Math.abs(e.clientY - drag.y) > 4 || Math.abs(e.clientX - drag.x) > 4;
      if (dMin !== drag.dMin || dDay !== drag.dDay || moved !== drag.moved) setDrag({ ...drag, dMin, dDay, moved });
    };
    const up = () => {
      const shift = drag.dDay * MINUTES_PER_DAY + drag.dMin;
      if (drag.moved && shift !== 0) onShift(drag.entry, drag.mode === "move" ? shift : 0, shift);
      setDrag(null);
      setTimeout(() => { dragging.current = false; });
    };
    const noScroll = (e: TouchEvent) => e.preventDefault();
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    window.addEventListener("touchmove", noScroll, { passive: false });
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
      window.removeEventListener("touchmove", noScroll);
    };
  }, [drag]); // eslint-disable-line react-hooks/exhaustive-deps

  // Drawing a new event on an empty column.
  useEffect(() => {
    if (!draw) return;
    const move = (e: PointerEvent) => {
      const to = Math.max(minuteAt(e.clientY) + SNAP, draw.from + SNAP);
      if (to !== draw.to) setDraw({ ...draw, to });
    };
    const up = () => {
      onNew(draw.day, draw.from, draw.to > draw.from + SNAP ? draw.to : undefined);
      setDraw(null);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [draw]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Mouse: drag at once. Touch: hold still for a moment first, so a swipe still scrolls. */
  const hold = useRef<ReturnType<typeof setTimeout>>();
  const startDrag = (e: React.PointerEvent, entry: CalendarEntry, mode: Drag["mode"]) => {
    if (entry.projected && !entry.task.isEvent) return;
    if (isVirtual(entry.task) || e.button !== 0) return;
    e.stopPropagation();
    const begin = () => {
      dragging.current = true;
      setDrag({ entry, mode, x: e.clientX, y: e.clientY, dMin: 0, dDay: 0, moved: e.pointerType !== "mouse" });
      if (e.pointerType !== "mouse") navigator.vibrate?.(20);
    };
    if (e.pointerType === "mouse") return begin();
    clearTimeout(hold.current);
    const x = e.clientX, y = e.clientY;
    hold.current = setTimeout(begin, 400);
    const cancel = (ev: PointerEvent) => {
      if (ev.type === "pointermove" && Math.hypot(ev.clientX - x, ev.clientY - y) < 6) return;
      clearTimeout(hold.current);
      window.removeEventListener("pointermove", cancel);
      window.removeEventListener("pointerup", cancel);
    };
    window.addEventListener("pointermove", cancel);
    window.addEventListener("pointerup", cancel);
  };

  const allDay = days.map((d) => (entries.get(d) ?? []).filter((e) => e.start == null));
  const hasAllDay = allDay.some((l) => l.length);
  const hidden = allDay.reduce((n, l) => Math.max(n, l.length - ALL_DAY_ROWS), 0);
  return (
    <div className="grid-wrap">
      <div className="grid-head">
        <div className="gutter" />
        {days.map((d) => (
          <div key={d} className={`day ${d === today ? "today" : ""}`} onClick={() => onDay(d)}>
            <div className="w">{WEEKDAYS_SHORT[dayOfWeek(d) - 1]}</div>
            <div className="n">{parts(d)[2]}</div>
          </div>
        ))}
      </div>
      {hasAllDay && (
        <div className="allday-row">
          <div className="gutter">
            весь день
            {hidden > 0 && <button className="more-link" onClick={() => setAllDayOpen(!allDayOpen)}>{allDayOpen ? "свернуть" : `+${hidden}`}</button>}
          </div>
          {days.map((d, i) => (
            <div key={d} className="col" onClick={() => onNew(d, null)}>
              {(allDayOpen ? allDay[i] : allDay[i].slice(0, ALL_DAY_ROWS)).map((e, j) => (
                <EntryChip key={j} e={e} color={colorOf(e.task)} past={dimPast && entryEnd(e) < now} onOpen={onOpen} />
              ))}
              {!allDayOpen && allDay[i].length > ALL_DAY_ROWS && (
                <button className="more-link" onClick={(ev) => { ev.stopPropagation(); setAllDayOpen(true); }}>ещё {allDay[i].length - ALL_DAY_ROWS}</button>
              )}
            </div>
          ))}
        </div>
      )}
      <div className="grid-scroll" ref={scroller}>
        <div className="grid" style={{ height: HOUR_PX * 24 }} ref={grid}>
          <div className="hours">
            {Array.from({ length: 23 }, (_, h) => <span key={h} style={{ top: (h + 1) * HOUR_PX }}>{String(h + 1).padStart(2, "0")}:00</span>)}
          </div>
          {days.map((d, di) => {
            const timed = (entries.get(d) ?? []).filter((e) => e.start != null);
            const placed = layoutBlocks(timed.map((e) => ({ item: e, start: e.start!, end: Math.max(e.end!, e.start! + 20) })))
              .sort((a, b) => a.depth - b.depth);
            const ghost = drag && drag.moved && days.indexOf(drag.entry.date) + drag.dDay === di ? drag : null;
            return (
              <div key={d} className={`daycol ${d === today ? "today" : ""}`}
                onPointerDown={(ev) => {
                  lastPointer.current = ev.pointerType;
                  if (ev.pointerType !== "mouse" || ev.button !== 0) return;
                  const from = minuteAt(ev.clientY);
                  setDraw({ day: d, from, to: from + SNAP });
                }}
                onClick={(ev) => {
                  // Touch: a tap is a new event at that time (the mouse draws one instead).
                  if (lastPointer.current === "mouse") return;
                  onNew(d, minuteAt(ev.clientY));
                }}>
                {Array.from({ length: 24 }, (_, h) => <div key={h} className="hourline" style={{ top: h * HOUR_PX }} />)}
                {placed.map((p, i) => {
                  const e = p.item;
                  const t = e.task;
                  const color = colorOf(t);
                  const filled = happens(t);
                  const width = 100 / p.lanes;
                  const indent = Math.min(p.depth * 10, 40);
                  const top = (p.start / 60) * HOUR_PX;
                  const height = Math.max(((p.end - p.start) / 60) * HOUR_PX, 18);
                  const held = drag?.entry === e;
                  return (
                    <div key={i} className={`block ${filled ? "" : "task"} ${isDone(t) && !t.isEvent ? "done" : ""} ${t.eventType === "AWAY" ? "away" : ""} ${held && drag.moved ? "held" : ""}`}
                      style={{
                        top, height, left: `calc(${p.lane * width}% + ${indent}px)`, width: `calc(${width}% - ${indent + 2}px)`,
                        background: filled ? color : `color-mix(in srgb, ${color} 22%, var(--bg))`,
                        color: filled ? "#fff" : "var(--text)", borderLeftColor: color,
                        opacity: held && drag.moved ? 0.35 : (e.projected && !filled) || (dimPast && entryEnd(e) < now) ? 0.6 : undefined,
                      }}
                      onPointerDown={(ev) => startDrag(ev, e, "move")}
                      onClick={(ev) => { ev.stopPropagation(); if (!dragging.current) onOpen(e); }} title={t.title}>
                      <div className="t">{entryTitle(e)}</div>
                      {height >= 34 && <div>{formatTime(e.start!)}–{formatTime(e.end! % MINUTES_PER_DAY)}{t.location ? ` · ${t.location}` : ""}</div>}
                      {!isVirtual(t) && !(e.projected && !t.isEvent) && e.part === e.parts && (
                        <div className="resize" onPointerDown={(ev) => startDrag(ev, e, "resize")} onClick={(ev) => ev.stopPropagation()} />
                      )}
                    </div>
                  );
                })}
                {ghost && (() => {
                  const s = ghost.mode === "move" ? ghost.entry.start! + ghost.dMin : ghost.entry.start!;
                  const end = Math.max(ghost.entry.end! + ghost.dMin, s + SNAP);
                  return (
                    <div className="block ghost" style={{ top: (s / 60) * HOUR_PX, height: Math.max(((end - s) / 60) * HOUR_PX, 18), left: 0, width: "calc(100% - 2px)",
                      background: colorOf(ghost.entry.task), color: "#fff" }}>
                      <div className="t">{ghost.entry.task.title}</div>
                      <div>{formatTime(((s % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY)}–{formatTime(((end % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY)}</div>
                    </div>
                  );
                })()}
                {draw && draw.day === d && (
                  <div className="block ghost" style={{ top: (draw.from / 60) * HOUR_PX, height: ((draw.to - draw.from) / 60) * HOUR_PX, left: 0, width: "calc(100% - 2px)", background: "var(--primary)", color: "#fff" }}>
                    <div>{formatTime(draw.from)}–{formatTime(draw.to % MINUTES_PER_DAY)}</div>
                  </div>
                )}
                {d === today && <div className="nowline" style={{ top: (nowMin / 60) * HOUR_PX }} />}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
