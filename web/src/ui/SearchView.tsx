import { useMemo, useState } from "react";
import { type Task } from "../data/model";
import { type SearchKind, type SearchPeriod, SEARCH_KINDS, SEARCH_PERIODS, search } from "../domain/search";
import { Empty } from "./common";
import { useData, useLocal, useNow, useToday } from "./context";
import { formatEventSpan } from "./format";
import { Icon } from "./icons";
import { TaskRow, rowProps, useStable } from "./TaskListView";

/** Search over tasks and events: title, notes and place (SearchScreen + CalendarSearch in the app). */
export function SearchView({ onOpen, onBack }: { onOpen: (t: Task) => void; onBack: () => void }) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow();
  const [text, setText] = useState("");
  const [without, setWithout] = useState("");
  const [kind, setKind] = useLocal<SearchKind>("search.kind", "ALL");
  const [period, setPeriod] = useLocal<SearchPeriod>("search.period", "ANY");
  const [recent, setRecent] = useLocal<string[]>("search.recent", []);
  const [more, setMore] = useState(false);

  const found = useMemo(
    () => search(store.live("tasks"), { text, without, kind, period }, today).slice(0, 300),
    [store.version, text, without, kind, period, today], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const remember = () => {
    const q = text.trim();
    if (q) setRecent([q, ...recent.filter((r) => r !== q)].slice(0, 8));
  };
  const open = useStable((t: Task) => { remember(); onOpen(t); });
  const toggle = useStable((t: Task) => {
    const undo = actions.setDone(t, t.status === "OPEN");
    if (t.status === "OPEN") toast({ text: `«${t.title}» выполнена`, undo });
  });

  return (
    <>
      <div className="main-head">
        <button className="icon-btn" aria-label="Назад" onClick={onBack}><Icon name="back" /></button>
        <div className="quick" style={{ flex: 1, margin: 0 }}>
          <Icon name="search" color="var(--muted)" />
          <input autoFocus value={text} placeholder="Поиск задач и событий" aria-label="Поиск" onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && remember()} />
          {text && <button className="icon-btn" aria-label="Очистить" onClick={() => setText("")}><Icon name="close" size={18} /></button>}
        </div>
      </div>
      <div className="main-body">
        <div className="prop" style={{ marginBottom: 8 }}>
          {SEARCH_KINDS.map((k) => <button key={k.key} className={`chip ${kind === k.key ? "on" : ""}`} onClick={() => setKind(k.key)}>{k.label}</button>)}
          <select className="field" style={{ width: "auto" }} value={period} aria-label="Даты" onChange={(e) => setPeriod(e.target.value as SearchPeriod)}>
            {SEARCH_PERIODS.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
          </select>
          <button className="btn ghost small" onClick={() => setMore(!more)}>{more ? "Меньше" : "Ещё фильтры"}</button>
        </div>
        {more && <input className="field" style={{ marginBottom: 8 }} value={without} placeholder="Без слов (через пробел)" onChange={(e) => setWithout(e.target.value)} />}
        {!text.trim() && recent.length > 0 && (
          <>
            <div className="section-title">Недавние</div>
            <div className="prop">
              {recent.map((r) => <button key={r} className="chip" onClick={() => setText(r)}>{r}</button>)}
              <button className="btn ghost small" onClick={() => setRecent([])}>Очистить</button>
            </div>
          </>
        )}
        {text.trim() && !found.length && <Empty emoji="🔍" text="Ничего не нашлось" />}
        {found.map((t) => t.isEvent ? (
          <div key={t.id} className="task-row" role="button" tabIndex={0} onClick={() => open(t)} onKeyDown={(e) => e.key === "Enter" && open(t)}>
            <Icon name="event" size={18} color="var(--primary)" />
            <span className="title"><Highlight text={t.title} query={text} /></span>
            <span className="meta">{t.repeatRule && <Icon name="repeat" size={14} />}{t.location && <span>{t.location}</span>}<span>{formatEventSpan(t, today)}</span></span>
          </div>
        ) : (
          <TaskRow key={t.id} {...rowProps(store, t, today, now, true, t.displayMode === "COUNTDOWN")} onOpen={open} onToggle={toggle} />
        ))}
      </div>
    </>
  );
}

/** The text with the searched words marked. */
function Highlight({ text, query }: { text: string; query: string }) {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return <>{text}</>;
  const re = new RegExp(`(${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  return <>{text.split(re).map((part, i) => (i % 2 ? <mark key={i}>{part}</mark> : part))}</>;
}
