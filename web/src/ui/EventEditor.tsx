import { useEffect, useRef, useState } from "react";
import { type LocalDate, daysBetween, formatTime, parseTime, plusDays } from "../core/date";
import type { ReminderSpec } from "../data/actions";
import { PERSONAL_CALENDAR_ID, type Task, blank, cssColor } from "../data/model";
import { allDayDue, dayOf, dueDay, localTimeOf, timedDue, todayDate } from "../domain/dates";
import { type SeriesScope, SCOPES, atOccurrence } from "../domain/series";
import { repeatDescription } from "../domain/tasks";
import { MenuButton, Swatches } from "./common";
import { useData, useToday } from "./context";
import { CALENDAR_COLORS, REMINDER_PRESETS, formatEventSpan, reminderLabel } from "./format";
import { EntryCard } from "./EntryCard";
import { Icon } from "./icons";
import { RepeatPicker } from "./RepeatPicker";
import { copyTaskLink } from "./TaskDetail";
import { PREVIEW_ID } from "./TimeGrid";

/** An event to edit ([task]) or to create (from [init]); [occurrence] is the day of a repeat that was clicked. */
export interface EventDraft {
  task: Task | null;
  init?: Partial<Task>;
  occurrence?: LocalDate;
  /** What was clicked: the card opens beside it. A new event opens beside its own block. */
  anchor?: DOMRect;
}

interface Span { startDate: LocalDate; start: number; endDate: LocalDate; end: number; allDay: boolean }

function spanOf(t: Task): Span {
  const due = t.dueAt ?? timedDue(todayDate(), 10 * 60).at;
  const start = t.startAt != null && t.startAt <= due ? t.startAt : due;
  const allDay = !!t.isAllDay;
  return {
    startDate: dayOf(t, start), start: allDay ? 9 * 60 : localTimeOf(start),
    endDate: dayOf(t, due), end: allDay ? 10 * 60 : localTimeOf(due), allDay,
  };
}

/** Like EventTime in the app: moving the start keeps the length; an end before the start is the next morning. */
function withStart(s: Span, date: LocalDate, time: number): Span {
  const length = daysBetween(s.startDate, s.endDate) * 1440 + (s.end - s.start);
  const total = time + Math.max(length, 0);
  return { ...s, startDate: date, start: time, endDate: plusDays(date, Math.floor(total / 1440)), end: total % 1440 };
}

function withEnd(s: Span, date: LocalDate, time: number): Span {
  let endDate = date;
  if (!s.allDay && date === s.startDate && time <= s.start) endDate = plusDays(date, 1);
  if (endDate < s.startDate) endDate = s.startDate;
  return { ...s, endDate, end: time };
}

function apply(s: Span, t: Task): Task {
  if (s.allDay) {
    const last = s.endDate < s.startDate ? s.startDate : s.endDate;
    const a = allDayDue(s.startDate), b = allDayDue(last);
    return { ...t, startAt: a.at, dueAt: b.at, isAllDay: 1, timeZone: a.timeZone, startZone: null, endZone: null };
  }
  const a = timedDue(s.startDate, s.start), b = timedDue(s.endDate, s.end);
  return { ...t, startAt: a.at, dueAt: Math.max(b.at, a.at), isAllDay: 0, timeZone: a.timeZone };
}

const EVENT_TYPES: { key: string | null; label: string }[] = [
  { key: null, label: "Обычное событие" }, { key: "FOCUS", label: "◎ Время для работы" }, { key: "AWAY", label: "Нет на месте" },
];

const ALL_DAY_PRESETS = [
  { label: "В день события (9:00)", minutes: 0 }, { label: "За день (9:00)", minutes: 1440 }, { label: "За 2 дня (9:00)", minutes: 2880 }, { label: "За неделю (9:00)", minutes: 7 * 1440 },
];

export function EventEditor({ draft, onClose, onTask, onPreview }: {
  draft: EventDraft; onClose: () => void; onTask: (id: string) => void;
  /** A new event as it is now, to show in the calendar under the window; null when it closes. */
  onPreview: (t: Task | null) => void;
}) {
  const { store, actions, toast } = useData();
  const master = draft.task;
  const isNew = master == null;
  const repeating = !!master?.repeatRule;
  const occurrence = master ? draft.occurrence ?? dueDay(master) ?? todayDate() : undefined;
  // A repeat opens as it is on its own day.
  const base: Task = master ? atOccurrence(master, occurrence!) : blank.task({ isEvent: 1, ...draft.init });
  const [title, setTitle] = useState(base.title);
  const [span, setSpan] = useState<Span>(spanOf(base));
  const [repeat, setRepeat] = useState<string | null>(base.repeatRule);
  const [calendarId, setCalendarId] = useState<string>(base.calendarId ?? PERSONAL_CALENDAR_ID);
  const [location, setLocation] = useState(base.location ?? "");
  const [content, setContent] = useState(base.content);
  const [color, setColor] = useState<number | null>(base.color);
  const [type, setType] = useState<string | null>(base.eventType);
  const [countdown, setCountdown] = useState(base.displayMode === "COUNTDOWN");
  const [reminders, setReminders] = useState<ReminderSpec[]>(() =>
    master ? actions.reminders(master.id).filter((r) => r.absoluteAt == null).map((r) => ({ offsetMinutes: r.offsetMinutes ?? 0, kind: r.kind }))
      : actions.defaultReminders(base.calendarId, !!base.isAllDay));
  const [ask, setAsk] = useState<null | "save" | "delete">(null);
  const [dateOpen, setDateOpen] = useState(isNew);
  const [moreOpen, setMoreOpen] = useState(false);
  const today = useToday();
  const calendars = store.live("calendars").sort((a, b) => a.sortOrder - b.sortOrder);
  const calendarColor = cssColor(calendars.find((c) => c.id === calendarId)?.color);

  const edited = (): Task => apply(span, {
    ...base, title: title.trim() || "(Без названия)", repeatRule: repeat, location: location.trim() || null,
    content, color, calendarId: calendarId === PERSONAL_CALENDAR_ID ? null : calendarId, eventType: type,
    displayMode: countdown ? "COUNTDOWN" : "NORMAL", isEvent: 1,
  });

  // The picked time stays marked in the calendar while the event is being filled in, and follows the fields.
  useEffect(() => {
    if (isNew) onPreview({ ...edited(), id: PREVIEW_ID, title: title.trim() || "Новое событие" });
  }, [isNew, title, span, calendarId, color, type, repeat]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => onPreview(null), []); // eslint-disable-line react-hooks/exhaustive-deps

  const save = (scope: SeriesScope = "ALL") => {
    if (isNew) actions.saveEvent(edited(), reminders);
    else actions.saveEvent(edited(), reminders, store.get("tasks", master.id) ?? master, occurrence, scope);
    onClose();
  };

  const remove = (scope: SeriesScope) => {
    if (!master) return;
    const undo = actions.deleteEvent(store.get("tasks", master.id) ?? master, occurrence!, scope);
    onClose();
    toast({ text: scope === "ONE" && repeating ? "Повтор удалён" : "Событие удалено", undo });
  };

  const copy = () => {
    if (!master) return;
    const c = actions.duplicate(store.get("tasks", master.id) ?? master, occurrence);
    onClose();
    toast({ text: `Копия: «${c.title}»` });
  };

  const makeTask = () => {
    if (!master) return;
    actions.update({ ...(store.get("tasks", master.id) ?? master), isEvent: 0 });
    onTask(master.id);
  };

  const setAllDay = (allDay: boolean) => {
    setSpan({ ...span, allDay });
    // Reminders of an all-day event count from 9:00 of its day, so the defaults differ.
    if (isNew) setReminders(actions.defaultReminders(calendarId === PERSONAL_CALENDAR_ID ? null : calendarId, allDay));
  };

  // A click elsewhere closes the card, unless that would lose what was typed.
  const state = JSON.stringify([title, span, repeat, calendarId, location, content, color, type, countdown, reminders]);
  const started = useRef(state);
  const canClose = isNew ? !title.trim() : state === started.current;

  const presets = span.allDay ? ALL_DAY_PRESETS : REMINDER_PRESETS;
  return (
    <EntryCard anchor={draft.anchor} label={isNew ? "Новое событие" : "Событие"} canClose={canClose} onClose={onClose}>
      <div className="card-head">
        <button className="date-btn" aria-expanded={dateOpen} onClick={() => setDateOpen(!dateOpen)}>
          <Icon name="calendar" size={16} /><span>{formatEventSpan(edited(), today)}</span>
        </button>
        {repeat && <Icon name="repeat" size={14} color="var(--muted)" />}
        <div className="spacer" />
        <button className="icon-btn" aria-label="Закрыть" onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <div className="card-body">
        {dateOpen && (
          <div className="card-section">
            <label className="row"><input type="checkbox" checked={span.allDay} onChange={(e) => setAllDay(e.target.checked)} />Весь день</label>
            <div className="two">
              <input className="field" type="date" value={span.startDate} aria-label="Начало, дата" onChange={(e) => e.target.value && setSpan(withStart(span, e.target.value, span.start))} />
              {!span.allDay && <input className="field" type="time" value={formatTime(span.start)} aria-label="Начало, время"
                onChange={(e) => { const t = parseTime(e.target.value); if (t != null) setSpan(withStart(span, span.startDate, t)); }} />}
            </div>
            <div className="two">
              <input className="field" type="date" value={span.endDate} aria-label="Конец, дата" onChange={(e) => e.target.value && setSpan(withEnd(span, e.target.value, span.end))} />
              {!span.allDay && <input className="field" type="time" value={formatTime(span.end)} aria-label="Конец, время"
                onChange={(e) => { const t = parseTime(e.target.value); if (t != null) setSpan(withEnd(span, span.endDate, t)); }} />}
            </div>
            <RepeatPicker day={span.startDate} rule={repeat} onChange={setRepeat} />
            <div className="prop">
              {reminders.map((r) => (
                <button key={`${r.offsetMinutes}:${r.kind}`} className="chip" title="Убрать" onClick={() => setReminders(reminders.filter((x) => x !== r))}>
                  {r.kind === "ALARM" ? "⏰" : "🔔"} {reminderLabel(r.offsetMinutes, span.allDay)} ✕
                </button>
              ))}
              <select className="field" style={{ width: "auto" }} value="" aria-label="Добавить напоминание"
                onChange={(e) => {
                  const m = Number(e.target.value);
                  if (e.target.value !== "" && !reminders.some((r) => r.offsetMinutes === m)) setReminders([...reminders, { offsetMinutes: m, kind: "NOTIFY" }]);
                }}>
                <option value="">+ Напоминание</option>
                {presets.map((p) => <option key={p.minutes} value={p.minutes}>{p.label}</option>)}
              </select>
            </div>
          </div>
        )}
        <input className="plain detail-title" autoFocus={isNew} value={title} placeholder="Название" aria-label="Название"
          onChange={(e) => setTitle(e.target.value)} onKeyDown={(e) => e.key === "Enter" && (repeating ? setAsk("save") : save())} />
        <textarea className="plain" rows={Math.max(2, Math.min(content.split("\n").length, 14))} value={content} placeholder="Описание" aria-label="Описание" onChange={(e) => setContent(e.target.value)} />
        <div className="prop card-place">
          <span className="icon"><Icon name="place" size={16} /></span>
          <input className="plain" value={location} placeholder="Место" aria-label="Место" onChange={(e) => setLocation(e.target.value)} />
        </div>
        {moreOpen && (
          <div className="card-section">
            <select className="field" value={type ?? ""} aria-label="Тип события" onChange={(e) => setType(e.target.value || null)}>
              {EVENT_TYPES.map((t) => <option key={t.label} value={t.key ?? ""}>{t.label}</option>)}
            </select>
            <label className="row"><input type="checkbox" checked={countdown} onChange={(e) => setCountdown(e.target.checked)} />Отсчёт дней до события</label>
            <div className="muted small">Цвет {color == null && calendarColor ? "(сейчас — цвет календаря)" : ""}</div>
            <Swatches value={color} onChange={setColor} palette={CALENDAR_COLORS} />
          </div>
        )}
        <div className="prop card-adds">
          <button className={`chip ${moreOpen ? "on" : ""}`} onClick={() => setMoreOpen(!moreOpen)}>+ Тип, цвет, отсчёт</button>
        </div>
        {repeating && repeat !== master?.repeatRule && <div className="muted small">Новый повтор: {repeatDescription({ ...base, repeatRule: repeat })}</div>}
        {ask && (
          <div className="menu" style={{ position: "static" }} role="menu" aria-label={ask === "save" ? "Сохранить для" : "Удалить"}>
            <div className="muted small" style={{ padding: "6px 12px" }}>{ask === "save" ? "Изменить повторяющееся событие:" : "Удалить повторяющееся событие:"}</div>
            {SCOPES.map((sc) => (
              <button key={sc.key} className={ask === "delete" && sc.key === "ALL" ? "error-text" : undefined}
                onClick={() => (ask === "save" ? save(sc.key) : remove(sc.key))}>{sc.label}</button>
            ))}
            <button onClick={() => setAsk(null)}>Отмена</button>
          </div>
        )}
      </div>
      <div className="card-foot">
        {calendars.length > 0 && (
          <select className="plain-select" value={calendarId} aria-label="Календарь" onChange={(e) => setCalendarId(e.target.value)}>
            {calendars.map((c) => <option key={c.id} value={c.id}>● {c.name}</option>)}
          </select>
        )}
        <div className="spacer" />
        {!isNew && (
          <MenuButton label="Ещё" up items={[
            { label: "Копия", icon: "copy", onClick: copy },
            {
              label: "Копировать ссылку", icon: "link", onClick: () => {
                void copyTaskLink(master.id).then((ok) => toast({ text: ok ? "Ссылка на событие скопирована" : "Браузер не дал скопировать ссылку" }));
              },
            },
            { label: "Сделать задачей", icon: "done", onClick: makeTask },
            { label: "Удалить", icon: "trash", danger: true, onClick: () => (repeating ? setAsk("delete") : remove("ALL")) },
          ]} />
        )}
        {(isNew || !canClose) && <button className="btn" onClick={onClose}>Отмена</button>}
        <button className="btn primary" onClick={() => (repeating ? setAsk("save") : save())}>Сохранить</button>
      </div>
    </EntryCard>
  );
}
