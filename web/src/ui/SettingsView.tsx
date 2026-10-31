import { useState } from "react";
import { useData, useLocal } from "./context";
import { Icon } from "./icons";
import { enableNotifications, notifySupported, useNotifySetting } from "./TabReminders";

type Theme = "system" | "light" | "dark";

/** Puts the chosen theme on the page (system: follow the computer). */
export function applyTheme(theme?: Theme) {
  let t = theme;
  if (!t) {
    try { t = JSON.parse(localStorage.getItem("tiktak.theme") ?? '"system"'); } catch { t = "system"; }
  }
  if (t === "system") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = t;
}

export function SettingsView({ onLogout }: { onLogout: () => void }) {
  const { store } = useData();
  const [theme, setTheme] = useLocal<Theme>("theme", "system");
  const s = store.status;
  const stateText = {
    loading: "Загрузка…", idle: "Всё синхронизировано", syncing: "Синхронизация…", offline: "Нет связи — изменения сохранены в браузере", error: "Ошибка синхронизации",
  }[s.state];
  return (
    <>
      <div className="main-head"><h1>Настройки</h1></div>
      <div className="main-body" style={{ maxWidth: 560 }}>
        <div className="section-title">Аккаунт</div>
        <div className="prop">
          <span>{store.userEmail}</span>
          <div className="spacer" />
          <button className="btn" onClick={() => confirm("Выйти из аккаунта на этом компьютере?") && onLogout()}>Выйти</button>
        </div>

        <div className="section-title">Синхронизация</div>
        <div className="prop">
          <span className={`sync-badge ${s.state === "error" ? "error" : ""}`}><Icon name="sync" size={16} />{stateText}</span>
          <div className="spacer" />
          <button className="btn" onClick={() => void store.sync()}>Обновить</button>
        </div>
        {s.message && s.state !== "idle" && <div className="error-text small">{s.message}</div>}
        {s.pending > 0 && <div className="muted small">Ждут отправки: {s.pending}</div>}
        {s.lastSyncAt && <div className="muted small">Последняя: {new Date(s.lastSyncAt).toLocaleTimeString("ru")}</div>}
        <p className="muted small">
          Сайт и приложение работают с одними данными. Изменения с сайта приходят на телефон при его следующей
          синхронизации (при открытии приложения или в течение часа), а с телефона на сайт — в течение 20 секунд.
        </p>

        <div className="section-title">Оформление</div>
        <div className="seg">
          {([["system", "Как в системе"], ["light", "Светлая"], ["dark", "Тёмная"]] as [Theme, string][]).map(([k, label]) => (
            <button key={k} className={theme === k ? "on" : ""} onClick={() => { setTheme(k); applyTheme(k); }}>{label}</button>
          ))}
        </div>

        <div className="section-title">Как на телефоне</div>
        <p className="muted small">
          На iPhone откройте сайт в Safari → «Поделиться» → «На экран „Домой“»: Tik Tak откроется как приложение.
          На Android и компьютере в Chrome — меню ⋮ → «Установить приложение».
        </p>
        <div className="section-title">Напоминания на этом компьютере</div>
        <Reminders />
      </div>
    </>
  );
}

function Reminders() {
  const [enabled, setEnabled] = useNotifySetting();
  const [denied, setDenied] = useState(notifySupported() && Notification.permission === "denied");
  if (!notifySupported()) return <p className="muted small">Этот браузер не умеет показывать уведомления. Напоминания приходят на телефон.</p>;
  return (
    <>
      <label className="row">
        <input type="checkbox" checked={enabled && !denied} onChange={async (e) => {
          if (!e.target.checked) return setEnabled(false);
          const ok = await enableNotifications();
          setDenied(!ok);
          setEnabled(ok);
        }} />
        Показывать напоминания, пока сайт открыт
      </label>
      {denied && <p className="error-text small">Браузер запретил уведомления для сайта. Разрешите их в настройках сайта (значок замка в адресной строке).</p>}
      <p className="muted small">
        Задачи, события и привычки напомнят и здесь, и на телефоне. Когда сайт закрыт, напоминает только телефон:
        браузер не может будить закрытый сайт без отдельного сервера рассылки.
      </p>
    </>
  );
}
