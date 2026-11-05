import { useEffect, useRef, useState } from "react";
import { formatTime, parseTime } from "../core/date";
import { cssColor, INBOX_ID, type Task } from "../data/model";
import { allDayDue, dueDay, localTimeOf, timedDue, todayDate } from "../domain/dates";
import { durationMinutes, isDone, timeStart } from "../domain/tasks";
import { Check, MenuButton, Swatches } from "./common";
import { useData, useNow, useToday } from "./context";
import { PRIORITY_COLORS, PRIORITY_NAMES, REMINDER_PRESETS, formatDue, formatDuration, reminderLabel } from "./format";
import { Icon } from "./icons";
import { RepeatPicker } from "./RepeatPicker";

const DURATIONS = [5, 10, 15, 30, 45, 60, 90, 120, 180, 240];
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
  const [dateOpen, setDateOpen] = useState(false);
  // What is empty stays out of the way until asked for.
  const [adding, setAdding] = useState<"item" | "sub" | "tag" | "color" | null>(null);

  // Save typed text before switching to another task.
  useEffect(() => () => { flushTitle(); flushContent(); }, [task.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const lists = store.live("task_lists").sort((a, b) => Number(b.isInbox) - Number(a.isInbox) || a.sortOrder - b.sortOrder);
  const sections = actions.sections(task.listId);
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

  const when = formatDue(task, today);
  const listLabel = (l: { id: string; isInbox: number; name: string }) => (l.isInbox || l.id === INBOX_ID ? "Входящие" : l.name);

  return (
    <>
      <div className="card-head">
        <Check done={done} priority={task.priority} onToggle={() => actions.setDone(task, !done)} />
        <button className={`date-btn ${overdue ? "overdue" : ""} ${when ? "" : "empty"}`} aria-expanded={dateOpen} onClick={() => setDateOpen(!dateOpen)}>
          <Icon name="calendar" size={16} /><span>{when ?? "Срок"}</span>
        </button>
        {task.repeatRule && <Icon name="repeat" size={14} color="var(--muted)" />}
        <div className="spacer" />
        <MenuButton label={`Приоритет: ${PRIORITY_NAMES[task.priority]}`} icon="flag" iconColor={PRIORITY_COLORS[task.priority]}
          items={[3, 2, 1, 0].map((p) => ({ label: (p === task.priority ? "✓ " : "") + PRIORITY_NAMES[p], onClick: () => actions.update({ ...task, priority: p }) }))} />
        <button className="icon-btn" aria-label="Закрыть" onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <div className="card-body">
        {dateOpen && (
          <div className="card-section">
            <div className="prop">
              <input className="field" type="date" style={{ width: "auto", color: overdue ? "var(--danger)" : undefined }} value={day ?? ""} aria-label="Дата"
                onChange={(e) => setDate(e.target.value, time)} />
              {day && (
                <input className="field" type="time" style={{ width: "auto" }} value={time != null ? formatTime(time) : ""} aria-label="Время"
                  onChange={(e) => setDate(day, parseTime(e.target.value))} />
              )}
              {day && <button className="btn ghost small" onClick={() => setDate("", null)}>Убрать</button>}
            </div>
            {time != null && (
              <div className="prop">
                <span className="icon"><Icon name="clock" size={16} /></span>
                <select className="field" style={{ width: "auto" }} value={length ?? ""} aria-label="Длительность"
                  onChange={(e) => setDuration(e.target.value ? Number(e.target.value) : null)}>
                  <option value="">Без длительности</option>
                  {[...new Set([...DURATIONS, ...(length ? [length] : [])])].sort((x, y) => x - y).map((m) => <option key={m} value={m}>{formatDuration(m)}</option>)}
                </select>
                {length != null && <span className="muted small">до {formatTime((time + length) % 1440)}</span>}
              </div>
            )}
            <div className="prop">
              <span className="icon"><Icon name="repeat" size={16} /></span>
              <RepeatPicker day={day ?? today} rule={task.repeatRule} onChange={setRepeat} style={{ width: "auto", maxWidth: 240 }} />
              {task.repeatRule && (
                <label className="row small muted">
                  <input type="checkbox" checked={task.repeatFrom === "COMPLETION"} onChange={(e) => actions.update({ ...task, repeatFrom: e.target.checked ? "COMPLETION" : "DUE" })} />
                  от выполнения
                </label>
              )}
            </div>
            {task.dueAt != null && (
              <div className="prop">
                <span className="icon"><Icon name="bell" size={16} /></span>
                {reminders.map((r) => (
                  <button key={r.id} className="chip" onClick={() => actions.deleteReminder(r)} title="Убрать">{reminderLabel(r.offsetMinutes ?? 0, !!task.isAllDay)} ✕</button>
                ))}
                <select className="field" style={{ width: "auto" }} value="" aria-label="Добавить напоминание"
                  onChange={(e) => e.target.value !== "" && actions.addReminder(task.id, Number(e.target.value))}>
                  <option value="">+ Напоминание</option>
                  {(task.isAllDay ? [{ label: "В день события", minutes: 0 }, { label: "За 1 день", minutes: 1440 }, { label: "За 2 дня", minutes: 2880 }] : REMINDER_PRESETS)
                    .map((p) => <option key={p.minutes} value={p.minutes}>{p.label}</option>)}
                </select>
              </div>
            )}
          </div>
        )}

        <input className="plain detail-title" value={title} placeholder="Название" onChange={(e) => setTitle(e.target.value)} onBlur={flushTitle} aria-label="Название" />
        <textarea className="plain" rows={Math.max(2, Math.min(content.split("\n").length, 14))} value={content} placeholder="Описание"
          onChange={(e) => setContent(e.target.value)} onBlur={flushContent} aria-label="Описание" />

        {checklist.map((c) => (
          <div key={c.id} className="check-row">
            <Check done={!!c.checked} priority={0} onToggle={() => actions.updateChecklistItem({ ...c, checked: c.checked ? 0 : 1 })} />
            <ChecklistText value={c.title} done={!!c.checked} onSave={(v) => actions.updateChecklistItem({ ...c, title: v })} />
            <button className="icon-btn" aria-label="Удалить пункт" onClick={() => actions.deleteChecklistItem(c)}><Icon name="close" size={14} /></button>
          </div>
        ))}
        {adding === "item" && (
          <input className="plain" autoFocus placeholder="Новый пункт, Enter — добавить" value={newItem} onChange={(e) => setNewItem(e.target.value)} aria-label="Новый пункт"
            onKeyDown={(e) => { if (e.key === "Enter" && newItem.trim()) { actions.addChecklistItem(task.id, newItem.trim()); setNewItem(""); } }} />
        )}

        {subtasks.map((st) => (
          <div key={st.id} className="check-row" style={{ cursor: "pointer" }} onClick={() => onOpen(st)}>
            <Check done={isDone(st)} priority={st.priority} onToggle={() => actions.setDone(st, !isDone(st))} />
            <span style={{ flex: 1, color: isDone(st) ? "var(--faint)" : undefined }}>{st.title}</span>
            <Icon name="right" size={14} color="var(--faint)" />
          </div>
        ))}
        {adding === "sub" && (
          <input className="plain" autoFocus placeholder="Новая подзадача, Enter — добавить" value={newSub} onChange={(e) => setNewSub(e.target.value)} aria-label="Новая подзадача"
            onKeyDown={(e) => { if (e.key === "Enter" && newSub.trim()) { actions.addSubtask(task, newSub.trim()); setNewSub(""); } }} />
        )}

        {(tags.length > 0 || adding === "tag") && (
          <div className="prop">
            {tags.map((t) => <button key={t} className="chip on" onClick={() => actions.setTags(task.id, tags.filter((x) => x !== t))}>#{t} ✕</button>)}
            {adding === "tag" && allTags.slice(0, 6).map((t) => <button key={t.id} className="chip" onClick={() => actions.setTags(task.id, [...tags, t.name])}>+ {t.name}</button>)}
          </div>
        )}
        {adding === "tag" && (
          <input className="plain" autoFocus placeholder="Новый тег, Enter — добавить" value={newTag} onChange={(e) => setNewTag(e.target.value)} aria-label="Новый тег"
            onKeyDown={(e) => { if (e.key === "Enter" && newTag.trim()) { actions.setTags(task.id, [...tags, newTag]); setNewTag(""); } }} />
        )}
        {adding === "color" && (
          <>
            <Swatches value={task.color} onChange={(c) => actions.update({ ...task, color: c })} />
            {task.color == null && cssColor(store.get("task_lists", task.listId)?.color) && <div className="muted small">Сейчас берётся цвет списка</div>}
          </>
        )}

        <div className="prop card-adds">
          {([["item", "Пункт"], ["sub", "Подзадача"], ["tag", "Тег"], ["color", "Цвет"]] as ["item" | "sub" | "tag" | "color", string][]).map(([k, label]) => (
            <button key={k} className={`chip ${adding === k ? "on" : ""}`} onClick={() => setAdding(adding === k ? null : k)}>+ {label}</button>
          ))}
        </div>
      </div>
      <div className="card-foot">
        <select className="plain-select" value={task.listId} aria-label="Список" onChange={(e) => actions.update({ ...task, listId: e.target.value, sectionId: null })}>
          {lists.map((l) => <option key={l.id} value={l.id}>{listLabel(l)}</option>)}
        </select>
        {sections.length > 0 && (
          <select className="plain-select" value={task.sectionId ?? ""} aria-label="Раздел" onChange={(e) => actions.moveToSection(task, e.target.value || null)}>
            <option value="">Без раздела</option>
            {sections.map((sec) => <option key={sec.id} value={sec.id}>{sec.name}</option>)}
          </select>
        )}
        <div className="spacer" />
        <MenuButton label="Ещё" up items={[
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
