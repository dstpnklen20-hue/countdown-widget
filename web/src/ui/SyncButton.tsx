import { useContext, useState } from "react";
import { Ctx, useSyncStatus } from "./context";
import { Icon } from "./icons";

/**
 * The state of sync, in one icon; a press syncs now. The quiet check every 20 seconds doesn't show:
 * the icon turns only while edits are on their way, so it moves when there is something to say.
 * [onlyProblems]: shows nothing while all is well (the calendar's toolbar, where the rail is hidden).
 */
export function SyncButton({ onlyProblems }: { onlyProblems?: boolean }) {
  const { store } = useContext(Ctx);
  const s = useSyncStatus();
  const [asked, setAsked] = useState(false);
  const problem = s.state === "offline" || s.state === "error";
  if (onlyProblems && !problem) return null;
  const busy = !problem && (asked || s.pending > 0 || s.state === "loading");
  const waiting = s.pending > 0 ? ` Ждут отправки: ${s.pending}.` : "";
  const title = s.state === "offline" ? `Нет связи: изменения сохранены в браузере и уйдут, когда она появится.${waiting} Нажмите, чтобы проверить`
    : s.state === "error" ? `Ошибка синхронизации${s.message ? `: ${s.message}` : ""}.${waiting} Нажмите, чтобы повторить`
      : busy ? "Синхронизация…"
        : `Всё синхронизировано${s.lastSyncAt ? `, последняя в ${new Date(s.lastSyncAt).toLocaleTimeString("ru", { hour: "2-digit", minute: "2-digit" })}` : ""}. Нажмите, чтобы обновить`;
  return (
    <button className={`icon-btn sync-btn ${s.state === "error" ? "error" : problem ? "warn" : busy ? "busy" : "ok"}`} title={title} aria-label={title}
      onClick={() => {
        setAsked(true);
        void store.sync().finally(() => setAsked(false));
      }}>
      <Icon name={problem ? "cloudOff" : busy ? "sync" : "cloudDone"} size={20} />
    </button>
  );
}
