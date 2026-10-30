import { memo, useCallback, useMemo, useRef, useState } from "react";
import { formatTime } from "../core/date";
import { QuickAddParser } from "../core/quickAdd";
import { describe } from "../core/repeatText";
import { cssColor, INBOX_ID, type Task } from "../data/model";
import type { Store } from "../data/store";
import { dueDay, nowMinute } from "../domain/dates";
import { type FilterKey, type TaskGroup, type TaskSort, SORTS, groupTasks, isDone, isOverdue, matches } from "../domain/tasks";
import { Check, Empty, MenuButton } from "./common";
import { useData, useLocal, useNow, useToday } from "./context";
import { PRIORITY_NAMES, daysLeft, formatDay, formatDue } from "./format";
import { Icon } from "./icons";
import { filterTitle } from "./Sidebar";
import { KanbanBoard } from "./KanbanBoard";

const GROUP_TITLES: Record<string, string> = {
  OVERDUE: "Просрочено", TODAY: "Сегодня", TOMORROW: "Завтра", LATER: "Позже", NO_DATE: "Без даты", DONE: "Выполнено", ALL: "Задачи",
};

export function TaskListView({ filter, selected, onOpen, onMenu }: {
  filter: FilterKey; selected: string | null; onOpen: (t: Task) => void; onMenu: () => void;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow();
  const [sort, setSort] = useLocal<TaskSort>(`sort:${filter}`, "DATE");
  const [collapsed, setCollapsed] = useLocal<string[]>(`collapsed:${filter}`, ["DONE:"]);

  const lists = store.live("task_lists");
  const listOrder = useMemo(() => new Map(lists.sort((a, b) => a.sortOrder - b.sortOrder).map((l, i) => [l.id, l.isInbox ? -1 : i])), [store.version]); // eslint-disable-line react-hooks/exhaustive-deps

  const groups = useMemo(() => {
    const listIds = new Set([INBOX_ID, ...lists.map((l) => l.id)]);
    const tagLinks = new Map<string, Set<string>>();
    for (const l of store.live("task_tags")) tagLinks.set(l.taskId, (tagLinks.get(l.taskId) ?? new Set()).add(l.tagId));
    const tasks = store.live("tasks").filter((t) => !t.parentId && listIds.has(t.listId) && matches(filter, t, tagLinks.get(t.id) ?? new Set(), today));
    return groupTasks(filter, tasks, now, today, sort, listOrder);
  }, [store.version, filter, today, now, sort]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = useStable((t: Task) => {
    const done = !isDone(t);
    const undo = actions.setDone(t, done);
    if (done) toast({ text: `«${t.title}» выполнена`, undo });
  });
  const open = useStable(onOpen);
  const [shown, setShown] = useState<Record<string, number>>({});

  const title = filterTitle(store, filter);
  const list = filter.startsWith("list:") ? store.get("task_lists", filter.slice(5)) : undefined;
  const kanban = list?.viewMode === "KANBAN";
  const groupTitle = (g: TaskGroup) => {
    if (g.kind === "DAY" && g.date) return formatDay(g.date, today);
    if (g.kind === "PRIORITY") return g.priority ? `${PRIORITY_NAMES[g.priority!]} приоритет` : PRIORITY_NAMES[0];
    if (g.kind === "LIST") return lists.find((l) => l.id === g.listId)?.isInbox ? "Входящие" : lists.find((l) => l.id === g.listId)?.name ?? "Список";
    return GROUP_TITLES[g.kind] ?? "";
  };

  return (
    <>
      <div className="main-head">
        <button className="icon-btn mobile-only" aria-label="Списки" onClick={onMenu}><Icon name="menu" /></button>
        <h1>{title}</h1>
        <div className="spacer" />
        {list && (
          <button className={`icon-btn ${kanban ? "active" : ""}`} title={kanban ? "Обычный список" : "Канбан: колонки по разделам"} aria-label={kanban ? "Обычный список" : "Канбан"}
            onClick={() => actions.updateList({ ...list, viewMode: kanban ? "LIST" : "KANBAN" })}><Icon name={kanban ? "all" : "kanban"} /></button>
        )}
        {!kanban && <MenuButton label="Сортировка" icon="all" items={SORTS.map((s) => ({ label: (s.key === sort ? "✓ " : "") + s.label, onClick: () => setSort(s.key) }))} />}
      </div>
      {kanban && list ? <KanbanBoard list={list} selected={selected} onOpen={open} onToggle={toggle} /> : (
      <div className="main-body">
        {filter !== "completed" && <QuickAdd filter={filter} onAdded={(t) => toast({ text: `Добавлено: ${t.title}${t.dueAt ? " · " + formatDue(t, today) : ""}` })} />}
        {groups.length === 0 && (
          <Empty emoji={filter === "completed" ? "🏁" : filter === "countdowns" ? "⏳" : "🌿"} text={
            filter === "completed" ? "Выполненных задач пока нет" : filter === "countdowns" ? "Здесь живут события с отсчётом: «Отпуск 15 июля»" : "Задач нет — можно отдохнуть"
          } />
        )}
        {groups.map((g) => {
          const isCollapsed = collapsed.includes(g.key);
          return (
            <section key={g.key}>
              <button className="group-head" onClick={() => setCollapsed(isCollapsed ? collapsed.filter((k) => k !== g.key) : [...collapsed, g.key])}>
                <Icon name={isCollapsed ? "right" : "left"} size={16} />
                {groupTitle(g)} <span className="count">{g.tasks.length}</span>
              </button>
              {!isCollapsed && g.tasks.slice(0, shown[g.key] ?? PAGE_ROWS).map((t) => (
                <TaskRow key={t.id} {...rowProps(store, t, today, now, !filter.startsWith("list:"), filter === "countdowns")} selected={selected === t.id}
                  onOpen={open} onToggle={toggle} />
              ))}
              {!isCollapsed && g.tasks.length > (shown[g.key] ?? PAGE_ROWS) && (
                <button className="btn ghost small" style={{ margin: "4px 0 8px 36px" }}
                  onClick={() => setShown({ ...shown, [g.key]: (shown[g.key] ?? PAGE_ROWS) + 500 })}>
                  Показать ещё {g.tasks.length - (shown[g.key] ?? PAGE_ROWS)}
                </button>
              )}
            </section>
          );
        })}
      </div>
      )}
    </>
  );
}

/** What a line of a task shows, worked out by the list (so a line redraws only when it changes). */
export interface RowProps {
  task: Task;
  due: string | null;
  overdue: boolean;
  color?: string;
  listName: string | null;
  checklist: string | null;
  subtasks: string | null;
  /** Tag names, one per line. */
  tags: string;
  countdown?: string | null;
  selected?: boolean;
  onOpen: (t: Task) => void;
  onToggle: (t: Task) => void;
}

const NL = String.fromCharCode(10);

/** The props of [t]'s line, read from the store's indexes. */
export function rowProps(store: Store, t: Task, today: string, now: number, showList: boolean, countdown = false): Omit<RowProps, "onOpen" | "onToggle" | "selected"> {
  const list = store.get("task_lists", t.listId);
  const checklist = store.index("checklist_items", "taskId").get(t.id) ?? [];
  const subtasks = store.index("tasks", "parentId").get(t.id) ?? [];
  const tags = (store.index("task_tags", "taskId").get(t.id) ?? []).map((l) => store.get("tags", l.tagId)).filter((x) => x && !x.deleted).map((x) => x!.name);
  const day = countdown ? dueDay(t) : null;
  return {
    task: t,
    due: formatDue(t, today),
    overdue: isOverdue(t, now, today),
    color: cssColor(t.color) ?? cssColor(list?.color),
    listName: showList && list && !list.isInbox ? list.name : null,
    checklist: checklist.length ? `${checklist.filter((c) => c.checked).length}/${checklist.length}` : null,
    subtasks: subtasks.length ? `${subtasks.filter(isDone).length}/${subtasks.length}` : null,
    tags: tags.join(NL),
    countdown: day ? daysLeft(day, today) : countdown ? "" : null,
  };
}

export const TaskRow = memo(function TaskRow({ task: t, due, overdue, color, listName, checklist, subtasks, tags, countdown, selected, onOpen, onToggle }: RowProps) {
  const done = isDone(t);
  return (
    <div className={`task-row ${done ? "done" : ""} ${selected ? "selected" : ""}`} onClick={() => onOpen(t)} role="button" tabIndex={0}
      onKeyDown={(e) => e.key === "Enter" && onOpen(t)}>
      {color && <span className="bar" style={{ background: color }} />}
      {countdown != null ? <Icon name="hourglass" size={18} color="var(--primary)" /> : <Check done={done} priority={t.priority} onToggle={() => onToggle(t)} />}
      <span className="title">{t.title || "(без названия)"}</span>
      <span className="meta">
        {tags && tags.split(NL).map((tag) => <span key={tag} className="chip">#{tag}</span>)}
        {checklist && <span>☑ {checklist}</span>}
        {subtasks && <span>↳ {subtasks}</span>}
        {t.repeatRule && <Icon name="repeat" size={14} />}
        {listName && <span>{listName}</span>}
        {countdown ? <span style={{ color: "var(--primary)", fontWeight: 600 }}>{countdown}</span>
          : due && <span className={overdue ? "overdue" : ""}>{due}</span>}
      </span>
    </div>
  );
});

/** A function that keeps its identity across renders but always calls the latest [fn]. */
export function useStable<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R {
  const ref = useRef(fn);
  ref.current = fn;
  return useCallback((...args: A) => ref.current(...args), []);
}

/** Long groups show their first lines and a button for the rest, so a big list opens at once. */
export const PAGE_ROWS = 150;

/** One line to add a task, read like the app's quick add: "завтра в 10 позвонить !высокий #работа". */
function QuickAdd({ filter, onAdded }: { filter: FilterKey; onAdded: (t: Task) => void }) {
  const { actions } = useData();
  const today = useToday();
  const [text, setText] = useState("");
  const parsed = useMemo(() => (text.trim() ? new QuickAddParser(today, nowMinute()).parse(text) : null), [text, today]);
  const add = () => {
    const t = actions.quickAdd(text, filter);
    if (t) {
      setText("");
      onAdded(t);
    }
  };
  return (
    <>
      <div className="quick">
        <Icon name="plus" color="var(--primary)" />
        <input value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === "Enter" && add()}
          placeholder={filter === "countdowns" ? "Событие с отсчётом: «Отпуск 15 июля»" : "Добавить задачу: «завтра в 10 позвонить !высокий #работа»"}
          aria-label="Новая задача" />
        {text.trim() && <button className="btn primary" onClick={add}>Добавить</button>}
      </div>
      {parsed && (parsed.date || parsed.priority != null || parsed.tags.length || parsed.repeat || parsed.listName) && (
        <div className="quick-hints">
          {parsed.date && <span className="chip on">📅 {formatDay(parsed.date, today)}{parsed.time != null ? `, ${formatTime(parsed.time)}` : ""}</span>}
          {parsed.repeat && <span className="chip on">🔁 {describe(parsed.repeat)}</span>}
          {parsed.priority != null && <span className="chip on">⚑ {PRIORITY_NAMES[parsed.priority]}</span>}
          {parsed.listName && <span className="chip on">≡ {parsed.listName}</span>}
          {parsed.tags.map((tag) => <span key={tag} className="chip on">#{tag}</span>)}
        </div>
      )}
    </>
  );
}
