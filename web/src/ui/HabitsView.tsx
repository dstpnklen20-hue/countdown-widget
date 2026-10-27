import { useState } from "react";
import { type LocalDate, dayOfWeek, parts, plusDays, toEpochDay, withDayOfMonth } from "../core/date";
import { blank, cssColor, type Habit } from "../data/model";
import { localDateOf } from "../domain/dates";
import { Dialog, Empty, MenuButton, Swatches } from "./common";
import { useData, useToday } from "./context";
import { WEEKDAYS_SHORT } from "./format";
import { Icon } from "./icons";

/** Port of domain/Habits.kt. */
const CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];

function habitDays(codes: string): Set<number> {
  const set = new Set(codes.split(",").map((c) => CODES.indexOf(c.trim().toUpperCase()) + 1).filter((d) => d > 0));
  return set.size ? set : new Set([1, 2, 3, 4, 5, 6, 7]);
}

const isScheduled = (h: Habit, day: LocalDate) => habitDays(h.days).has(dayOfWeek(day));

/** Streaks count consecutive scheduled days that reached the goal; an unfinished today does not break it yet. */
function habitStats(h: Habit, counts: Map<number, number>, today: LocalDate) {
  const done = (d: LocalDate) => (counts.get(toEpochDay(d)) ?? 0) >= h.goal;
  let current = 0;
  let day = done(today) ? today : plusDays(today, -1);
  for (let guard = 0; guard < 3660; guard++) {
    if (isScheduled(h, day)) {
      if (done(day)) current++;
      else break;
    }
    day = plusDays(day, -1);
  }
  const created = localDateOf(h.createdAt);
  const monthStart = withDayOfMonth(today, 1) > created ? withDayOfMonth(today, 1) : created;
  let scheduled = 0, hit = 0;
  for (let d = monthStart; d <= today; d = plusDays(d, 1)) {
    if (!isScheduled(h, d)) continue;
    scheduled++;
    if (done(d)) hit++;
  }
  const total = [...counts.values()].filter((c) => c >= h.goal).length;
  return { current, total, rate: scheduled ? Math.floor((hit * 100) / scheduled) : 0 };
}

export function HabitsView() {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [editing, setEditing] = useState<Habit | null>(null);
  const [isNew, setIsNew] = useState(false);
  const habits = store.live("habits").filter((h) => !h.archived).sort((a, b) => a.sortOrder - b.sortOrder);
  const week = Array.from({ length: 7 }, (_, i) => plusDays(today, i - 6));
  const checkins = store.live("habit_checkins");

  return (
    <>
      <div className="main-head">
        <h1>Привычки</h1>
        <div className="spacer" />
        <button className="btn primary" onClick={() => { setEditing(blank.habit({ name: "" })); setIsNew(true); }}><Icon name="plus" size={18} />Привычка</button>
      </div>
      <div className="main-body">
        {!habits.length && <Empty emoji="🌱" text="Добавьте привычку: зарядка, вода, чтение" />}
        {habits.map((h) => {
          const counts = new Map<number, number>();
          for (const c of checkins) if (c.habitId === h.id) counts.set(c.day, Math.max(counts.get(c.day) ?? 0, c.count));
          const stats = habitStats(h, counts, today);
          return (
            <div key={h.id} className="habit">
              <div className="emoji">{h.emoji}</div>
              <div className="info">
                <div style={{ fontWeight: 600 }}>{h.name}</div>
                <div className="muted small">
                  Серия: {stats.current} · Выполнено: {stats.total} · За месяц: {stats.rate}%{h.goal > 1 ? ` · Цель: ${h.goal} в день` : ""}
                </div>
              </div>
              <div className="days">
                {week.map((d) => {
                  const count = actions.checkIn(h.id, d)?.count ?? 0;
                  const on = count >= h.goal;
                  const scheduled = isScheduled(h, d);
                  return (
                    <button key={d} className={`hday ${on ? "on" : ""} ${scheduled ? "" : "off"}`}
                      style={on && cssColor(h.color) ? { background: cssColor(h.color), borderColor: cssColor(h.color) } : undefined}
                      title={`${WEEKDAYS_SHORT[dayOfWeek(d) - 1]}, ${parts(d)[2]}${h.goal > 1 ? ` — ${count}/${h.goal}` : ""}`}
                      onClick={() => actions.setCount(h.id, d, h.goal > 1 ? (count >= h.goal ? 0 : count + 1) : on ? 0 : 1)}>
                      {h.goal > 1 && !on && count > 0 ? count : parts(d)[2]}
                    </button>
                  );
                })}
              </div>
              <MenuButton label={`Привычка «${h.name}»`} items={[
                { label: "Изменить", icon: "edit", onClick: () => { setEditing(h); setIsNew(false); } },
                { label: "В архив", icon: "inbox", onClick: () => actions.saveHabit({ ...h, archived: 1 }, false) },
                {
                  label: "Удалить", icon: "trash", danger: true, onClick: () => {
                    actions.deleteHabit(h);
                    toast({ text: `Привычка «${h.name}» удалена`, undo: () => actions.saveHabit({ ...h, deleted: 0 }, false) });
                  },
                },
              ]} />
            </div>
          );
        })}
      </div>
      {editing && <HabitDialog habit={editing} isNew={isNew} onClose={() => setEditing(null)} />}
    </>
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
    const code = next.size === 7 || next.size === 0 ? "" : [1, 2, 3, 4, 5, 6, 7].filter((x) => next.has(x)).map((x) => CODES[x - 1]).join(",");
    setH({ ...h, days: code });
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
          <input className="field" type="number" min={1} max={99} style={{ width: 80 }} value={h.goal}
            onChange={(e) => setH({ ...h, goal: Math.max(1, Math.min(99, Number(e.target.value) || 1)) })} />
        </label>
        <Swatches value={h.color} onChange={(c) => setH({ ...h, color: c })} />
      </div>
    </Dialog>
  );
}
