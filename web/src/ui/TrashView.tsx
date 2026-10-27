import { useData, useToday } from "./context";
import { Empty } from "./common";
import { formatDay } from "./format";
import { Icon } from "./icons";
import { localDateOf } from "../domain/dates";

/** Deleted tasks and events; subtasks only while their parent is alive (they come back with it). */
export function TrashView({ onBack }: { onBack: () => void }) {
  const { store, actions, toast } = useData();
  const today = useToday();
  const deleted = store.all("tasks").filter((t) => t.deleted && !(t.parentId && store.get("tasks", t.parentId)?.deleted))
    .sort((a, b) => b.updatedAt - a.updatedAt);
  const emptyTrash = async () => {
    if (!confirm(`Удалить навсегда ${deleted.length} записей? Это не отменить.`)) return;
    await actions.purge(deleted);
    toast({ text: "Корзина очищена" });
  };
  return (
    <>
      <div className="main-head">
        <button className="icon-btn" aria-label="Назад" onClick={onBack}><Icon name="back" /></button>
        <h1>Корзина</h1>
        <div className="spacer" />
        {deleted.length > 0 && <button className="btn danger" onClick={() => void emptyTrash()}>Очистить</button>}
      </div>
      <div className="main-body">
        <p className="muted small">Удалённое хранится здесь 30 дней (так же, как в приложении).</p>
        {!deleted.length && <Empty emoji="🗑️" text="Корзина пуста" />}
        {deleted.map((t) => (
          <div key={t.id} className="task-row" style={{ cursor: "default" }}>
            <Icon name={t.isEvent ? "event" : "tasks"} size={18} color="var(--faint)" />
            <span className="title">{t.title || "(без названия)"}</span>
            <span className="meta">удалено {formatDay(localDateOf(t.updatedAt), today).toLowerCase()}</span>
            <button className="btn ghost" onClick={() => { actions.restore(t); toast({ text: `«${t.title}» восстановлена` }); }}>
              <Icon name="restore" size={16} />Вернуть
            </button>
            <button className="icon-btn" title="Удалить навсегда" aria-label="Удалить навсегда"
              onClick={() => confirm(`Удалить «${t.title}» навсегда?`) && void actions.purge([t])}><Icon name="close" size={18} /></button>
          </div>
        ))}
      </div>
    </>
  );
}
