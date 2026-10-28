import { useEffect, useMemo, useState } from "react";
import { Actions } from "../data/actions";
import { Store } from "../data/store";
import { type Session, signIn, signOut, signUp } from "../data/supabase";
import type { FilterKey } from "../domain/tasks";
import type { Task } from "../data/model";
import { CalendarView } from "./CalendarView";
import { type AppContext, Ctx, type Toast, useData, useLocal } from "./context";
import { EventEditor, type EventDraft } from "./EventEditor";
import { FocusView } from "./FocusView";
import { HabitsView } from "./HabitsView";
import { Icon } from "./icons";
import { MatrixView } from "./MatrixView";
import { SettingsView, applyTheme } from "./SettingsView";
import { Sidebar } from "./Sidebar";
import { TaskDetail } from "./TaskDetail";
import { TaskListView } from "./TaskListView";
import { TrashView } from "./TrashView";

const SESSION_KEY = "tiktak.session";

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) ?? "null");
  } catch {
    return null;
  }
}

function saveSession(s: Session | null) {
  try {
    if (s) localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else localStorage.removeItem(SESSION_KEY);
  } catch { /* storage refused: the session lives until the tab closes */ }
}

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  useEffect(() => applyTheme(), []);
  const onSession = (s: Session | null) => {
    saveSession(s);
    setSession(s);
  };
  if (!session) return <Login onSession={onSession} />;
  return <Workspace key={session.userId} session={session} onSession={onSession} />;
}

function Login({ onSession }: { onSession: (s: Session) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  async function run(create: boolean) {
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const s = create ? await signUp(email, password) : await signIn(email, password);
      if (s) onSession(s);
      else setInfo("Аккаунт создан. Подтвердите почту по ссылке из письма и войдите.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="card form" onSubmit={(e) => { e.preventDefault(); void run(false); }}>
        <img src="./icon.svg" width={48} height={48} alt="" />
        <h1>Tik Tak</h1>
        <p className="muted" style={{ margin: "0 0 8px" }}>
          Войдите тем же аккаунтом, что и в приложении на телефоне (Настройки → Синхронизация): задачи, календарь и привычки будут общими.
        </p>
        <input className="field" type="email" placeholder="Почта" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="field" type="password" placeholder="Пароль" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <div className="error-text">{error}</div>}
        {info && <div className="muted">{info}</div>}
        <button className="btn primary" type="submit" disabled={busy} style={{ justifyContent: "center" }}>{busy ? "Вход…" : "Войти"}</button>
        <button className="btn ghost" type="button" disabled={busy || !email || password.length < 6} onClick={() => void run(true)} style={{ justifyContent: "center" }}>
          Создать аккаунт
        </button>
      </form>
    </div>
  );
}

export type Section = "tasks" | "calendar" | "matrix" | "habits" | "focus" | "trash" | "settings";

const SECTIONS: { key: Section; icon: string; label: string }[] = [
  { key: "tasks", icon: "tasks", label: "Задачи" },
  { key: "calendar", icon: "calendar", label: "Календарь" },
  { key: "matrix", icon: "matrix", label: "Матрица" },
  { key: "habits", icon: "habits", label: "Привычки" },
  { key: "focus", icon: "focus", label: "Фокус" },
];

function Workspace({ session, onSession }: { session: Session; onSession: (s: Session | null) => void }) {
  const store = useMemo(() => new Store(session, onSession), []); // eslint-disable-line react-hooks/exhaustive-deps
  const actions = useMemo(() => new Actions(store), [store]);
  const [toast, setToast] = useState<Toast | null>(null);
  useEffect(() => {
    void store.start(() => actions.ensureBasics());
    return () => store.stop();
  }, [store, actions]);
  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(id);
  }, [toast]);
  const ctx: AppContext = useMemo(() => ({ store, actions, toast: setToast }), [store, actions]);

  async function logout() {
    await signOut(session);
    store.stop();
    await store.clearCache();
    onSession(null);
  }

  return (
    <Ctx.Provider value={ctx}>
      <Shell onLogout={logout} />
      {toast && (
        <div className="toast" role="status">
          <span>{toast.text}</span>
          {toast.undo && <button onClick={() => { toast.undo!(); setToast(null); }}>Отменить</button>}
        </div>
      )}
    </Ctx.Provider>
  );
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const { store } = useData();
  const [section, setSection] = useLocal<Section>("section", "tasks");
  const [filter, setFilter] = useLocal<FilterKey>("filter", "today");
  const [selected, setSelected] = useState<string | null>(null);
  const [event, setEvent] = useState<EventDraft | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  /** The calendar takes the whole width: the rail slides away until its ☰ button calls it back. */
  const [railShown, setRailShown] = useState(false);

  /** Tasks open in the side panel, events in their editor. */
  const open = (task: Task, occurrence?: string) => {
    if (task.isEvent) setEvent({ task, occurrence });
    else setSelected(task.id);
  };
  const go = (s: Section) => {
    setSection(s);
    setSelected(null);
    setMenuOpen(false);
    setRailShown(false);
  };
  const selectedTask = store.get("tasks", selected);
  // A task turned into an event moves to the event editor.
  useEffect(() => {
    if (selectedTask?.isEvent) {
      setEvent({ task: selectedTask });
      setSelected(null);
    }
  }, [selectedTask]);

  const status = store.status;
  if (status.state === "loading" && !store.all("task_lists").length) {
    return <div className="empty"><div className="big">⏳</div>Загружаю ваши задачи…</div>;
  }

  return (
    <div className={`app ${section === "calendar" && !railShown ? "rail-hidden" : ""}`}>
      <nav className="rail" aria-label="Разделы">
        <img className="logo" src="./icon.svg" alt="Tik Tak" />
        {SECTIONS.map((s) => (
          <button key={s.key} className={`icon-btn ${section === s.key ? "active" : ""}`} title={s.label} aria-label={s.label} onClick={() => go(s.key)}>
            <Icon name={s.icon} size={22} />
          </button>
        ))}
        <div className="spacer rail-extra" />
        <button className={`icon-btn ${section === "settings" ? "active" : ""}`} title="Настройки" aria-label="Настройки" onClick={() => go("settings")}>
          <Icon name="settings" size={22} />
        </button>
      </nav>

      {section === "tasks" && (
        <>
          {menuOpen && <div className="scrim mobile-only" onClick={() => setMenuOpen(false)} />}
          <Sidebar
            open={menuOpen}
            filter={filter}
            onFilter={(f) => { setFilter(f); setSelected(null); setMenuOpen(false); }}
            onTrash={() => go("trash")}
          />
        </>
      )}

      <main className="main">
        {section === "tasks" && <TaskListView filter={filter} selected={selected} onOpen={open} onMenu={() => setMenuOpen(true)} />}
        {section === "calendar" && <CalendarView onOpen={open} onNewEvent={(d) => setEvent(d)} onMenu={() => setRailShown((v) => !v)} />}
        {section === "matrix" && <MatrixView selected={selected} onOpen={open} />}
        {section === "habits" && <HabitsView />}
        {section === "focus" && <FocusView />}
        {section === "trash" && <TrashView onBack={() => go("tasks")} />}
        {section === "settings" && <SettingsView onLogout={onLogout} />}
      </main>

      {selectedTask && !selectedTask.deleted && !selectedTask.isEvent && (
        <aside className="detail">
          <TaskDetail task={selectedTask} onClose={() => setSelected(null)} onOpen={(t) => open(t)} />
        </aside>
      )}
      {event && <EventEditor draft={event} onClose={() => setEvent(null)} onTask={(id) => { setEvent(null); setSection(section === "calendar" ? "calendar" : "tasks"); setSelected(id); }} />}
    </div>
  );
}
