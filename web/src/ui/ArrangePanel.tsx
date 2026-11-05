import { useEffect, useMemo, useRef, useState } from "react";
import { cssColor, INBOX_ID, type Task } from "../data/model";
import { dueDay } from "../domain/dates";
import { isCountdown, isDone } from "../domain/tasks";
import { useData, useLocal, useToday } from "./context";
import { PRIORITY_CATEGORY, formatDay } from "./format";
import { Icon } from "./icons";

/** The type of a task carried by the mouse (also from the Kanban board). */
export const TASK_DRAG = "text/x-tiktak-task";
/** Set beside [TASK_DRAG] when the task comes from the calendar: the panel takes it back. */
export const FROM_CALENDAR = "text/x-tiktak-from-calendar";

type GroupBy = "list" | "tag" | "priority";
type Scope = "unplanned" | "all";
const GROUP_ROWS = 100;

interface Group { key: string; title: string; color?: string; tasks: Task[] }

/**
 * Tasks to place on the calendar, as TickTick's "Arrange tasks": by list, tag or priority. A task
 * is dragged onto a time of the grid or into the all-day row. "Без времени" leaves out what already
 * has a time, so what is left to plan is what is shown.
 */
export function ArrangePanel({ onOpen, onClose, onUnplan }: { onOpen: (t: Task, at: DOMRect) => void; onClose: () => void; onUnplan: (taskId: string) => void }) {
  const { store } = useData();
  const today = useToday();
  const [by, setBy] = useLocal<GroupBy>("arrange.by", "list");
  const [scope, setScope] = useLocal<Scope>("arrange.scope", "unplanned");
  const [hiddenLists, setHiddenLists] = useLocal<string[]>("arrange.hiddenLists", []);
  const [collapsed, setCollapsed] = useLocal<string[]>("arrange.collapsed", []);
  const [expanded, setExpanded] = useState<string[]>([]);
  const [shown, setShown] = useState<Record<string, number>>({});
  const [listMenu, setListMenu] = useState(false);
  const [over, setOver] = useState(false);

  const lists = useMemo(() => store.live("task_lists").sort((a, b) => Number(b.isInbox) - Number(a.isInbox) || a.sortOrder - b.sortOrder), [store.version]); // eslint-disable-line react-hooks/exhaustive-deps
  const listName = (l: { id: string; isInbox: number; name: string }) => (l.isInbox || l.id === INBOX_ID ? "Входящие" : l.name);

  const groups = useMemo((): Group[] => {
    const hidden = new Set(hiddenLists);
    const open = store.live("tasks").filter((t) => !t.parentId && !isDone(t) && !t.isEvent && !isCountdown(t) && !hidden.has(t.listId) &&
      (scope === "all" || t.dueAt == null || !!t.isAllDay))
      .sort((a, b) => a.sortOrder - b.sortOrder);
    if (by === "list") {
      return lists.filter((l) => !hidden.has(l.id)).map((l) => ({
        key: `list:${l.id}`, title: listName(l), color: cssColor(l.color), tasks: open.filter((t) => t.listId === l.id),
      })).filter((g) => g.tasks.length);
    }
    if (by === "priority") {
      return [3, 2, 1, 0].map((p) => ({ key: `priority:${p}`, title: PRIORITY_CATEGORY[p], tasks: open.filter((t) => t.priority === p) })).filter((g) => g.tasks.length);
    }
    const links = store.index("task_tags", "tagId");
    const tagged = new Set<string>();
    const out: Group[] = store.live("tags").sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name)).map((tag) => {
      const ids = new Set((links.get(tag.id) ?? []).map((l) => l.taskId));
      ids.forEach((id) => tagged.add(id));
      return { key: `tag:${tag.id}`, title: `#${tag.name}`, color: cssColor(tag.color), tasks: open.filter((t) => ids.has(t.id)) };
    }).filter((g) => g.tasks.length);
    const rest = open.filter((t) => !tagged.has(t.id));
    return rest.length ? [...out, { key: "tag:none", title: "Без тега", tasks: rest }] : out;
  }, [store.version, by, scope, hiddenLists, lists]); // eslint-disable-line react-hooks/exhaustive-deps

  const colorOf = (t: Task, group: Group) => cssColor(t.color) ?? cssColor(store.get("task_lists", t.listId)?.color) ?? group.color ?? "var(--faint)";
  const row = (t: Task, group: Group, depth: number) => {
    const subs = (store.index("tasks", "parentId").get(t.id) ?? []).filter((s) => !isDone(s));
    const open = expanded.includes(t.id);
    const day = dueDay(t);
    const color = colorOf(t, group);
    return (
      <div key={t.id}>
        <div className="arr-row" draggable={depth === 0} style={{ marginLeft: depth * 14, background: `color-mix(in srgb, ${color} 55%, var(--bg))` }}
          title={depth === 0 ? "Перетащите на нужное время" : t.title}
          onDragStart={(e) => { e.dataTransfer.setData(TASK_DRAG, t.id); e.dataTransfer.effectAllowed = "move"; }}
          onClick={(ev) => onOpen(t, ev.currentTarget.getBoundingClientRect())}>
          <span className="arr-title">{t.title || "(без названия)"}</span>
          {day && <span className="arr-day">{formatDay(day, today)}</span>}
          {subs.length > 0 && (
            <button className="arr-more" aria-label={open ? "Свернуть подзадачи" : "Показать подзадачи"} aria-expanded={open}
              onClick={(e) => { e.stopPropagation(); setExpanded(open ? expanded.filter((x) => x !== t.id) : [...expanded, t.id]); }}>
              <Icon name={open ? "left" : "right"} size={14} />
            </button>
          )}
        </div>
        {open && subs.map((s) => row(s, group, depth + 1))}
      </div>
    );
  };

  const visible = lists.filter((l) => !hiddenLists.includes(l.id));
  const listLabel = visible.length === lists.length ? "Все списки"
    : visible.length === 0 ? "Списки не выбраны"
      : visible.length === 1 ? listName(visible[0]) : `${listName(visible[0])}, ещё ${visible.length - 1}`;

  return (
    <aside className={`arrange ${over ? "drop-here" : ""}`} aria-label="Распланировать задачи"
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes(FROM_CALENDAR)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
        setOver(true);
      }}
      onDragLeave={(e) => !e.currentTarget.contains(e.relatedTarget as Node) && setOver(false)}
      onDrop={(e) => {
        setOver(false);
        const id = e.dataTransfer.types.includes(FROM_CALENDAR) ? e.dataTransfer.getData(TASK_DRAG) : "";
        if (!id) return;
        e.preventDefault();
        onUnplan(id);
      }}>
      <div className="arrange-head">
        <b>Распланировать</b>
        <div className="spacer" />
        <button className="icon-btn" aria-label="Закрыть панель" onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <div className="seg arrange-seg">
        {([["list", "Список"], ["tag", "Тег"], ["priority", "Приоритет"]] as [GroupBy, string][]).map(([k, label]) => (
          <button key={k} className={by === k ? "on" : ""} onClick={() => setBy(k)}>{label}</button>
        ))}
      </div>
      <div className="arrange-filters">
        <ListFilter open={listMenu} setOpen={setListMenu} label={listLabel}>
          {lists.map((l) => (
            <label key={l.id} className="row layer-check">
              <input type="checkbox" checked={!hiddenLists.includes(l.id)} onChange={(e) => setHiddenLists(e.target.checked ? hiddenLists.filter((x) => x !== l.id) : [...hiddenLists, l.id])} />
              {listName(l)}
            </label>
          ))}
        </ListFilter>
        <select className="field" aria-label="Какие задачи" value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
          <option value="unplanned">Без времени</option>
          <option value="all">Все открытые</option>
        </select>
      </div>
      <div className="arrange-body">
        {!groups.length && <div className="muted small" style={{ padding: 12 }}>{scope === "unplanned" ? "Все задачи уже распланированы" : "Задач нет"}</div>}
        {groups.map((g) => {
          const shut = collapsed.includes(g.key);
          const limit = shown[g.key] ?? GROUP_ROWS;
          return (
            <section key={g.key}>
              <button className="group-head" aria-expanded={!shut} onClick={() => setCollapsed(shut ? collapsed.filter((k) => k !== g.key) : [...collapsed, g.key])}>
                <Icon name={shut ? "right" : "left"} size={14} />
                <span className="arr-group">{g.title}</span>
                <span className="count">{g.tasks.length}</span>
              </button>
              {!shut && g.tasks.slice(0, limit).map((t) => row(t, g, 0))}
              {!shut && g.tasks.length > limit && (
                <button className="btn ghost small" onClick={() => setShown({ ...shown, [g.key]: limit + 300 })}>Показать ещё {g.tasks.length - limit}</button>
              )}
            </section>
          );
        })}
      </div>
    </aside>
  );
}

function ListFilter({ open, setOpen, label, children }: { open: boolean; setOpen: (v: boolean) => void; label: string; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [open, setOpen]);
  return (
    <div ref={ref} style={{ position: "relative", flex: 1, minWidth: 0 }}>
      <button className="btn" style={{ width: "100%", justifyContent: "space-between" }} aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="arr-group">{label}</span><Icon name="right" size={14} />
      </button>
      {open && <div className="menu" style={{ left: 0, right: 0, top: "100%", maxHeight: 320, overflowY: "auto" }}>{children}</div>}
    </div>
  );
}
