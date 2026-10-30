import { useMemo, useState } from "react";
import { formatTime } from "../core/date";
import { QuickAddParser } from "../core/quickAdd";
import { describe } from "../core/repeatText";
import { cssColor, INBOX_ID, type Task } from "../data/model";
import { dueDay, nowMinute } from "../domain/dates";
import { type FilterKey, type TaskGroup, type TaskSort, SORTS, groupTasks, isDone, isOverdue, matches } from "../domain/tasks";
import { Check, Empty, MenuButton } from "./common";
import { useData, useLocal, useNow, useToday } from "./context";
import { PRIORITY_NAMES, daysLeft, formatDay, formatDue } from "./format";
import { Icon } from "./icons";
import { filterTitle } from "./Sidebar";

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

  const toggle = (t: Task) => {
    const done = !isDone(t);
    const undo = actions.setDone(t, done);
    if (done) toast({ text: `«${t.title}» выполнена`, undo });
  };

  const title = filterTitle(store, filter);
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
        <MenuButton label="Сортировка" icon="all" items={SORTS.map((s) => ({ label: (s.key === sort ? "✓ " : "") + s.label, onClick: () => setSort(s.key) }))} />
      </div>
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
              {!isCollapsed && g.tasks.map((t) => (
                <TaskRow key={t.id} task={t} today={today} now={now} showList={!filter.startsWith("list:")} selected={selected === t.id}
                  countdown={filter === "countdowns"} onOpen={() => onOpen(t)} onToggle={() => toggle(t)} />
              ))}
            </section>
          );
        })}
      </div>
    </>
  );
}

export function TaskRow({ task: t, today, now, showList, selected, countdown, onOpen, onToggle }: {
  task: Task; today: string; now: number; showList?: boolean; selected?: boolean; countdown?: boolean; onOpen: () => void; onToggle: () => void;
}) {
  const { store } = useData();
  const list = store.get("task_lists", t.listId);
  const done = isDone(t);
  const due = formatDue(t, today);
  const overdue = isOverdue(t, now, today);
  const color = cssColor(t.color) ?? cssColor(list?.color);
  const checklist = store.index("checklist_items", "taskId").get(t.id) ?? [];
  const subtasks = store.index("tasks", "parentId").get(t.id) ?? [];
  const tags = (store.index("task_tags", "taskId").get(t.id) ?? []).map((l) => store.get("tags", l.tagId)).filter((x) => x && !x.deleted);
  const day = dueDay(t);
  return (
    <div className={`task-row ${done ? "done" : ""} ${selected ? "selected" : ""}`} onClick={onOpen} role="button" tabIndex={0}
      onKeyDown={(e) => e.key === "Enter" && onOpen()}>
      {color && <span className="bar" style={{ background: color }} />}
      {countdown ? <Icon name="hourglass" size={18} color="var(--primary)" /> : <Check done={done} priority={t.priority} onToggle={onToggle} />}
      <span className="title">{t.title || "(без названия)"}</span>
      <span className="meta">
        {tags.map((tag) => <span key={tag!.id} className="chip">#{tag!.name}</span>)}
        {checklist.length > 0 && <span>☑ {checklist.filter((c) => c.checked).length}/{checklist.length}</span>}
        {subtasks.length > 0 && <span>↳ {subtasks.filter(isDone).length}/{subtasks.length}</span>}
        {t.repeatRule && <Icon name="repeat" size={14} />}
        {showList && list && !list.isInbox && <span>{list.name}</span>}
        {countdown && day ? <span style={{ color: "var(--primary)", fontWeight: 600 }}>{daysLeft(day, today)}</span>
          : due && <span className={overdue ? "overdue" : ""}>{due}</span>}
      </span>
    </div>
  );
}

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
