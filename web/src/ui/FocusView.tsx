import { useEffect, useMemo, useState } from "react";
import { dayOfWeek, parts, plusDays } from "../core/date";
import { localDateOf } from "../domain/dates";
import { dayRange, focusMinutesPerDay } from "../domain/stats";
import { isDone } from "../domain/tasks";
import { Dialog } from "./common";
import { useData, useLocal, useToday } from "./context";
import { MONTHS_NOM, WEEKDAYS_SHORT } from "./format";
import { Icon } from "./icons";
import { BarChart } from "./StatsView";

/**
 * A pomodoro timer like the app's (pomodoro/PomodoroTimer.kt): focus, short break, a long break
 * after every N focuses. Its state lives in this browser (a reload keeps it running); a focus is
 * counted only when its timer runs to the end, and is then saved as a focus session for statistics.
 */
type Phase = "FOCUS" | "SHORT_BREAK" | "LONG_BREAK";
type Status = "IDLE" | "RUNNING" | "PAUSED";

interface Timer {
  phase: Phase;
  status: Status;
  /** RUNNING: when the phase ends. */
  endAt: number;
  /** PAUSED: time left. */
  remainingMs: number;
  /** When the phase was first started, for the saved session. */
  startedAt: number;
  taskId: string | null;
  /** Focuses finished in the current cycle (for the long break). */
  cycleCount: number;
}

interface Settings { focusMin: number; shortMin: number; longMin: number; longEvery: number; sound: boolean }

const DEFAULTS: Settings = { focusMin: 25, shortMin: 5, longMin: 15, longEvery: 4, sound: true };
const IDLE: Timer = { phase: "FOCUS", status: "IDLE", endAt: 0, remainingMs: 0, startedAt: 0, taskId: null, cycleCount: 0 };
const LABELS: Record<Phase, string> = { FOCUS: "Фокус", SHORT_BREAK: "Перерыв", LONG_BREAK: "Длинный перерыв" };

const durationMs = (phase: Phase, s: Settings) => 60_000 * (phase === "FOCUS" ? s.focusMin : phase === "SHORT_BREAK" ? s.shortMin : s.longMin);

/** The phase after [t] (PomodoroTimer.next). */
function next(t: Timer, s: Settings): Timer {
  if (t.phase === "FOCUS") {
    const cycles = t.cycleCount + 1;
    const long = cycles % Math.max(s.longEvery, 1) === 0;
    return { ...t, phase: long ? "LONG_BREAK" : "SHORT_BREAK", status: "IDLE", endAt: 0, remainingMs: 0, cycleCount: long ? 0 : cycles };
  }
  return { ...t, phase: "FOCUS", status: "IDLE", endAt: 0, remainingMs: 0 };
}

export function FocusView({ onStats }: { onStats: () => void }) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [stored, setTimer] = useLocal<Timer>("pomodoro", IDLE);
  const timer = { ...IDLE, ...stored };
  const [settings, setSettings] = useLocal<Settings>("pomodoroSettings", DEFAULTS);
  const s = { ...DEFAULTS, ...settings };
  const [editing, setEditing] = useState(false);
  const [, tick] = useState(0);

  const total = durationMs(timer.phase, s);
  const left = timer.status === "RUNNING" ? Math.max(0, timer.endAt - Date.now()) : timer.status === "PAUSED" ? timer.remainingMs : total;

  useEffect(() => {
    if (timer.status !== "RUNNING") return;
    const id = setInterval(() => tick((n) => n + 1), 500);
    return () => clearInterval(id);
  }, [timer.status]);

  // The phase is over: a finished focus is saved, and the next phase waits for «Старт».
  useEffect(() => {
    if (timer.status !== "RUNNING" || left > 0) return;
    if (timer.phase === "FOCUS") {
      actions.recordFocus(timer.startedAt, timer.startedAt + total, timer.taskId);
      toast({ text: "Помидор засчитан — пора отдохнуть" });
    }
    finished(timer.phase, s.sound);
    setTimer(next(timer, s));
  }, [left, timer.status]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const base = "Tik Tak";
    document.title = timer.status !== "IDLE" ? `${clock(left)} · ${LABELS[timer.phase]}` : base;
    return () => { document.title = base; };
  }, [left, timer.status, timer.phase]);

  const start = () => {
    if ("Notification" in window && Notification.permission === "default") void Notification.requestPermission();
    unlockSound();
    const now = Date.now();
    setTimer({ ...timer, status: "RUNNING", endAt: now + total, startedAt: now });
  };
  const pause = () => setTimer({ ...timer, status: "PAUSED", remainingMs: left });
  const resume = () => setTimer({ ...timer, status: "RUNNING", endAt: Date.now() + timer.remainingMs });
  // Reset writes nothing: only a full timer counts (as in the app since 2.4).
  const reset = () => setTimer({ ...timer, status: "IDLE", endAt: 0, remainingMs: 0 });
  const skip = () => setTimer(next(timer, s));

  const weekStart = plusDays(today, -6);
  const stats = useMemo(() => {
    const sessions = store.live("focus_sessions").filter((x) => x.kind === "FOCUS");
    const todays = sessions.filter((x) => localDateOf(x.startedAt) === today);
    return {
      count: todays.length,
      minutes: Math.floor(todays.reduce((a, x) => a + x.durationMs, 0) / 60_000),
      week: focusMinutesPerDay(sessions, weekStart, 7),
    };
  }, [store.version, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const tasks = store.live("tasks").filter((t) => !isDone(t) && !t.isEvent && !t.parentId && t.displayMode !== "COUNTDOWN")
    .sort((a, b) => a.title.localeCompare(b.title, "ru"));
  const progress = 1 - left / total;
  const r = 110, c = 2 * Math.PI * r;
  const week = dayRange(weekStart, 7);

  return (
    <>
      <div className="main-head">
        <h1>Фокус</h1>
        <div className="spacer" />
        <button className="icon-btn" title="Настройки таймера" aria-label="Настройки таймера" onClick={() => setEditing(true)}><Icon name="settings" /></button>
      </div>
      <div className="main-body">
        <div className="focus-box">
          <div className="seg">
            {(["FOCUS", "SHORT_BREAK", "LONG_BREAK"] as Phase[]).map((p) => (
              <button key={p} className={timer.phase === p ? "on" : ""} disabled={timer.status !== "IDLE" && timer.phase !== p}
                onClick={() => timer.status === "IDLE" && setTimer({ ...timer, phase: p })}>{LABELS[p]}</button>
            ))}
          </div>
          <div className="ring">
            <svg width="240" height="240" viewBox="0 0 240 240">
              <circle cx="120" cy="120" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="10" />
              <circle cx="120" cy="120" r={r} fill="none" stroke="var(--primary)" strokeWidth="10" strokeLinecap="round"
                strokeDasharray={c} strokeDashoffset={c * (1 - progress)} transform="rotate(-90 120 120)" />
            </svg>
            <div className="time">
              {clock(left)}
              <span className="muted small">{timer.status === "RUNNING" ? LABELS[timer.phase] : timer.status === "PAUSED" ? "Пауза" : "Готов к старту"}</span>
            </div>
          </div>
          <select className="field" style={{ width: "min(360px, 100%)" }} value={timer.taskId ?? ""} aria-label="Задача"
            onChange={(e) => setTimer({ ...timer, taskId: e.target.value || null })}>
            <option value="">Без задачи</option>
            {tasks.map((t) => <option key={t.id} value={t.id}>{t.title}</option>)}
          </select>
          <div style={{ display: "flex", gap: 10 }}>
            {timer.status === "IDLE" && <>
              <button className="btn primary" onClick={start}><Icon name="play" size={18} />Старт</button>
              <button className="btn" onClick={skip}>Пропустить</button>
            </>}
            {timer.status === "RUNNING" && <>
              <button className="btn primary" onClick={pause}><Icon name="pause" size={18} />Стоп</button>
              <button className="btn" onClick={reset}><Icon name="stop" size={18} />Сбросить</button>
            </>}
            {timer.status === "PAUSED" && <>
              <button className="btn primary" onClick={resume}><Icon name="play" size={18} />Продолжить</button>
              <button className="btn" onClick={reset}><Icon name="stop" size={18} />Сбросить</button>
            </>}
          </div>
          {timer.phase === "FOCUS" && timer.status !== "IDLE" && <div className="muted small">Помидор засчитается, когда таймер дойдёт до конца</div>}
          <div className="card-box" style={{ width: "min(520px, 100%)" }}>
            <div className="chart-title">Статистика</div>
            <div className="stats tiles">
              <div><b>{stats.count} 🍅</b><span className="muted small">сегодня</span></div>
              <div><b>{stats.minutes}</b><span className="muted small">минут сегодня</span></div>
            </div>
            <div className="muted small">Минуты фокуса за 7 дней</div>
            <BarChart values={stats.week} labels={week.map((d) => WEEKDAYS_SHORT[dayOfWeek(d) - 1])}
              titles={week.map((d) => `${parts(d)[2]} ${MONTHS_NOM[parts(d)[1] - 1].toLowerCase()}`)} current={6} text={(m) => `${m} мин`} />
            <button className="btn ghost" style={{ marginLeft: "auto", display: "flex" }} onClick={onStats}>Вся статистика</button>
          </div>
        </div>
      </div>
      {editing && <SettingsDialog initial={s} onClose={() => setEditing(false)} onSave={(v) => { setSettings(v); setEditing(false); }} />}
    </>
  );
}

function SettingsDialog({ initial, onSave, onClose }: { initial: Settings; onSave: (s: Settings) => void; onClose: () => void }) {
  const [s, set] = useState(initial);
  const stepper = (label: string, key: "focusMin" | "shortMin" | "longMin" | "longEvery", min: number, max: number, step: number) => (
    <div className="prop">
      <span style={{ flex: 1 }}>{label}</span>
      <button className="btn" aria-label={`${label}: меньше`} onClick={() => set({ ...s, [key]: Math.max(min, s[key] - step) })}>−</button>
      <b style={{ minWidth: 28, textAlign: "center" }}>{s[key]}</b>
      <button className="btn" aria-label={`${label}: больше`} onClick={() => set({ ...s, [key]: Math.min(max, s[key] + step) })}>+</button>
    </div>
  );
  return (
    <Dialog title="Таймер" onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" onClick={() => onSave(s)}>Сохранить</button>
    </>}>
      <div className="form">
        {stepper("Фокус, мин", "focusMin", 5, 120, 5)}
        {stepper("Перерыв, мин", "shortMin", 1, 30, 1)}
        {stepper("Длинный перерыв, мин", "longMin", 5, 60, 5)}
        {stepper("Длинный после каждых", "longEvery", 2, 8, 1)}
        <label className="row"><input type="checkbox" checked={s.sound} onChange={(e) => set({ ...s, sound: e.target.checked })} />Звук в конце</label>
      </div>
    </Dialog>
  );
}

function clock(ms: number): string {
  const sec = Math.ceil(ms / 1000);
  return `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
}

let audio: AudioContext | null = null;

/** Browsers play sound only after a click: the context is made on «Старт». */
function unlockSound() {
  try {
    audio ??= new AudioContext();
    void audio.resume();
  } catch { /* no Web Audio */ }
}

/** A notification and three short beeps when a phase ends. */
function finished(phase: Phase, sound: boolean) {
  const [title, body] = phase === "FOCUS" ? ["Фокус завершён", "Пора отдохнуть"] : ["Перерыв окончен", "Можно снова сосредоточиться"];
  try {
    if ("Notification" in window && Notification.permission === "granted") new Notification(title, { body, icon: "./icon-192.png" });
  } catch { /* some browsers only notify from a service worker */ }
  if (!sound || !audio) return;
  try {
    for (let i = 0; i < 3; i++) {
      const at = audio.currentTime + i * 0.35;
      const osc = audio.createOscillator();
      const gain = audio.createGain();
      osc.frequency.value = 880;
      gain.gain.setValueAtTime(0.25, at);
      gain.gain.exponentialRampToValueAtTime(0.001, at + 0.25);
      osc.connect(gain).connect(audio.destination);
      osc.start(at);
      osc.stop(at + 0.3);
    }
  } catch { /* sound is optional */ }
}
