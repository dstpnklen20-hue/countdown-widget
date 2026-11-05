import { memo, useEffect, useMemo, useRef, useState } from "react";
import { type LocalDate, dayOfWeek, formatTime, parts, plusDays } from "../core/date";
import type { Task } from "../data/model";
import { type CalendarEntry, MINUTES_PER_DAY, isVirtual, layoutBlocks } from "../domain/calendar";
import { epochAt, localTimeOf } from "../domain/dates";
import { happens, isDone } from "../domain/tasks";
import { Check } from "./common";
import { WEEKDAYS_SHORT } from "./format";
import { Icon } from "./icons";
import { TASK_DRAG } from "./ArrangePanel";

/** Taps and drags on the grid land on quarter hours, as in the app. */
const SNAP = 15;
/** All-day entries shown per day before "+N". */
const ALL_DAY_ROWS = 2;
export const DEFAULT_HOUR_PX = 64;
export const MIN_HOUR_PX = 32;
export const MAX_HOUR_PX = 160;
/** The new event while its editor is open: drawn like an entry, not stored. */
export const PREVIEW_ID = "virtual:preview";

export function entryTitle(e: CalendarEntry): string {
  const t = e.parts > 1 ? `${e.task.title} (${e.part}/${e.parts})` : e.task.title;
  return e.task.eventType === "FOCUS" ? `◎ ${t}` : t;
}

const midnights = new Map<LocalDate, number>();
function midnight(day: LocalDate): number {
  let at = midnights.get(day);
  if (at == null) {
    if (midnights.size > 800) midnights.clear();
    at = epochAt(day, 0);
    midnights.set(day, at);
  }
  return at;
}

/** End of an entry's part on the clock, to dim what is over. */
export function entryEnd(e: CalendarEntry): number {
  if (e.start == null) return midnight(plusDays(e.date, 1));
  return midnight(e.date) + (e.end ?? e.start) * 60_000;
}

/** Text colour that reads on [css]: dark on yellow, white on purple (textOn in the app). */
export function onColor(css: string): string {
  if (!css.startsWith("#")) return "var(--on-primary)";
  const n = parseInt(css.slice(1), 16);
  const lum = 0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255);
  return lum > 165 ? "#1c1d21" : "#ffffff";
}

export function EntryChip({ e, color, past, onOpen, onToggle, onMenu }: {
  e: CalendarEntry; color: string; past?: boolean; onOpen: (e: CalendarEntry) => void;
  onToggle?: (e: CalendarEntry) => void; onMenu?: (e: CalendarEntry, ev: React.MouseEvent) => void;
}) {
  const t = e.task;
  const filled = happens(t);
  const done = isDone(t) && !t.isEvent;
  return (
    <div role="button" tabIndex={0} className={`chip-entry ${t.eventType === "AWAY" ? "away" : ""} ${t.id === PREVIEW_ID ? "draft" : ""}`}
      style={{
        ...(filled ? { background: color, color: onColor(color) } : { background: `color-mix(in srgb, ${color} 45%, var(--bg))`, color: "var(--text)" }),
        opacity: (past && filled) || done ? 0.55 : undefined,
      }}
      onClick={(ev) => { ev.stopPropagation(); onOpen(e); }} onKeyDown={(ev) => ev.key === "Enter" && onOpen(e)}
      onContextMenu={(ev) => onMenu?.(e, ev)} title={t.title}>
      {!filled && !e.projected && onToggle && <Check done={isDone(t)} priority={0} onToggle={() => onToggle(e)} />}
      {t.repeatRule && t.id !== PREVIEW_ID && <Icon name="repeat" size={11} />}
      <span className="chip-text">{e.start != null && !t.isAllDay ? `${formatTime(e.start)} ` : ""}{entryTitle(e)}</span>
    </div>
  );
}

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

/** What a block can do; these keep their identity, so a column redraws only when its own data changes. */
interface BlockActions {
  open: (e: CalendarEntry) => void;
  toggle: (e: CalendarEntry) => void;
  menu: (e: CalendarEntry, ev: React.MouseEvent) => void;
  press: (ev: React.PointerEvent, e: CalendarEntry, mode: Drag["mode"]) => void;
  /** A press on the empty part of a column. */
  pressEmpty: (ev: React.PointerEvent, day: LocalDate) => void;
  tapEmpty: (ev: React.MouseEvent, day: LocalDate) => void;
  dragOver: (ev: React.DragEvent, day: LocalDate) => void;
  drop: (ev: React.DragEvent, day: LocalDate) => void;
}

const EMPTY: CalendarEntry[] = [];
const wrap = (m: number) => ((m % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;

/** One day's blocks. What it draws depends only on these props, so moving the mouse elsewhere costs nothing. */
const DayColumn = memo(function DayColumn({ day, isToday, list, colorOf, dimPast, now, hourPx, held, ghost, draw, actions }: {
  day: LocalDate; isToday: boolean; list: CalendarEntry[]; colorOf: (t: Task) => string; dimPast: boolean; now: number; hourPx: number;
  held: CalendarEntry | null; ghost: Drag | null; draw: Draw | null; actions: BlockActions;
}) {
  const placed = useMemo(() => {
    const timed = list.filter((e) => e.start != null);
    return layoutBlocks(timed.map((e) => ({ item: e, start: e.start!, end: Math.max(e.end!, e.start! + 20) }))).sort((a, b) => a.depth - b.depth);
  }, [list]);
  return (
    <div className={`daycol ${isToday ? "today" : ""}`} onPointerDown={(ev) => actions.pressEmpty(ev, day)} onClick={(ev) => actions.tapEmpty(ev, day)}
      onDragOver={(ev) => actions.dragOver(ev, day)} onDrop={(ev) => actions.drop(ev, day)}>
      {Array.from({ length: 24 }, (_, h) => <div key={h} className="hourline" style={{ top: h * hourPx }} />)}
      {placed.map((p) => {
        const e = p.item;
        const t = e.task;
        const draft = t.id === PREVIEW_ID;
        const color = colorOf(t);
        const filled = happens(t);
        const done = isDone(t) && !t.isEvent;
        const width = 100 / p.lanes;
        const indent = Math.min(p.depth * 10, 40);
        const top = (p.start / 60) * hourPx;
        const height = Math.max(((p.end - p.start) / 60) * hourPx, 18);
        const fixed = isVirtual(t) || (e.projected && !t.isEvent);
        return (
          <div key={`${t.id}:${e.occurrence}:${e.part}`} className={`block ${filled ? "" : "task"} ${t.eventType === "AWAY" ? "away" : ""} ${draft ? "draft" : ""}`}
            style={{
              top, height, left: `calc(${p.lane * width}% + ${indent}px)`, width: `calc(${width}% - ${indent + 2}px)`,
              background: filled ? color : `color-mix(in srgb, ${color} 55%, var(--bg))`, color: filled ? onColor(color) : "var(--text)",
              borderLeftColor: color,
              // What is over fades, but a task not done yet stays visible: it still needs doing.
              opacity: held === e ? 0.35 : done || (e.projected && !filled) || (dimPast && filled && !draft && entryEnd(e) < now) ? 0.55 : undefined,
            }}
            onPointerDown={(ev) => actions.press(ev, e, "move")}
            onClick={(ev) => { ev.stopPropagation(); actions.open(e); }}
            onContextMenu={(ev) => actions.menu(e, ev)} title={t.title}>
            <div className="t">
              {!filled && !fixed && <Check done={isDone(t)} priority={0} onToggle={() => actions.toggle(e)} />}
              {t.repeatRule && !draft && <Icon name="repeat" size={11} />}
              <span>{entryTitle(e)}</span>
            </div>
            {height >= 30 && <div className="when">{formatTime(e.start!)}–{formatTime(e.end! % MINUTES_PER_DAY)}{t.location ? ` · ${t.location}` : ""}</div>}
            {!fixed && e.part === e.parts && (
              <div className="resize" onPointerDown={(ev) => actions.press(ev, e, "resize")} onClick={(ev) => ev.stopPropagation()} />
            )}
          </div>
        );
      })}
      {ghost && (() => {
        const s = ghost.mode === "move" ? ghost.entry.start! + ghost.dMin : ghost.entry.start!;
        const end = Math.max(ghost.entry.end! + ghost.dMin, s + SNAP);
        const color = colorOf(ghost.entry.task);
        return (
          <div className="block ghost" style={{ top: (s / 60) * hourPx, height: Math.max(((end - s) / 60) * hourPx, 18), left: 0, width: "calc(100% - 2px)", background: color, color: onColor(color) }}>
            <div className="t"><span>{ghost.entry.task.title}</span></div>
            <div className="when">{formatTime(wrap(s))}–{formatTime(wrap(end))}</div>
          </div>
        );
      })()}
      {draw && (
        <div className="block ghost" style={{ top: (draw.from / 60) * hourPx, height: ((draw.to - draw.from) / 60) * hourPx, left: 0, width: "calc(100% - 2px)", background: "var(--primary)", color: "var(--on-primary)" }}>
          <div className="when">{formatTime(draw.from)}–{formatTime(draw.to % MINUTES_PER_DAY)}</div>
        </div>
      )}
      {isToday && <div className="nowline" style={{ top: (localTimeOf(now) / 60) * hourPx }} />}
    </div>
  );
});

export function TimeGrid({ days, today, now, entries, colorOf, dimPast, hourPx, setHourPx, dragging, carrying, onDay, onNew, onOpen, onToggle, onMenu, onShift, onDropTask }: {
  days: LocalDate[]; today: LocalDate; now: number; entries: Map<LocalDate, CalendarEntry[]>; colorOf: (t: Task) => string; dimPast: boolean;
  hourPx: number; setHourPx: (n: number) => void; dragging: React.MutableRefObject<boolean>;
  /** The panel is open: the all-day row stays, as a place to drop a task. */
  carrying: boolean;
  onDay: (d: LocalDate) => void; onNew: (d: LocalDate, minute: number | null, end?: number, x?: number) => void; onOpen: (e: CalendarEntry) => void;
  onToggle: (e: CalendarEntry) => void; onMenu: (e: CalendarEntry, ev: React.MouseEvent) => void;
  onShift: (e: CalendarEntry, start: number, end: number) => void;
  /** A task dragged from the panel: to a time of [day], or (null) into its all-day row. */
  onDropTask: (taskId: string, day: LocalDate, minute: number | null) => void;
}) {
  const [dropAt, setDropAt] = useState<Draw | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const grid = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [draw, setDraw] = useState<Draw | null>(null);
  const [allDayOpen, setAllDayOpen] = useState(false);
  const lastPointer = useRef("mouse");
  const hourRef = useRef(hourPx);
  hourRef.current = hourPx;
  const setHour = useRef(setHourPx);
  setHour.current = setHourPx;

  // Open at the current hour (an hour above it).
  useEffect(() => {
    scroller.current?.scrollTo({ top: Math.max(0, (Math.floor(localTimeOf(now) / 60) - 1) * hourPx) });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Ctrl + wheel (a pinch on a trackpad) changes the scale, like a pinch on the phone; the hour under the pointer stays.
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const old = hourRef.current;
      const next = Math.min(Math.max(old + (e.deltaY < 0 ? 8 : -8), MIN_HOUR_PX), MAX_HOUR_PX);
      if (next === old) return;
      const y = e.clientY - el.getBoundingClientRect().top;
      const minute = ((el.scrollTop + y) / old) * 60;
      setHour.current(next);
      requestAnimationFrame(() => el.scrollTo({ top: (minute / 60) * next - y }));
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  }, []);

  const minuteAt = (clientY: number) => {
    const top = grid.current!.getBoundingClientRect().top;
    return Math.min(Math.max(Math.floor((((clientY - top) / hourRef.current) * 60) / SNAP) * SNAP, 0), MINUTES_PER_DAY - SNAP);
  };

  // While a block is held: follow the pointer, drop it on release. Only a real move is a drag; press and release is a click.
  useEffect(() => {
    if (!drag) return;
    const colWidth = (grid.current?.querySelector(".daycol") as HTMLElement | null)?.offsetWidth ?? 100;
    const index = days.indexOf(drag.entry.date);
    const move = (e: PointerEvent) => {
      const dMin = Math.round((((e.clientY - drag.y) / hourRef.current) * 60) / SNAP) * SNAP;
      const dDay = drag.mode === "move" ? Math.min(Math.max(Math.round((e.clientX - drag.x) / colWidth), -index), days.length - 1 - index) : 0;
      const moved = drag.moved || Math.abs(e.clientY - drag.y) > 4 || Math.abs(e.clientX - drag.x) > 4;
      if (moved) dragging.current = true;
      if (dMin !== drag.dMin || dDay !== drag.dDay || moved !== drag.moved) setDrag({ ...drag, dMin, dDay, moved });
    };
    const up = () => {
      const shift = drag.dDay * MINUTES_PER_DAY + drag.dMin;
      if (drag.moved && shift !== 0) onShift(drag.entry, drag.mode === "move" ? shift : 0, shift);
      setDrag(null);
      // The click that ends a drag comes right after this; it must not open the block.
      if (dragging.current) setTimeout(() => { dragging.current = false; }, 50);
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
    const up = (e: PointerEvent) => {
      onNew(draw.day, draw.from, draw.to > draw.from + SNAP ? draw.to : undefined, e.clientX);
      setDraw(null);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [draw]); // eslint-disable-line react-hooks/exhaustive-deps

  const hold = useRef<ReturnType<typeof setTimeout>>();
  const latest = useRef<BlockActions>(null as unknown as BlockActions);
  latest.current = {
    open: (e) => { if (!dragging.current) onOpen(e); },
    toggle: onToggle,
    menu: onMenu,
    /** Mouse: drag at once. Touch: hold still for a moment first, so a swipe still scrolls. */
    press: (e, entry, mode) => {
      // A press on a block is never a press on the empty column under it, even when the block can't be moved.
      e.stopPropagation();
      if (isVirtual(entry.task) || e.button !== 0 || (entry.projected && !entry.task.isEvent)) return;
      if ((e.target as HTMLElement).closest(".check")) return;
      const begin = () => {
        if (e.pointerType !== "mouse") {
          dragging.current = true;
          navigator.vibrate?.(20);
        }
        setDrag({ entry, mode, x: e.clientX, y: e.clientY, dMin: 0, dDay: 0, moved: e.pointerType !== "mouse" });
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
    },
    pressEmpty: (ev, day) => {
      lastPointer.current = ev.pointerType;
      if (ev.pointerType !== "mouse" || ev.button !== 0) return;
      const from = minuteAt(ev.clientY);
      setDraw({ day, from, to: from + SNAP });
    },
    // Touch: a tap is a new event at that time (the mouse draws one instead).
    tapEmpty: (ev, day) => {
      if (lastPointer.current !== "mouse") onNew(day, minuteAt(ev.clientY), undefined, ev.clientX);
    },
    dragOver: (ev, day) => {
      if (!ev.dataTransfer.types.includes(TASK_DRAG)) return;
      ev.preventDefault();
      ev.dataTransfer.dropEffect = "move";
      const from = minuteAt(ev.clientY);
      if (dropAt?.day !== day || dropAt.from !== from) setDropAt({ day, from, to: Math.min(from + 30, MINUTES_PER_DAY) });
    },
    drop: (ev, day) => {
      const id = ev.dataTransfer.getData(TASK_DRAG);
      if (!id) return;
      ev.preventDefault();
      setDropAt(null);
      onDropTask(id, day, minuteAt(ev.clientY));
    },
  };
  const actions = useMemo<BlockActions>(() => ({
    open: (e) => latest.current.open(e),
    toggle: (e) => latest.current.toggle(e),
    menu: (e, ev) => latest.current.menu(e, ev),
    press: (ev, e, mode) => latest.current.press(ev, e, mode),
    pressEmpty: (ev, day) => latest.current.pressEmpty(ev, day),
    tapEmpty: (ev, day) => latest.current.tapEmpty(ev, day),
    dragOver: (ev, day) => latest.current.dragOver(ev, day),
    drop: (ev, day) => latest.current.drop(ev, day),
  }), []);

  const allDay = days.map((d) => (entries.get(d) ?? EMPTY).filter((e) => e.start == null));
  const hasAllDay = allDay.some((l) => l.length);
  const hidden = allDay.reduce((n, l) => Math.max(n, l.length - ALL_DAY_ROWS), 0);
  const heldDay = drag?.moved ? days.indexOf(drag.entry.date) : -1;
  const dropIntoAllDay = (ev: React.DragEvent, day: LocalDate) => {
    const id = ev.dataTransfer.getData(TASK_DRAG);
    if (!id) return;
    ev.preventDefault();
    setDropAt(null);
    onDropTask(id, day, null);
  };
  const overAllDay = (ev: React.DragEvent) => {
    if (!ev.dataTransfer.types.includes(TASK_DRAG)) return;
    ev.preventDefault();
    setDropAt(null);
  };
  return (
    <div className="grid-wrap" onDragLeave={(ev) => !ev.currentTarget.contains(ev.relatedTarget as Node) && setDropAt(null)}>
      <div className="grid-head">
        <div className="gutter" />
        {days.map((d) => (
          <div key={d} className={`day ${d === today ? "today" : ""}`} onClick={() => onDay(d)}>
            <div className="w">{WEEKDAYS_SHORT[dayOfWeek(d) - 1]}</div>
            <div className="n">{parts(d)[2]}</div>
          </div>
        ))}
      </div>
      {(hasAllDay || carrying) && (
        <div className="allday-row">
          <div className="gutter">
            весь день
            {hidden > 0 && <button className="more-link" onClick={() => setAllDayOpen(!allDayOpen)}>{allDayOpen ? "свернуть" : `+${hidden}`}</button>}
          </div>
          {days.map((d, i) => (
            <div key={d} className="col" onClick={(ev) => onNew(d, null, undefined, ev.clientX)} onDragOver={overAllDay} onDrop={(ev) => dropIntoAllDay(ev, d)}>
              {(allDayOpen ? allDay[i] : allDay[i].slice(0, ALL_DAY_ROWS)).map((e) => (
                <EntryChip key={`${e.task.id}:${e.occurrence}:${e.part}`} e={e} color={colorOf(e.task)} past={dimPast && entryEnd(e) < now}
                  onOpen={onOpen} onToggle={onToggle} onMenu={onMenu} />
              ))}
              {!allDayOpen && allDay[i].length > ALL_DAY_ROWS && (
                <button className="more-link" onClick={(ev) => { ev.stopPropagation(); setAllDayOpen(true); }}>ещё {allDay[i].length - ALL_DAY_ROWS}</button>
              )}
            </div>
          ))}
        </div>
      )}
      <div className="grid-scroll" ref={scroller}>
        <div className="grid" style={{ height: hourPx * 24 }} ref={grid}>
          <div className="hours">
            {Array.from({ length: 23 }, (_, h) => <span key={h} style={{ top: (h + 1) * hourPx }}>{String(h + 1).padStart(2, "0")}:00</span>)}
          </div>
          {days.map((d, di) => (
            <DayColumn key={d} day={d} isToday={d === today} list={entries.get(d) ?? EMPTY} colorOf={colorOf} dimPast={dimPast} now={now} hourPx={hourPx}
              held={di === heldDay ? drag!.entry : null}
              ghost={drag?.moved && heldDay + drag.dDay === di ? drag : null}
              draw={draw?.day === d ? draw : dropAt?.day === d ? dropAt : null} actions={actions} />
          ))}
        </div>
      </div>
    </div>
  );
}
