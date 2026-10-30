import { useState } from "react";
import { type LocalDate, dayOfWeek, formatTime, lengthOfMonth, parseTime, parts, plusDays, plusMonths, toEpochDay, withDayOfMonth } from "../core/date";
import { blank, cssColor, type Habit } from "../data/model";
import { habitDays, habitDaysCode, habitStats, isScheduled } from "../domain/habits";
import { Dialog, Empty, MenuButton, Swatches } from "./common";
import { useData, useToday } from "./context";
import { MONTHS_NOM, WEEKDAYS_SHORT } from "./format";
import { Icon } from "./icons";

/** Habits like the app's HabitsScreen: the last 7 days, details with a month calendar, an archive. */
export function HabitsView() {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [editing, setEditing] = useState<{ habit: Habit; isNew: boolean } | null>(null);
  const [details, setDetails] = useState<string | null>(null);
  const [archive, setArchive] = useState(false);
  const all = store.live("habits").sort((a, b) => a.sortOrder - b.sortOrder);
  const habits = all.filter((h) => !h.archived);
  const archived = all.filter((h) => h.archived);
  const week = Array.from({ length: 7 }, (_, i) => plusDays(today, i - 6));
  const checkins = store.index("habit_checkins", "habitId");
  const countsOf = (id: string) => {
    const counts = new Map<number, number>();
    for (const c of checkins.get(id) ?? []) counts.set(c.day, Math.max(counts.get(c.day) ?? 0, c.count));
    return counts;
  };
  const toggle = (h: Habit, d: LocalDate) => {
    const count = actions.checkIn(h.id, d)?.deleted ? 0 : actions.checkIn(h.id, d)?.count ?? 0;
    actions.setCount(h.id, d, h.goal > 1 ? (count >= h.goal ? 0 : count + 1) : count >= 1 ? 0 : 1);
  };
  const remove = (h: Habit) => {
    actions.deleteHabit(h);
    setDetails(null);
    toast({ text: `Привычка «${h.name}» удалена`, undo: () => actions.saveHabit({ ...h, deleted: 0 }, false) });
  };
  const shown = details ? store.get("habits", details) : undefined;

  return (
    <>
      <div className="main-head">
        <h1>Привычки</h1>
        <div className="spacer" />
        {archived.length > 0 && <button className="btn ghost" onClick={() => setArchive(true)}><Icon name="archive" size={18} />Архив ({archived.length})</button>}
        <button className="btn primary" onClick={() => setEditing({ habit: blank.habit({ name: "" }), isNew: true })}><Icon name="plus" size={18} />Привычка</button>
      </div>
      <div className="main-body">
        {!habits.length && <Empty emoji="🌱" text="Добавьте привычку: зарядка, вода, чтение" />}
        {habits.map((h) => {
          const stats = habitStats(h, countsOf(h.id), today);
          return (
            <div key={h.id} className="habit">
              <button className="emoji" style={{ border: "none", background: "none" }} aria-label={`Подробнее: ${h.name}`} onClick={() => setDetails(h.id)}>{h.emoji}</button>
              <div className="info" role="button" tabIndex={0} style={{ cursor: "pointer" }} onClick={() => setDetails(h.id)} onKeyDown={(e) => e.key === "Enter" && setDetails(h.id)}>
                <div style={{ fontWeight: 600 }}>{h.name}</div>
                <div className="muted small">
                  Серия: {stats.current} · Выполнено: {stats.total} · За месяц: {stats.rate}%{h.goal > 1 ? ` · Цель: ${h.goal} в день` : ""}
                  {h.reminderMinute != null ? ` · 🔔 ${formatTime(h.reminderMinute)}` : ""}
                </div>
              </div>
              <div className="days">
                {week.map((d) => {
                  const c = actions.checkIn(h.id, d);
                  const count = c && !c.deleted ? c.count : 0;
                  const on = count >= h.goal;
                  return (
                    <button key={d} className={`hday ${on ? "on" : ""} ${isScheduled(h, d) ? "" : "off"}`}
                      style={on && cssColor(h.color) ? { background: cssColor(h.color), borderColor: cssColor(h.color) } : undefined}
                      title={`${WEEKDAYS_SHORT[dayOfWeek(d) - 1]}, ${parts(d)[2]}${h.goal > 1 ? ` — ${count}/${h.goal}` : ""}`}
                      onClick={() => toggle(h, d)}>
                      {h.goal > 1 && !on && count > 0 ? count : parts(d)[2]}
                    </button>
                  );
                })}
              </div>
              <MenuButton label={`Привычка «${h.name}»`} items={[
                { label: "Изменить", icon: "edit", onClick: () => setEditing({ habit: h, isNew: false }) },
                { label: "В архив", icon: "archive", onClick: () => actions.saveHabit({ ...h, archived: 1 }, false) },
                { label: "Удалить", icon: "trash", danger: true, onClick: () => remove(h) },
              ]} />
            </div>
          );
        })}
      </div>
      {editing && <HabitDialog habit={editing.habit} isNew={editing.isNew} onClose={() => setEditing(null)} />}
      {shown && !shown.deleted && (
        <HabitDetails habit={shown} counts={countsOf(shown.id)} today={today} onToggle={(d) => toggle(shown, d)} onClose={() => setDetails(null)}
          onEdit={() => { setDetails(null); setEditing({ habit: shown, isNew: false }); }}
          onArchive={() => { actions.saveHabit({ ...shown, archived: 1 }, false); setDetails(null); }}
          onDelete={() => confirm(`Удалить привычку «${shown.name}»?`) && remove(shown)} />
      )}
      {archive && (
        <Dialog title="Архив привычек" onClose={() => setArchive(false)} actions={<button className="btn" onClick={() => setArchive(false)}>Закрыть</button>}>
          {!archived.length && <div className="muted">Архив пуст</div>}
          {archived.map((h) => (
            <div key={h.id} className="prop">
              <span style={{ flex: 1 }}>{h.emoji} {h.name}</span>
              <button className="btn ghost" onClick={() => actions.saveHabit({ ...h, archived: 0 }, false)}><Icon name="restore" size={16} />Вернуть</button>
            </div>
          ))}
        </Dialog>
      )}
    </>
  );
}

/** Streaks and a month of check-ins; a tap on a past day marks or clears it. */
function HabitDetails({ habit: h, counts, today, onToggle, onClose, onEdit, onArchive, onDelete }: {
  habit: Habit; counts: Map<number, number>; today: LocalDate; onToggle: (d: LocalDate) => void;
  onClose: () => void; onEdit: () => void; onArchive: () => void; onDelete: () => void;
}) {
  const [month, setMonth] = useState(withDayOfMonth(today, 1));
  const stats = habitStats(h, counts, today);
  const offset = dayOfWeek(month) - 1;
  const cells = Array.from({ length: offset + lengthOfMonth(month) }, (_, i) => (i < offset ? null : plusDays(month, i - offset)));
  const color = cssColor(h.color) ?? "var(--primary)";
  return (
    <Dialog title={`${h.emoji} ${h.name}`} onClose={onClose} actions={<>
      <button className="btn danger" onClick={onDelete}>Удалить</button>
      <button className="btn ghost" onClick={onArchive}>В архив</button>
      <div className="spacer" />
      <button className="btn" onClick={onEdit}>Изменить</button>
      <button className="btn primary" onClick={onClose}>Закрыть</button>
    </>}>
      <div className="stats tiles" style={{ marginBottom: 12 }}>
        <div><b>{stats.current}</b><span className="muted small">серия</span></div>
        <div><b>{stats.best}</b><span className="muted small">лучшая</span></div>
        <div><b>{stats.total}</b><span className="muted small">всего</span></div>
        <div><b>{stats.rate}%</b><span className="muted small">месяц</span></div>
      </div>
      <div className="prop" style={{ justifyContent: "space-between" }}>
        <button className="icon-btn" aria-label="Прошлый месяц" onClick={() => setMonth(plusMonths(month, -1))}><Icon name="left" /></button>
        <b>{MONTHS_NOM[parts(month)[1] - 1]} {parts(month)[0]}</b>
        <button className="icon-btn" aria-label="Следующий месяц" disabled={month >= withDayOfMonth(today, 1)} onClick={() => setMonth(plusMonths(month, 1))}><Icon name="right" /></button>
      </div>
      <div className="habit-month">
        {WEEKDAYS_SHORT.map((w) => <span key={w} className="w">{w}</span>)}
        {cells.map((d, i) => {
          if (!d) return <span key={i} />;
          const count = counts.get(toEpochDay(d)) ?? 0;
          const future = d > today;
          return (
            <button key={d} disabled={future} onClick={() => onToggle(d)}
              className={`${count >= h.goal ? "on" : count > 0 ? "part" : ""} ${future || !isScheduled(h, d) ? "off" : ""}`}
              style={count >= h.goal ? { background: color } : count > 0 ? { background: `color-mix(in srgb, ${color} 40%, transparent)` } : undefined}>
              {parts(d)[2]}
            </button>
          );
        })}
      </div>
    </Dialog>
  );
}

const EMOJIS = ["✅", "💧", "🏃", "📚", "🧘", "🍎", "💪", "😴", "🦷", "💊", "✍️", "🎸"];

function HabitDialog({ habit, isNew, onClose }: { habit: Habit; isNew: boolean; onClose: () => void }) {
  const { actions } = useData();
  const [h, setH] = useState(habit);
  const days = habitDays(h.days);
  const everyDay = !h.days;
  const toggleDay = (d: number) => {
    const next = new Set(everyDay ? [] : days);
    if (next.has(d)) next.delete(d);
    else next.add(d);
    setH({ ...h, days: habitDaysCode(next) });
  };
  const save = () => {
    if (!h.name.trim()) return;
    actions.saveHabit({ ...h, name: h.name.trim() }, isNew);
    onClose();
  };
  return (
    <Dialog title={isNew ? "Новая привычка" : "Привычка"} onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" disabled={!h.name.trim()} onClick={save}>Сохранить</button>
    </>}>
      <div className="form">
        <input className="field" autoFocus placeholder="Название" value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} onKeyDown={(e) => e.key === "Enter" && save()} />
        <div className="prop">{EMOJIS.map((e) => (
          <button key={e} className={`chip ${h.emoji === e ? "on" : ""}`} style={{ fontSize: 18 }} onClick={() => setH({ ...h, emoji: e })}>{e}</button>
        ))}</div>
        <div className="muted small">Дни (ничего не выбрано — каждый день)</div>
        <div className="prop">{[1, 2, 3, 4, 5, 6, 7].map((d) => (
          <button key={d} className={`hday ${!everyDay && days.has(d) ? "on" : ""}`} onClick={() => toggleDay(d)}>{WEEKDAYS_SHORT[d - 1]}</button>
        ))}</div>
        <label className="row">Цель в день:
          <input className="field" type="number" min={1} max={50} style={{ width: 80 }} value={h.goal}
            onChange={(e) => setH({ ...h, goal: Math.max(1, Math.min(50, Number(e.target.value) || 1)) })} />
        </label>
        <label className="row">Напоминание:
          <input className="field" type="time" style={{ width: "auto" }} value={h.reminderMinute != null ? formatTime(h.reminderMinute) : ""}
            onChange={(e) => setH({ ...h, reminderMinute: e.target.value ? parseTime(e.target.value) : null })} />
          {h.reminderMinute != null && <button className="btn ghost small" onClick={() => setH({ ...h, reminderMinute: null })}>Без напоминания</button>}
        </label>
        <Swatches value={h.color} onChange={(c) => setH({ ...h, color: c })} />
      </div>
    </Dialog>
  );
}
