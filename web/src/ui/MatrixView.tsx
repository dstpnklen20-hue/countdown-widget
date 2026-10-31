import { useMemo, useState } from "react";
import { plusDays } from "../core/date";
import { INBOX_ID, type Task } from "../data/model";
import { dueDay } from "../domain/dates";
import { isCountdown, isDone } from "../domain/tasks";
import { useData, useLocal, useNow, useToday } from "./context";
import { PRIORITY_CATEGORY, PRIORITY_COLORS, PRIORITY_NAMES } from "./format";
import { Icon } from "./icons";
import { MenuButton } from "./common";
import { TaskRow, rowProps, useStable } from "./TaskListView";

/** Eisenhower matrix: quadrants by priority, a period filter (domain/Matrix.kt). */
const PERIODS: { key: string; label: string; days: number | null }[] = [
  { key: "all", label: "Все", days: null }, { key: "today", label: "Сегодня", days: 0 }, { key: "3", label: "3 дня", days: 2 },
  { key: "week", label: "Неделя", days: 6 }, { key: "month", label: "Месяц", days: 29 },
];

export function MatrixView({ selected, onOpen }: { selected: string | null; onOpen: (t: Task) => void }) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow();
  const [period, setPeriod] = useLocal("matrixPeriod", "all");
  const [adding, setAdding] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const [shown, setShown] = useState<number[]>([]);
  const [text, setText] = useState("");
  const open = useStable(onOpen);
  const done = useStable((t: Task) => actions.setDone(t, true));
  const days = PERIODS.find((p) => p.key === period)?.days ?? null;

  const tasks = useMemo(() => {
    const last = days == null ? null : plusDays(today, days);
    const lists = new Set([INBOX_ID, ...store.live("task_lists").map((l) => l.id)]);
    return store.live("tasks").filter((t) => !t.parentId && lists.has(t.listId) && !isDone(t) && !t.isEvent && !isCountdown(t) &&
      (last == null || ((d) => d != null && d <= last)(dueDay(t))));
  }, [store.version, today, days]); // eslint-disable-line react-hooks/exhaustive-deps

  const add = (priority: number) => {
    const t = actions.quickAdd(text, days != null ? "today" : "inbox");
    if (t) actions.update({ ...t, priority });
    setText("");
    setAdding(null);
  };

  return (
    <>
      <div className="main-head">
        <h1>Матрица Эйзенхауэра</h1>
        <div className="spacer" />
        <div className="seg">
          {PERIODS.map((p) => <button key={p.key} className={period === p.key ? "on" : ""} onClick={() => setPeriod(p.key)}>{p.label}</button>)}
        </div>
      </div>
      <div className="main-body">
        <div className="matrix">
          {[3, 2, 1, 0].map((p) => {
            const list = tasks.filter((t) => t.priority === p).sort((a, b) => (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity));
            return (
              <div key={p} className={`quad ${over === p ? "over" : ""}`}
                onDragOver={(e) => { e.preventDefault(); setOver(p); }} onDragLeave={() => setOver((o) => (o === p ? null : o))}
                onDrop={(e) => {
                  e.preventDefault();
                  setOver(null);
                  const t = store.get("tasks", e.dataTransfer.getData("text/x-tiktak-task"));
                  if (t && t.priority !== p) {
                    actions.update({ ...t, priority: p });
                    toast({ text: `«${t.title}» → ${PRIORITY_CATEGORY[p]}`, undo: () => actions.update({ ...t, updatedAt: store.get("tasks", t.id)?.updatedAt ?? t.updatedAt }) });
                  }
                }}>
                <h3>
                  <span className="dot" style={{ background: PRIORITY_COLORS[p] ?? "var(--faint)" }} />
                  {PRIORITY_CATEGORY[p]}
                  <span className="muted small" style={{ fontWeight: 400 }}>{PRIORITY_NAMES[p]}</span>
                  <div className="spacer" />
                  <button className="icon-btn" aria-label="Добавить" onClick={() => setAdding(p)}><Icon name="plus" size={18} /></button>
                </h3>
                {adding === p && (
                  <input className="field" autoFocus value={text} placeholder="Новая задача" onChange={(e) => setText(e.target.value)}
                    onBlur={() => !text && setAdding(null)} onKeyDown={(e) => { if (e.key === "Enter") add(p); if (e.key === "Escape") setAdding(null); }} />
                )}
                {list.slice(0, shown.includes(p) ? undefined : 100).map((t) => (
                  <div key={t.id} draggable className="kcard" onDragStart={(e) => { e.dataTransfer.setData("text/x-tiktak-task", t.id); e.dataTransfer.effectAllowed = "move"; }}>
                    <TaskRow {...rowProps(store, t, today, now, true)} selected={selected === t.id} onOpen={open} onToggle={done} />
                    <MenuButton label="Переместить в" icon="matrix" items={[3, 2, 1, 0].filter((q) => q !== t.priority).map((q) => ({
                      label: PRIORITY_CATEGORY[q], onClick: () => actions.update({ ...t, priority: q }),
                    }))} />
                  </div>
                ))}
                {list.length > 100 && !shown.includes(p) && (
                  <button className="btn ghost small" onClick={() => setShown([...shown, p])}>Показать все ({list.length})</button>
                )}
                {!list.length && adding !== p && <div className="muted small" style={{ padding: 8 }}>Пусто</div>}
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}
