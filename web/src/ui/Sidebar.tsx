import { useMemo, useState } from "react";
import { cssColor, type Folder, INBOX_ID, type TaskList } from "../data/model";
import { type FilterKey, SMART, matches } from "../domain/tasks";
import { Dialog, MenuButton, NameDialog, Swatches } from "./common";
import { useData, useLocal, useToday } from "./context";
import { Icon } from "./icons";

type DialogState = null | { kind: "list"; list: TaskList | null; folderId?: string | null } | { kind: "folder"; folder: Folder | null };

/** Smart lists, folders with the user's lists, and tags, like the app's drawer (AppDrawer.kt). */
export function Sidebar({ open, filter, onFilter, onTrash }: {
  open: boolean; filter: FilterKey; onFilter: (f: FilterKey) => void; onTrash: () => void;
}) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const [dialog, setDialog] = useState<DialogState>(null);
  const [collapsed, setCollapsed] = useLocal<string[]>("folders.collapsed", []);
  const lists = store.live("task_lists").filter((l) => !l.isInbox).sort((a, b) => a.sortOrder - b.sortOrder);
  const folders = store.live("folders").sort((a, b) => a.sortOrder - b.sortOrder);
  const folderIds = new Set(folders.map((f) => f.id));
  const tags = store.live("tags").sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));

  const counts = useMemo(() => {
    const tasks = store.live("tasks").filter((t) => !t.parentId && t.status === "OPEN");
    const tagLinks = new Map<string, Set<string>>();
    for (const l of store.live("task_tags")) tagLinks.set(l.taskId, (tagLinks.get(l.taskId) ?? new Set()).add(l.tagId));
    const cache = new Map<FilterKey, number>();
    return (f: FilterKey) => {
      if (!cache.has(f)) cache.set(f, tasks.filter((t) => matches(f, t, tagLinks.get(t.id) ?? new Set(), today)).length);
      return cache.get(f)!;
    };
  }, [store.version, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const listItem = (l: TaskList, nested: boolean) => {
    const key: FilterKey = `list:${l.id}`;
    return (
      <div key={l.id} style={{ display: "flex", alignItems: "center", paddingLeft: nested ? 14 : 0 }}>
        <button className={`side-item ${filter === key ? "active" : ""}`} onClick={() => onFilter(key)}>
          {l.viewMode === "KANBAN" ? <Icon name="kanban" size={16} color={cssColor(l.color) ?? "var(--faint)"} />
            : <span className="dot" style={{ background: cssColor(l.color) ?? "var(--faint)" }} />}
          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{l.name}</span>
          <span className="count">{counts(key) || ""}</span>
        </button>
        <MenuButton label={`Список «${l.name}»`} items={[
          { label: "Изменить", icon: "edit", onClick: () => setDialog({ kind: "list", list: l }) },
          {
            label: "Удалить", icon: "trash", danger: true, onClick: () => {
              if (!confirm(`Удалить список «${l.name}» вместе с задачами? Задачи попадут в корзину.`)) return;
              const undo = actions.deleteList(l);
              if (filter === key) onFilter("inbox");
              toast({ text: `Список «${l.name}» удалён`, undo });
            },
          },
        ]} />
      </div>
    );
  };

  return (
    <aside className={`sidebar ${open ? "open" : ""}`} aria-label="Списки">
      {SMART.map((s) => (
        <button key={s.key} className={`side-item ${filter === s.key ? "active" : ""}`} onClick={() => onFilter(s.key)}>
          <Icon name={s.icon} size={18} />
          {s.label}
          {s.key !== "completed" && <span className="count">{counts(s.key) || ""}</span>}
        </button>
      ))}

      <div className="side-title">
        Списки
        <MenuButton label="Добавить" icon="plus" items={[
          { label: "Новый список", icon: "list", onClick: () => setDialog({ kind: "list", list: null }) },
          { label: "Новая папка", icon: "folder", onClick: () => setDialog({ kind: "folder", folder: null }) },
        ]} />
      </div>
      {folders.map((f) => {
        const inFolder = lists.filter((l) => l.folderId === f.id);
        const shut = collapsed.includes(f.id);
        return (
          <div key={f.id}>
            <div style={{ display: "flex", alignItems: "center" }}>
              <button className="side-item" onClick={() => setCollapsed(shut ? collapsed.filter((x) => x !== f.id) : [...collapsed, f.id])} aria-expanded={!shut}>
                <Icon name="folder" size={18} color="var(--muted)" />
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{f.name}</span>
                <span className="count"><Icon name={shut ? "right" : "left"} size={14} /></span>
              </button>
              <MenuButton label={`Папка «${f.name}»`} items={[
                { label: "Новый список в папке", icon: "plus", onClick: () => setDialog({ kind: "list", list: null, folderId: f.id }) },
                { label: "Переименовать", icon: "edit", onClick: () => setDialog({ kind: "folder", folder: f }) },
                {
                  label: "Удалить папку", icon: "trash", danger: true, onClick: () => {
                    if (!confirm(`Удалить папку «${f.name}»? Списки из неё останутся.`)) return;
                    actions.deleteFolder(f);
                  },
                },
              ]} />
            </div>
            {!shut && inFolder.map((l) => listItem(l, true))}
            {!shut && !inFolder.length && <div className="muted small" style={{ padding: "2px 10px 4px 38px" }}>Пустая папка</div>}
          </div>
        );
      })}
      {lists.filter((l) => !l.folderId || !folderIds.has(l.folderId)).map((l) => listItem(l, false))}
      {!lists.length && !folders.length && <div className="muted small" style={{ padding: "4px 10px" }}>Пока только «Входящие»</div>}

      {tags.length > 0 && <div className="side-title">Теги</div>}
      {tags.map((t) => {
        const key: FilterKey = `tag:${t.id}`;
        return (
          <button key={t.id} className={`side-item ${filter === key ? "active" : ""}`} onClick={() => onFilter(key)}>
            <Icon name="tag" size={18} color={cssColor(t.color)} />
            {t.name}
            <span className="count">{counts(key) || ""}</span>
          </button>
        );
      })}

      <div className="side-title" />
      <button className="side-item" onClick={onTrash}><Icon name="trash" size={18} />Корзина</button>

      {dialog?.kind === "list" && (
        <ListDialog list={dialog.list} folders={folders} folderId={dialog.list?.folderId ?? dialog.folderId ?? null} onClose={() => setDialog(null)}
          onSave={(name, color, folderId) => {
            if (dialog.list) actions.updateList({ ...dialog.list, name, color, folderId });
            else onFilter(`list:${actions.createList(name, color, folderId).id}`);
          }} />
      )}
      {dialog?.kind === "folder" && (
        <NameDialog title={dialog.folder ? "Папка" : "Новая папка"} initial={dialog.folder?.name ?? ""} onClose={() => setDialog(null)}
          onSave={(name) => (dialog.folder ? actions.updateFolder({ ...dialog.folder, name }) : actions.createFolder(name))} />
      )}
    </aside>
  );
}

function ListDialog({ list, folders, folderId, onSave, onClose }: {
  list: TaskList | null; folders: Folder[]; folderId: string | null;
  onSave: (name: string, color: number | null, folderId: string | null) => void; onClose: () => void;
}) {
  const [name, setName] = useState(list?.name ?? "");
  const [color, setColor] = useState<number | null>(list?.color ?? null);
  const [folder, setFolder] = useState<string | null>(folderId);
  const save = () => {
    if (!name.trim()) return;
    onSave(name.trim(), color, folder);
    onClose();
  };
  return (
    <Dialog title={list ? "Список" : "Новый список"} onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" onClick={save} disabled={!name.trim()}>Сохранить</button>
    </>}>
      <div className="form">
        <input className="field" autoFocus value={name} placeholder="Название" onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && save()} />
        <Swatches value={color} onChange={setColor} />
        {folders.length > 0 && (
          <label className="row">Папка
            <select className="field" style={{ width: "auto" }} value={folder ?? ""} onChange={(e) => setFolder(e.target.value || null)}>
              <option value="">Без папки</option>
              {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
            </select>
          </label>
        )}
      </div>
    </Dialog>
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
