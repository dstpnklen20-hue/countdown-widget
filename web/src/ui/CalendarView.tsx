import { useEffect, useMemo, useRef } from "react";
import {
  type LocalDate, dayOfWeek, formatTime, parts, plusDays, plusMonths, plusWeeks, previousOrSame, withDayOfMonth,
} from "../core/date";
import { cssColor, PERSONAL_CALENDAR_ID, type Task } from "../data/model";
import { localTimeOf, nowMinute, timedDue, allDayDue } from "../domain/dates";
import { type CalendarEntry, MINUTES_PER_DAY, calendarEntries, isVirtual, layoutBlocks, russianHolidays } from "../domain/calendar";
import { happens, isDone } from "../domain/tasks";
import { useData, useLocal, useNow, useToday } from "./context";
import type { EventDraft } from "./EventEditor";
import { MONTHS_NOM, PRIORITY_COLORS, WEEKDAYS_SHORT, monthTitle } from "./format";
import { Icon } from "./icons";

type Mode = "month" | "week" | "3days" | "day";
const MODES: { key: Mode; label: string }[] = [
  { key: "day", label: "День" }, { key: "3days", label: "3 дня" }, { key: "week", label: "Неделя" }, { key: "month", label: "Месяц" },
];
const HOUR_PX = 48;
/** Taps on the grid land on the quarter hour under the pointer, as in the app. */
const SNAP = 15;

export function CalendarView({ onOpen, onNewEvent, onMenu }: {
  onOpen: (t: Task, occurrence?: string) => void; onNewEvent: (d: EventDraft) => void; onMenu: () => void;
}) {
  const { store } = useData();
  const today = useToday();
  const [mode, setMode] = useLocal<Mode>("calendarMode", "week");
  const [anchor, setAnchor] = useLocal<LocalDate>("calendarDay", today);
  const [showTasks, setShowTasks] = useLocal("calendarTasks", true);
  const [showHolidays, setShowHolidays] = useLocal("calendarHolidays", true);

  const range = useMemo((): [LocalDate, LocalDate] => {
    switch (mode) {
      case "month": {
        const first = previousOrSame(withDayOfMonth(anchor, 1), 1);
        return [first, plusDays(first, 41)];
      }
      case "week": { const s = previousOrSame(anchor, 1); return [s, plusDays(s, 6)]; }
      case "3days": return [anchor, plusDays(anchor, 2)];
      case "day": return [anchor, anchor];
    }
  }, [mode, anchor]);

  const colorOf = useMemo(() => {
    const calendars = new Map(store.all("calendars").map((c) => [c.id, c.color]));
    const lists = new Map(store.all("task_lists").map((l) => [l.id, l.color]));
    return (t: Task): string => cssColor(t.color)
      ?? (t.isEvent ? cssColor(calendars.get(t.calendarId ?? PERSONAL_CALENDAR_ID)) ?? "var(--primary)"
        : cssColor(lists.get(t.listId)) ?? PRIORITY_COLORS[t.priority] ?? "var(--primary)");
  }, [store.version]); // eslint-disable-line react-hooks/exhaustive-deps

  const entries = useMemo(() => {
    const tasks = store.live("tasks").filter((t) => t.dueAt != null && (happens(t) || showTasks));
    const all = showHolidays ? [...tasks, ...russianHolidays(parts(today)[0] - 2)] : tasks;
    return calendarEntries(all, range[0], range[1]);
  }, [store.version, range, showTasks, showHolidays, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const shift = (n: number) => {
    switch (mode) {
      case "month": setAnchor(plusMonths(withDayOfMonth(anchor, 1), n)); break;
      case "week": setAnchor(plusWeeks(anchor, n)); break;
      case "3days": setAnchor(plusDays(anchor, 3 * n)); break;
      case "day": setAnchor(plusDays(anchor, n)); break;
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

  const newEvent = (day: LocalDate, minute: number | null) => {
    const start = minute != null ? timedDue(day, minute) : allDayDue(day);
    onNewEvent({
      task: null,
      init: minute != null
        ? { startAt: start.at, dueAt: start.at + 3_600_000, isAllDay: 0, timeZone: start.timeZone }
        : { startAt: start.at, dueAt: start.at, isAllDay: 1, timeZone: start.timeZone },
    });
  };
  const openEntry = (e: CalendarEntry) => !isVirtual(e.task) && onOpen(e.task, e.occurrence);

  const days = mode === "day" ? [anchor] : mode === "3days" ? [0, 1, 2].map((i) => plusDays(anchor, i)) : mode === "week" ? [0, 1, 2, 3, 4, 5, 6].map((i) => plusDays(range[0], i)) : [];

  return (
    <>
      <div className="main-head cal-toolbar">
        <button className="icon-btn desktop-only" title="Разделы" aria-label="Разделы" onClick={onMenu}><Icon name="menu" /></button>
        <h1 style={{ marginRight: 8 }}>{mode === "month" ? monthTitle(withDayOfMonth(anchor, 1), today) : monthTitle(range[0], today)}</h1>
        <button className="icon-btn" aria-label="Назад" onClick={() => shift(-1)}><Icon name="left" /></button>
        <button className="btn" onClick={() => setAnchor(today)}>Сегодня</button>
        <button className="icon-btn" aria-label="Вперёд" onClick={() => shift(1)}><Icon name="right" /></button>
        <div className="spacer" />
        <label className="row small muted"><input type="checkbox" checked={showTasks} onChange={(e) => setShowTasks(e.target.checked)} />Задачи</label>
        <label className="row small muted"><input type="checkbox" checked={showHolidays} onChange={(e) => setShowHolidays(e.target.checked)} />Праздники</label>
        <div className="seg">
          {MODES.map((m) => <button key={m.key} className={mode === m.key ? "on" : ""} onClick={() => setMode(m.key)}>{m.label}</button>)}
        </div>
        <button className="btn primary" onClick={() => newEvent(mode === "month" || !days.includes(today) ? (mode === "month" ? today : days[0]) : today, Math.min(nowMinute() - (nowMinute() % 60) + 60, 23 * 60))}>
          <Icon name="plus" size={18} />Событие
        </button>
      </div>
      <div className="main-body" style={{ paddingBottom: mode === "month" ? 40 : 0, display: "flex", flexDirection: "column" }}>
        {mode === "month"
          ? <MonthGrid first={range[0]} month={parts(withDayOfMonth(anchor, 1))[1]} today={today} entries={entries} colorOf={colorOf}
              onDay={(d) => { setAnchor(d); setMode("day"); }} onNew={(d) => newEvent(d, null)} onOpen={openEntry} />
          : <TimeGrid days={days} today={today} entries={entries} colorOf={colorOf}
              onDay={(d) => { setAnchor(d); setMode("day"); }} onNew={newEvent} onOpen={openEntry} />}
      </div>
    </>
  );
}

function entryTitle(e: CalendarEntry): string {
  return e.parts > 1 ? `${e.task.title} (${e.part}/${e.parts})` : e.task.title;
}

function MonthGrid({ first, month, today, entries, colorOf, onDay, onNew, onOpen }: {
  first: LocalDate; month: number; today: LocalDate; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate) => void; onOpen: (e: CalendarEntry) => void;
}) {
  const cells = Array.from({ length: 42 }, (_, i) => plusDays(first, i));
  return (
    <div className="month" aria-label={MONTHS_NOM[month - 1]}>
      {WEEKDAYS_SHORT.map((w) => <div key={w} className="dow">{w}</div>)}
      {cells.map((d) => {
        const list = entries.get(d) ?? [];
        return (
          <div key={d} className={`cell ${parts(d)[1] !== month ? "other" : ""} ${d === today ? "today" : ""}`} onClick={() => onNew(d)}>
            <button className="num" onClick={(e) => { e.stopPropagation(); onDay(d); }}>{parts(d)[2]}</button>
            {list.slice(0, 4).map((e, i) => <EntryChip key={i} e={e} color={colorOf(e.task)} onOpen={onOpen} />)}
            {list.length > 4 && <div className="more-link">ещё {list.length - 4}</div>}
          </div>
        );
      })}
    </div>
  );
}

function EntryChip({ e, color, onOpen }: { e: CalendarEntry; color: string; onOpen: (e: CalendarEntry) => void }) {
  const t = e.task;
  const filled = happens(t);
  return (
    <button className={`chip-entry ${isDone(t) && !t.isEvent ? "done" : ""}`}
      style={filled ? { background: color, color: "#fff" } : { background: `color-mix(in srgb, ${color} 18%, transparent)`, color: "var(--text)" }}
      onClick={(ev) => { ev.stopPropagation(); onOpen(e); }} title={t.title}>
      {e.start != null && !t.isAllDay ? `${formatTime(e.start)} ` : ""}{entryTitle(e)}
    </button>
  );
}

function TimeGrid({ days, today, entries, colorOf, onDay, onNew, onOpen }: {
  days: LocalDate[]; today: LocalDate; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate, minute: number | null) => void; onOpen: (e: CalendarEntry) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const now = useNow(60_000);
  const nowMin = localTimeOf(now);
  // Open at the current hour (an hour above it).
  useEffect(() => {
    scroller.current?.scrollTo({ top: Math.max(0, (Math.floor(nowMin / 60) - 1) * HOUR_PX) });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const allDay = days.map((d) => (entries.get(d) ?? []).filter((e) => e.start == null));
  const hasAllDay = allDay.some((l) => l.length);
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
          <div className="gutter">весь день</div>
          {days.map((d, i) => (
            <div key={d} className="col" onClick={() => onNew(d, null)}>
              {allDay[i].map((e, j) => <EntryChip key={j} e={e} color={colorOf(e.task)} onOpen={onOpen} />)}
            </div>
          ))}
        </div>
      )}
      <div className="grid-scroll" ref={scroller}>
        <div className="grid" style={{ height: HOUR_PX * 24 }}>
          <div className="hours">
            {Array.from({ length: 23 }, (_, h) => <span key={h} style={{ top: (h + 1) * HOUR_PX }}>{String(h + 1).padStart(2, "0")}:00</span>)}
          </div>
          {days.map((d) => {
            const timed = (entries.get(d) ?? []).filter((e) => e.start != null);
            const placed = layoutBlocks(timed.map((e) => ({ item: e, start: e.start!, end: Math.max(e.end!, e.start! + 20) })))
              .sort((a, b) => a.depth - b.depth);
            return (
              <div key={d} className={`daycol ${d === today ? "today" : ""}`}
                onClick={(ev) => {
                  const y = ev.clientY - (ev.currentTarget as HTMLElement).getBoundingClientRect().top;
                  const minute = Math.min(Math.max(Math.floor((y / HOUR_PX) * 60 / SNAP) * SNAP, 0), MINUTES_PER_DAY - SNAP);
                  onNew(d, minute);
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
                  return (
                    <div key={i} className={`block ${filled ? "" : "task"} ${isDone(t) && !t.isEvent ? "done" : ""}`}
                      style={{
                        top, height, left: `calc(${p.lane * width}% + ${indent}px)`, width: `calc(${width}% - ${indent + 2}px)`,
                        background: filled ? color : `color-mix(in srgb, ${color} 22%, var(--bg))`,
                        color: filled ? "#fff" : "var(--text)", borderLeftColor: color, opacity: e.projected && !filled ? 0.6 : undefined,
                      }}
                      onClick={(ev) => { ev.stopPropagation(); onOpen(e); }} title={t.title}>
                      <div className="t">{entryTitle(e)}</div>
                      {height >= 34 && <div>{formatTime(e.start!)}–{formatTime(e.end! % MINUTES_PER_DAY)}{t.location ? ` · ${t.location}` : ""}</div>}
                    </div>
                  );
                })}
                {d === today && <div className="nowline" style={{ top: (nowMin / 60) * HOUR_PX }} />}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

