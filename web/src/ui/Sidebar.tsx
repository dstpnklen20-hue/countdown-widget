import { useMemo, useState } from "react";
import { cssColor, INBOX_ID } from "../data/model";
import { type FilterKey, SMART, matches } from "../domain/tasks";
import { MenuButton, NameDialog } from "./common";
import { useData, useToday } from "./context";
import { Icon } from "./icons";

/** Smart lists, the user's lists and tags, like the app's drawer. */
export function Sidebar({ open, filter, onFilter, onTrash }: {
  open: boolean; filter: FilterKey; onFilter: (f: FilterKey) => void; onTrash: () => void;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [dialog, setDialog] = useState<null | { kind: "new" } | { kind: "edit"; id: string }>(null);
  const lists = store.live("task_lists").filter((l) => !l.isInbox).sort((a, b) => a.sortOrder - b.sortOrder);
  const tags = store.live("tags").sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));

  const counts = useMemo(() => {
    const tasks = store.live("tasks").filter((t) => !t.parentId && t.status === "OPEN");
    const tagLinks = new Map<string, Set<string>>();
    for (const l of store.live("task_tags")) tagLinks.set(l.taskId, (tagLinks.get(l.taskId) ?? new Set()).add(l.tagId));
    const count = (f: FilterKey) => tasks.filter((t) => matches(f, t, tagLinks.get(t.id) ?? new Set(), today)).length;
    return { count };
  }, [store.version, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const editing = dialog?.kind === "edit" ? store.get("task_lists", dialog.id) : undefined;

  return (
    <aside className={`sidebar ${open ? "open" : ""}`} aria-label="Списки">
      {SMART.map((s) => (
        <button key={s.key} className={`side-item ${filter === s.key ? "active" : ""}`} onClick={() => onFilter(s.key)}>
          <Icon name={s.icon} size={18} />
          {s.label}
          {s.key !== "completed" && <span className="count">{counts.count(s.key) || ""}</span>}
        </button>
      ))}

      <div className="side-title">
        Списки
        <button className="icon-btn" title="Новый список" aria-label="Новый список" onClick={() => setDialog({ kind: "new" })}><Icon name="plus" size={18} /></button>
      </div>
      {lists.map((l) => {
        const key: FilterKey = `list:${l.id}`;
        return (
          <div key={l.id} style={{ display: "flex", alignItems: "center" }}>
            <button className={`side-item ${filter === key ? "active" : ""}`} onClick={() => onFilter(key)}>
              <span className="dot" style={{ background: cssColor(l.color) ?? "var(--faint)" }} />
              <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{l.name}</span>
              <span className="count">{counts.count(key) || ""}</span>
            </button>
            <MenuButton label={`Список «${l.name}»`} items={[
              { label: "Изменить", icon: "edit", onClick: () => setDialog({ kind: "edit", id: l.id }) },
              {
                label: "Удалить", icon: "trash", danger: true, onClick: () => {
                  if (!confirm(`Удалить список «${l.name}» вместе с задачами? Задачи попадут в корзину.`)) return;
                  actions.deleteList(l);
                  if (filter === key) onFilter("inbox");
                  toast({ text: `Список «${l.name}» удалён` });
                },
              },
            ]} />
          </div>
        );
      })}
      {!lists.length && <div className="muted small" style={{ padding: "4px 10px" }}>Пока только «Входящие»</div>}

      {tags.length > 0 && <div className="side-title">Теги</div>}
      {tags.map((t) => {
        const key: FilterKey = `tag:${t.id}`;
        return (
          <button key={t.id} className={`side-item ${filter === key ? "active" : ""}`} onClick={() => onFilter(key)}>
            <Icon name="tag" size={18} color={cssColor(t.color)} />
            {t.name}
            <span className="count">{counts.count(key) || ""}</span>
          </button>
        );
      })}

      <div className="side-title" />
      <button className="side-item" onClick={onTrash}><Icon name="trash" size={18} />Корзина</button>

      {dialog?.kind === "new" && (
        <NameDialog title="Новый список" withColor onClose={() => setDialog(null)} onSave={(name, color) => {
          const list = actions.createList(name, color);
          onFilter(`list:${list.id}`);
        }} />
      )}
      {editing && (
        <NameDialog title="Список" initial={editing.name} withColor initialColor={editing.color} onClose={() => setDialog(null)}
          onSave={(name, color) => actions.updateList({ ...editing, name, color })} />
      )}
    </aside>
  );
}

export function filterTitle(store: ReturnType<typeof useData>["store"], filter: FilterKey): string {
  const smart = SMART.find((s) => s.key === filter);
  if (smart) return smart.label;
  if (filter.startsWith("list:")) {
    const id = filter.slice(5);
    return id === INBOX_ID ? "Входящие" : store.get("task_lists", id)?.name ?? "Список";
  }
  if (filter.startsWith("tag:")) return "#" + (store.get("tags", filter.slice(4))?.name ?? "тег");
  return "Задачи";
}
