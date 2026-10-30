import { useState } from "react";
import type { Section, Task, TaskList } from "../data/model";
import { isDone } from "../domain/tasks";
import { MenuButton, NameDialog } from "./common";
import { useData, useNow, useToday } from "./context";
import { Icon } from "./icons";
import { PAGE_ROWS, TaskRow, rowProps } from "./TaskListView";

/**
 * A list as a board (KanbanBoard.kt): one column per section plus «Без раздела»; completed tasks
 * are left out. Tasks are dragged between columns with the mouse or moved from their menu.
 */
export function KanbanBoard({ list, selected, onOpen, onToggle }: {
  list: TaskList; selected: string | null; onOpen: (t: Task) => void; onToggle: (t: Task) => void;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const now = useNow();
  const [dialog, setDialog] = useState<null | { section: Section | null }>(null);
  const [over, setOver] = useState<string | null>(null);
  const [shown, setShown] = useState<Record<string, number>>({});
  const sections = actions.sections(list.id);
  const ids = new Set(sections.map((s) => s.id));
  const open = store.live("tasks").filter((t) => t.listId === list.id && !t.parentId && !isDone(t) && !t.isEvent && t.displayMode !== "COUNTDOWN")
    .sort((a, b) => (a.dueAt ?? Infinity) - (b.dueAt ?? Infinity) || b.priority - a.priority || a.sortOrder - b.sortOrder);
  const columns: { section: Section | null; tasks: Task[] }[] = [
    { section: null, tasks: open.filter((t) => !t.sectionId || !ids.has(t.sectionId)) },
    ...sections.map((s) => ({ section: s, tasks: open.filter((t) => t.sectionId === s.id) })),
  ];

  const drop = (e: React.DragEvent, sectionId: string | null) => {
    e.preventDefault();
    setOver(null);
    const task = store.get("tasks", e.dataTransfer.getData("text/x-tiktak-task"));
    if (task) actions.moveToSection(task, sectionId);
  };

  return (
    <div className="kanban">
      {columns.map(({ section, tasks }) => {
        const key = section?.id ?? "none";
        return (
          <div key={key} className={`kcol ${over === key ? "over" : ""}`}
            onDragOver={(e) => { e.preventDefault(); setOver(key); }} onDragLeave={() => setOver((o) => (o === key ? null : o))}
            onDrop={(e) => drop(e, section?.id ?? null)}>
            <div className="khead">
              <b>{section?.name ?? "Без раздела"}</b>
              <span className="muted small">{tasks.length}</span>
              <div className="spacer" />
              {section && (
                <MenuButton label={`Колонка «${section.name}»`} items={[
                  { label: "Переименовать", icon: "edit", onClick: () => setDialog({ section }) },
                  {
                    label: "Удалить", icon: "trash", danger: true, onClick: () => {
                      if (!confirm(`Удалить колонку «${section.name}»? Задачи из неё останутся в списке.`)) return;
                      actions.deleteSection(section);
                      toast({ text: `Колонка «${section.name}» удалена` });
                    },
                  },
                ]} />
              )}
            </div>
            <ColumnAdd onAdd={(text) => actions.quickAdd(text, `list:${list.id}`, section?.id ?? null)} />
            {tasks.slice(0, shown[key] ?? PAGE_ROWS).map((t) => (
              <div key={t.id} draggable className="kcard" onDragStart={(e) => { e.dataTransfer.setData("text/x-tiktak-task", t.id); e.dataTransfer.effectAllowed = "move"; }}>
                <TaskRow {...rowProps(store, t, today, now, false)} selected={selected === t.id} onOpen={onOpen} onToggle={onToggle} />
                {sections.length > 0 && (
                  <MenuButton label="Переместить в колонку" icon="kanban" items={[
                    ...(t.sectionId && ids.has(t.sectionId) ? [{ label: "Без раздела", onClick: () => actions.moveToSection(t, null) }] : []),
                    ...sections.filter((s) => s.id !== t.sectionId).map((s) => ({ label: s.name, onClick: () => actions.moveToSection(t, s.id) })),
                  ]} />
                )}
              </div>
            ))}
            {tasks.length > (shown[key] ?? PAGE_ROWS) && (
              <button className="btn ghost small" onClick={() => setShown({ ...shown, [key]: (shown[key] ?? PAGE_ROWS) + 500 })}>
                Показать ещё {tasks.length - (shown[key] ?? PAGE_ROWS)}
              </button>
            )}
          </div>
        );
      })}
      <button className="kcol knew" onClick={() => setDialog({ section: null })}><Icon name="plus" />Новая колонка</button>
      {dialog && (
        <NameDialog title={dialog.section ? "Колонка" : "Новая колонка"} initial={dialog.section?.name ?? ""} onClose={() => setDialog(null)}
          onSave={(name) => (dialog.section ? actions.updateSection({ ...dialog.section, name }) : actions.createSection(list.id, name))} />
      )}
    </div>
  );
}

function ColumnAdd({ onAdd }: { onAdd: (text: string) => void }) {
  const [text, setText] = useState("");
  return (
    <input className="field kadd" value={text} placeholder="+ Задача" aria-label="Новая задача в колонку" onChange={(e) => setText(e.target.value)}
      onKeyDown={(e) => { if (e.key === "Enter" && text.trim()) { onAdd(text); setText(""); } }} />
  );
}
