import { useEffect, useRef, useState } from "react";
import { type LocalDate, dayOfWeek, formatTime, parseTime, parts } from "../core/date";
import { cssColor, INBOX_ID, type Task } from "../data/model";
import { allDayDue, dueDay, localTimeOf, timedDue, todayDate } from "../domain/dates";
import { durationMinutes, isDone, repeatDescription, timeStart } from "../domain/tasks";
import { Check, MenuButton, Swatches } from "./common";
import { useData, useNow, useToday } from "./context";
import { PRIORITY_COLORS, PRIORITY_NAMES, REMINDER_PRESETS, formatDuration, reminderLabel } from "./format";
import { Icon } from "./icons";

const DURATIONS = [15, 30, 45, 60, 90, 120, 180, 240];
const DAY_ACC = ["понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье"];
const DAY_CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];

/** Repeats offered for a date, like repeatPresets() in EventEditor.kt. */
export function repeatPresets(day: LocalDate): { label: string; rule: string | null }[] {
  const dow = dayOfWeek(day);
  const [, , d] = parts(day);
  return [
    { label: "Не повторять", rule: null },
    { label: "Каждый день", rule: "FREQ=DAILY" },
    { label: `Каждую неделю в ${DAY_ACC[dow - 1]}`, rule: `FREQ=WEEKLY;BYDAY=${DAY_CODES[dow - 1]}` },
    { label: "По будням", rule: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" },
    { label: `Ежемесячно, ${d} числа`, rule: `FREQ=MONTHLY;BYMONTHDAY=${d}` },
    { label: "Ежегодно", rule: "FREQ=YEARLY" },
  ];
}

/** Text typed into a field is saved after a short pause (and when leaving it). */
function useDraft(value: string, save: (v: string) => void, key: string): [string, (v: string) => void, () => void] {
  const [draft, setDraft] = useState(value);
  const dirty = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => {
    if (!dirty.current) setDraft(value);
  }, [value, key]);
  useEffect(() => () => clearTimeout(timer.current), []);
  const flush = () => {
    clearTimeout(timer.current);
    if (dirty.current) {
      dirty.current = false;
      save(draftRef.current);
    }
  };
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const change = (v: string) => {
    setDraft(v);
    draftRef.current = v;
    dirty.current = true;
    clearTimeout(timer.current);
    timer.current = setTimeout(flush, 600);
  };
  return [draft, change, flush];
}

export function TaskDetail({ task, onClose, onOpen }: { task: Task; onClose: () => void; onOpen: (t: Task) => void }) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow();
  const fresh = () => store.get("tasks", task.id) ?? task;
  const [title, setTitle, flushTitle] = useDraft(task.title, (v) => actions.update({ ...fresh(), title: v }), task.id);
  const [content, setContent, flushContent] = useDraft(task.content, (v) => actions.update({ ...fresh(), content: v }), task.id);
  const [newItem, setNewItem] = useState("");
  const [newSub, setNewSub] = useState("");
  const [newTag, setNewTag] = useState("");

  // Save typed text before switching to another task.
  useEffect(() => () => { flushTitle(); flushContent(); }, [task.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const lists = store.live("task_lists").sort((a, b) => Number(b.isInbox) - Number(a.isInbox) || a.sortOrder - b.sortOrder);
  const day = dueDay(task);
  const start = timeStart(task);
  const time = start != null && !task.isAllDay ? localTimeOf(start) : null;
  const length = durationMinutes(task);
  const done = isDone(task);
  const reminders = actions.reminders(task.id);
  const checklist = actions.checklist(task.id);
  const subtasks = store.live("tasks").filter((t) => t.parentId === task.id).sort((a, b) => a.sortOrder - b.sortOrder);
  const tags = actions.tagNames(task.id);
  const allTags = store.live("tags").filter((t) => !tags.includes(t.name));
  const overdue = !done && day != null && (task.isAllDay ? day < today : (task.dueAt ?? 0) < now);
  const repeat = repeatDescription(task);

  const setDate = (d: string, t: number | null) => {
    if (!d) return actions.setDue(task, null, null);
    actions.setDue(task, t != null ? timedDue(d, t) : allDayDue(d), length);
  };
  const setDuration = (minutes: number | null) => {
    if (task.dueAt == null || task.isAllDay) return;
    const s = start!;
    actions.update(minutes == null ? { ...task, startAt: null, dueAt: s } : { ...task, startAt: s, dueAt: s + minutes * 60_000 });
  };
  const setRepeat = (rule: string | null) => {
    if (rule && task.dueAt == null) {
      const due = allDayDue(todayDate());
      actions.update({ ...task, repeatRule: rule, dueAt: due.at, isAllDay: 1, timeZone: due.timeZone });
    } else actions.update({ ...task, repeatRule: rule });
  };

  return (
    <>
      <div className="detail-head">
        <button className="icon-btn" aria-label="Закрыть" onClick={onClose}><Icon name="close" /></button>
        <select className="field" style={{ width: "auto", maxWidth: 200 }} value={task.listId} aria-label="Список"
          onChange={(e) => actions.update({ ...task, listId: e.target.value, sectionId: null })}>
          {lists.map((l) => <option key={l.id} value={l.id}>{l.isInbox || l.id === INBOX_ID ? "Входящие" : l.name}</option>)}
        </select>
        <div className="spacer" />
        <MenuButton label="Ещё" items={[
          { label: "Сделать событием", icon: "event", onClick: () => actions.update(asEvent(task)) },
          {
            label: task.displayMode === "COUNTDOWN" ? "Обычная задача (без отсчёта)" : "Событие с отсчётом", icon: "hourglass",
            onClick: () => actions.update({ ...task, displayMode: task.displayMode === "COUNTDOWN" ? "NORMAL" : "COUNTDOWN" }),
          },
          {
            label: "Удалить", icon: "trash", danger: true, onClick: () => {
              actions.delete(task);
              onClose();
              toast({ text: "Задача удалена", undo: () => actions.restore({ ...task, deleted: 1, updatedAt: Date.now() - 1000 }) });
            },
          },
        ]} />
      </div>
      <div className="detail-body">
        <div className="prop">
          <Check done={done} priority={task.priority} onToggle={() => actions.setDone(task, !done)} />
          <input className="field" type="date" style={{ width: "auto", color: overdue ? "var(--danger)" : undefined }} value={day ?? ""} aria-label="Дата"
            onChange={(e) => setDate(e.target.value, time)} />
          {day && (
            <input className="field" type="time" style={{ width: "auto" }} value={time != null ? formatTime(time) : ""} aria-label="Время"
              onChange={(e) => setDate(day, parseTime(e.target.value))} />
          )}
          {day && <button className="btn ghost small" onClick={() => setDate("", null)}>Убрать</button>}
          <div className="spacer" />
          <select className="field" style={{ width: "auto", color: PRIORITY_COLORS[task.priority] }} value={task.priority} aria-label="Приоритет"
            onChange={(e) => actions.update({ ...task, priority: Number(e.target.value) })}>
            {[3, 2, 1, 0].map((p) => <option key={p} value={p}>⚑ {PRIORITY_NAMES[p]}</option>)}
          </select>
        </div>
        {time != null && (
          <div className="prop">
            <span className="icon"><Icon name="clock" size={18} /></span>
            <select className="field" style={{ width: "auto" }} value={length ?? ""} aria-label="Длительность"
              onChange={(e) => setDuration(e.target.value ? Number(e.target.value) : null)}>
              <option value="">Без длительности</option>
              {[...new Set([...DURATIONS, ...(length ? [length] : [])])].sort((a, b) => a - b).map((m) => <option key={m} value={m}>{formatDuration(m)}</option>)}
            </select>
            {length != null && <span className="muted small">до {formatTime((time + length) % 1440)}</span>}
          </div>
        )}
        <div className="prop">
          <span className="icon"><Icon name="repeat" size={18} /></span>
          <select className="field" style={{ width: "auto", maxWidth: 280 }} value={task.repeatRule ?? ""} aria-label="Повтор"
            onChange={(e) => setRepeat(e.target.value || null)}>
            {repeatPresets(day ?? today).map((p) => <option key={p.label} value={p.rule ?? ""}>{p.label}</option>)}
            {task.repeatRule && !repeatPresets(day ?? today).some((p) => p.rule === task.repeatRule) && <option value={task.repeatRule}>{repeat}</option>}
          </select>
          {task.repeatRule && (
            <label className="row small muted">
              <input type="checkbox" checked={task.repeatFrom === "COMPLETION"} onChange={(e) => actions.update({ ...task, repeatFrom: e.target.checked ? "COMPLETION" : "DUE" })} />
              от выполнения
            </label>
          )}
        </div>

        <input className="plain detail-title" value={title} placeholder="Название" onChange={(e) => setTitle(e.target.value)} onBlur={flushTitle}
          style={{ textDecoration: done ? "line-through" : undefined }} aria-label="Название" />
        <textarea className="plain" rows={Math.max(3, content.split("\n").length)} value={content} placeholder="Описание"
          onChange={(e) => setContent(e.target.value)} onBlur={flushContent} aria-label="Описание" />

        <div className="section-title">Напоминания</div>
        {task.dueAt == null ? <div className="muted small">Задайте дату, чтобы добавить напоминание</div> : (
          <div className="prop">
            {reminders.map((r) => (
              <button key={r.id} className="chip" onClick={() => actions.deleteReminder(r)} title="Убрать">
                <Icon name="bell" size={14} /> {reminderLabel(r.offsetMinutes ?? 0, !!task.isAllDay)} ✕
              </button>
            ))}
            <select className="field" style={{ width: "auto" }} value="" aria-label="Добавить напоминание"
              onChange={(e) => e.target.value !== "" && actions.addReminder(task.id, Number(e.target.value))}>
              <option value="">+ Добавить</option>
              {(task.isAllDay ? [{ label: "В день события", minutes: 0 }, { label: "За 1 день", minutes: 1440 }, { label: "За 2 дня", minutes: 2880 }] : REMINDER_PRESETS)
                .map((p) => <option key={p.minutes} value={p.minutes}>{p.label}</option>)}
            </select>
          </div>
        )}

        <div className="section-title">Чек-лист</div>
        {checklist.map((c) => (
          <div key={c.id} className="check-row">
            <Check done={!!c.checked} priority={0} onToggle={() => actions.updateChecklistItem({ ...c, checked: c.checked ? 0 : 1 })} />
            <ChecklistText value={c.title} done={!!c.checked} onSave={(v) => actions.updateChecklistItem({ ...c, title: v })} />
            <button className="icon-btn" aria-label="Удалить пункт" onClick={() => actions.deleteChecklistItem(c)}><Icon name="close" size={16} /></button>
          </div>
        ))}
        <input className="plain" placeholder="+ Добавить пункт" value={newItem} onChange={(e) => setNewItem(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && newItem.trim()) { actions.addChecklistItem(task.id, newItem.trim()); setNewItem(""); } }} />

        <div className="section-title">Подзадачи</div>
        {subtasks.map((s) => (
          <div key={s.id} className="check-row" style={{ cursor: "pointer" }} onClick={() => onOpen(s)}>
            <Check done={isDone(s)} priority={s.priority} onToggle={() => actions.setDone(s, !isDone(s))} />
            <span style={{ flex: 1, textDecoration: isDone(s) ? "line-through" : undefined }}>{s.title}</span>
          </div>
        ))}
        <input className="plain" placeholder="+ Добавить подзадачу" value={newSub} onChange={(e) => setNewSub(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && newSub.trim()) { actions.addSubtask(task, newSub.trim()); setNewSub(""); } }} />

        <div className="section-title">Теги</div>
        <div className="prop">
          {tags.map((t) => <button key={t} className="chip on" onClick={() => actions.setTags(task.id, tags.filter((x) => x !== t))}>#{t} ✕</button>)}
          {allTags.slice(0, 6).map((t) => <button key={t.id} className="chip" onClick={() => actions.setTags(task.id, [...tags, t.name])}>+ {t.name}</button>)}
        </div>
        <input className="plain" placeholder="+ Новый тег" value={newTag} onChange={(e) => setNewTag(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && newTag.trim()) { actions.setTags(task.id, [...tags, newTag]); setNewTag(""); } }} />

        <div className="section-title">Цвет</div>
        <Swatches value={task.color} onChange={(c) => actions.update({ ...task, color: c })} />
        {task.color == null && cssColor(store.get("task_lists", task.listId)?.color) && <div className="muted small">Сейчас берётся цвет списка</div>}
      </div>
    </>
  );
}

function ChecklistText({ value, done, onSave }: { value: string; done: boolean; onSave: (v: string) => void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  return (
    <input className="plain" value={draft} style={{ textDecoration: done ? "line-through" : undefined, color: done ? "var(--faint)" : undefined }}
      onChange={(e) => setDraft(e.target.value)} onBlur={() => draft !== value && onSave(draft)}
      onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
  );
}

/**
 * A task turned into an event (TaskDetailViewModel.setEvent): a task with a time but no start
 * becomes an hour from that time; one without a date, an hour from 9:00 today.
 */
export function asEvent(t: Task): Task {
  if (t.dueAt == null) {
    const s = timedDue(todayDate(), 9 * 60);
    return { ...t, isEvent: 1, startAt: s.at, dueAt: s.at + 3_600_000, isAllDay: 0, timeZone: s.timeZone };
  }
  if (t.startAt == null && !t.isAllDay) return { ...t, isEvent: 1, startAt: t.dueAt, dueAt: t.dueAt + 3_600_000 };
  return { ...t, isEvent: 1 };
}
