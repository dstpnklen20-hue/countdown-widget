import { useState } from "react";
import { type LocalDate, daysBetween, formatTime, parseTime, plusDays } from "../core/date";
import { PERSONAL_CALENDAR_ID, type Task, blank } from "../data/model";
import { allDayDue, dayOf, localTimeOf, timedDue, todayDate } from "../domain/dates";
import { repeatDescription } from "../domain/tasks";
import { Dialog, Swatches } from "./common";
import { useData } from "./context";
import { REMINDER_PRESETS, reminderLabel } from "./format";
import { repeatPresets } from "./TaskDetail";

/** An event to edit ([task]) or to create (from [init]); [occurrence] is the day of a repeat that was clicked. */
export interface EventDraft {
  task: Task | null;
  init?: Partial<Task>;
  occurrence?: LocalDate;
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

export function EventEditor({ draft, onClose, onTask }: { draft: EventDraft; onClose: () => void; onTask: (id: string) => void }) {
  const { store, actions, toast } = useData();
  const isNew = draft.task == null;
  const base: Task = draft.task ?? blank.task({ isEvent: 1, ...draft.init });
  const [title, setTitle] = useState(base.title);
  const [span, setSpan] = useState<Span>(spanOf(base));
  const [repeat, setRepeat] = useState<string | null>(base.repeatRule);
  const [calendarId, setCalendarId] = useState<string>(base.calendarId ?? PERSONAL_CALENDAR_ID);
  const [location, setLocation] = useState(base.location ?? "");
  const [content, setContent] = useState(base.content);
  const [color, setColor] = useState<number | null>(base.color);
  const [askDelete, setAskDelete] = useState(false);
  const calendars = store.live("calendars").sort((a, b) => a.sortOrder - b.sortOrder);
  const reminders = draft.task ? actions.reminders(draft.task.id) : [];
  const repeating = !!base.repeatRule;

  const save = () => {
    const edited = apply(span, {
      ...base, title: title.trim() || "(Без названия)", repeatRule: repeat, location: location.trim() || null,
      content, color, calendarId: calendarId === PERSONAL_CALENDAR_ID ? null : calendarId,
    });
    if (isNew) actions.createEvent(edited);
    else actions.update(edited);
    onClose();
  };

  const remove = (onlyThis: boolean) => {
    if (!draft.task) return;
    const before = store.get("tasks", draft.task.id) ?? draft.task;
    actions.deleteEvent(draft.task, draft.occurrence ?? dayOf(draft.task, draft.task.dueAt ?? Date.now()), onlyThis);
    onClose();
    toast({ text: "Событие удалено", undo: () => {
      const rows = store.all("tasks").filter((t) => (t.id === before.id || t.seriesId === before.id) && t.deleted);
      actions.update({ ...before, deleted: 0 });
      for (const r of rows) if (r.id !== before.id) actions.update({ ...r, deleted: 0 });
    } });
  };

  const makeTask = () => {
    if (!draft.task) return;
    actions.update({ ...(store.get("tasks", draft.task.id) ?? draft.task), isEvent: 0 });
    onTask(draft.task.id);
  };

  const presets = repeatPresets(span.startDate);
  return (
    <Dialog title={isNew ? "Новое событие" : "Событие"} onClose={onClose} actions={<>
      {!isNew && <button className="btn danger" onClick={() => (repeating ? setAskDelete(true) : remove(false))}>Удалить</button>}
      {!isNew && <button className="btn ghost" onClick={makeTask}>Сделать задачей</button>}
      <div className="spacer" />
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" onClick={save}>Сохранить</button>
    </>}>
      <div className="form">
        <input className="field" autoFocus value={title} placeholder="Название" style={{ fontSize: 17 }}
          onChange={(e) => setTitle(e.target.value)} onKeyDown={(e) => e.key === "Enter" && save()} />
        <label className="row"><input type="checkbox" checked={span.allDay} onChange={(e) => setSpan({ ...span, allDay: e.target.checked })} />Весь день</label>
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
        <select className="field" value={repeat ?? ""} aria-label="Повтор" onChange={(e) => setRepeat(e.target.value || null)}>
          {presets.map((p) => <option key={p.label} value={p.rule ?? ""}>{p.label}</option>)}
          {repeat && !presets.some((p) => p.rule === repeat) && <option value={repeat}>{repeatDescription({ ...base, repeatRule: repeat })}</option>}
        </select>
        {repeating && !isNew && <div className="muted small">Изменения применятся ко всем повторам. Отдельный повтор можно изменить в приложении.</div>}
        {calendars.length > 0 && (
          <select className="field" value={calendarId} aria-label="Календарь" onChange={(e) => setCalendarId(e.target.value)}>
            {calendars.map((c) => <option key={c.id} value={c.id}>● {c.name}</option>)}
          </select>
        )}
        <input className="field" value={location} placeholder="Место" onChange={(e) => setLocation(e.target.value)} />
        <textarea className="field" rows={3} value={content} placeholder="Описание" onChange={(e) => setContent(e.target.value)} />
        {!isNew && (
          <div className="prop">
            {reminders.map((r) => (
              <button key={r.id} className="chip" onClick={() => actions.deleteReminder(r)} title="Убрать">
                🔔 {reminderLabel(r.offsetMinutes ?? 0, span.allDay)} ✕
              </button>
            ))}
            <select className="field" style={{ width: "auto" }} value="" aria-label="Добавить напоминание"
              onChange={(e) => e.target.value !== "" && draft.task && actions.addReminder(draft.task.id, Number(e.target.value))}>
              <option value="">+ Напоминание</option>
              {REMINDER_PRESETS.map((p) => <option key={p.minutes} value={p.minutes}>{p.label}</option>)}
            </select>
          </div>
        )}
        <div className="muted small">Цвет {color == null && calendars.find((c) => c.id === calendarId) ? "(сейчас — цвет календаря)" : ""}</div>
        <Swatches value={color} onChange={setColor} />
      </div>
      {askDelete && (
        <div className="menu" style={{ position: "static", marginTop: 12 }}>
          <button onClick={() => remove(true)}>Только это событие</button>
          <button className="error-text" onClick={() => remove(false)}>Все события серии</button>
        </div>
      )}
    </Dialog>
  );
}
