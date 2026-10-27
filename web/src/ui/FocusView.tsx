import { useEffect, useMemo, useState } from "react";
import { localDateOf } from "../domain/dates";
import { isDone } from "../domain/tasks";
import { useData, useLocal, useToday } from "./context";
import { formatDuration } from "./format";
import { Icon } from "./icons";

/**
 * A pomodoro timer. Its state lives in this browser (a reload keeps it running); a finished focus
 * is saved as a focus session, so the app's statistics count it too.
 */
interface Timer {
  kind: "FOCUS" | "SHORT_BREAK";
  /** When the current run ends (epoch ms), or null when paused. */
  endsAt: number | null;
  /** Time left while paused (ms). */
  left: number;
  /** When this focus began, for the saved session. */
  startedAt: number | null;
  taskId: string | null;
}

const LENGTH = { FOCUS: 25 * 60_000, SHORT_BREAK: 5 * 60_000 };
const idle = (kind: Timer["kind"] = "FOCUS", taskId: string | null = null): Timer => ({ kind, endsAt: null, left: LENGTH[kind], startedAt: null, taskId });

export function FocusView() {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [timer, setTimer] = useLocal<Timer>("focusTimer", idle());
  const [, tick] = useState(0);

  const left = timer.endsAt != null ? Math.max(0, timer.endsAt - Date.now()) : timer.left;
  const running = timer.endsAt != null;

  useEffect(() => {
    if (!running) return;
    const id = setInterval(() => tick((n) => n + 1), 500);
    return () => clearInterval(id);
  }, [running]);

  // The run is over: save a finished focus and offer a break (or back to focus).
  useEffect(() => {
    if (!running || left > 0) return;
    if (timer.kind === "FOCUS" && timer.startedAt != null) {
      actions.recordFocus(timer.startedAt, Date.now(), timer.taskId);
      notify("Фокус завершён", "Пора отдохнуть 5 минут");
      toast({ text: "Фокус завершён — сохранён в статистике" });
      setTimer(idle("SHORT_BREAK", timer.taskId));
    } else {
      notify("Перерыв окончен", "Можно снова сосредоточиться");
      setTimer(idle("FOCUS", timer.taskId));
    }
  }, [left, running]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const base = "Tik Tak";
    document.title = running ? `${clock(left)} · ${timer.kind === "FOCUS" ? "Фокус" : "Перерыв"}` : base;
    return () => { document.title = base; };
  }, [left, running, timer.kind]);

  const start = () => {
    if ("Notification" in window && Notification.permission === "default") void Notification.requestPermission();
    setTimer({ ...timer, endsAt: Date.now() + left, startedAt: timer.startedAt ?? Date.now() });
  };
  const pause = () => setTimer({ ...timer, endsAt: null, left });
  const stop = () => {
    // A focus stopped after at least a minute still counts, as in the app.
    if (timer.kind === "FOCUS" && timer.startedAt != null && LENGTH.FOCUS - left >= 60_000) actions.recordFocus(timer.startedAt, Date.now(), timer.taskId);
    setTimer(idle("FOCUS", timer.taskId));
  };

  const stats = useMemo(() => {
    const sessions = store.live("focus_sessions").filter((s) => s.kind === "FOCUS");
    const todays = sessions.filter((s) => localDateOf(s.startedAt) === today);
    const minutes = (list: typeof sessions) => Math.round(list.reduce((a, s) => a + s.durationMs, 0) / 60_000);
    return { todayCount: todays.length, todayMinutes: minutes(todays), totalMinutes: minutes(sessions) };
  }, [store.version, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const tasks = store.live("tasks").filter((t) => !isDone(t) && !t.isEvent && !t.parentId).sort((a, b) => a.title.localeCompare(b.title));
  const total = LENGTH[timer.kind];
  const progress = 1 - left / total;
  const r = 110, c = 2 * Math.PI * r;

  return (
    <>
      <div className="main-head"><h1>Фокус</h1></div>
      <div className="main-body">
        <div className="focus-box">
          <div className="seg">
            <button className={timer.kind === "FOCUS" ? "on" : ""} disabled={running} onClick={() => setTimer(idle("FOCUS", timer.taskId))}>Фокус 25 мин</button>
            <button className={timer.kind === "SHORT_BREAK" ? "on" : ""} disabled={running} onClick={() => setTimer(idle("SHORT_BREAK", timer.taskId))}>Перерыв 5 мин</button>
          </div>
          <div className="ring">
            <svg width="240" height="240" viewBox="0 0 240 240">
              <circle cx="120" cy="120" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="10" />
              <circle cx="120" cy="120" r={r} fill="none" stroke="var(--primary)" strokeWidth="10" strokeLinecap="round"
                strokeDasharray={c} strokeDashoffset={c * (1 - progress)} transform="rotate(-90 120 120)" />
            </svg>
            <div className="time">{clock(left)}</div>
          </div>
          <select className="field" style={{ width: "min(360px, 100%)" }} value={timer.taskId ?? ""} aria-label="Задача"
            onChange={(e) => setTimer({ ...timer, taskId: e.target.value || null })}>
            <option value="">Без задачи</option>
            {tasks.map((t) => <option key={t.id} value={t.id}>{t.title}</option>)}
          </select>
          <div style={{ display: "flex", gap: 10 }}>
            {running
              ? <button className="btn" onClick={pause}><Icon name="pause" size={18} />Пауза</button>
              : <button className="btn primary" onClick={start}><Icon name="play" size={18} />{left < total ? "Продолжить" : "Старт"}</button>}
            {(running || left < total) && <button className="btn" onClick={stop}><Icon name="stop" size={18} />Стоп</button>}
          </div>
          <div className="stats">
            <div><b>{stats.todayCount}</b><span className="muted small">фокусов сегодня</span></div>
            <div><b>{formatDuration(stats.todayMinutes)}</b><span className="muted small">сегодня</span></div>
            <div><b>{formatDuration(stats.totalMinutes)}</b><span className="muted small">всего</span></div>
          </div>
        </div>
      </div>
    </>
  );
}

function clock(ms: number): string {
  const s = Math.ceil(ms / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function notify(title: string, body: string) {
  try {
    if ("Notification" in window && Notification.permission === "granted") new Notification(title, { body, icon: "./icon-192.png" });
  } catch { /* some browsers only notify from a service worker */ }
}
