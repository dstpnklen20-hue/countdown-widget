import { useState } from "react";
import { type IcsItem, parseIcs, writeIcs } from "../data/ics";
import { PERSONAL_CALENDAR_ID } from "../data/model";
import { Dialog } from "./common";
import { useData } from "./context";
import { Icon } from "./icons";

/** Import of .ics files from other calendars and export of everything with a date (Ics.kt, the calendar's ☰ menu). */
export function CalendarFile({ onClose }: { onClose: () => void }) {
  const { store, actions, toast } = useData();
  const calendars = store.live("calendars").sort((a, b) => a.sortOrder - b.sortOrder);
  const [items, setItems] = useState<IcsItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [calendarId, setCalendarId] = useState(PERSONAL_CALENDAR_ID);

  const read = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    try {
      const parsed = parseIcs(await file.text());
      if (!parsed.length) setError("В файле нет событий и задач.");
      setItems(parsed);
    } catch {
      setError("Не получилось прочитать файл. Нужен файл календаря .ics.");
    }
  };

  const importNow = () => {
    if (!items) return;
    const n = actions.importIcs(items, calendarId === PERSONAL_CALENDAR_ID ? null : calendarId);
    toast({ text: `Импортировано: ${n}` });
    onClose();
  };

  const exportAll = () => {
    const tasks = store.live("tasks").filter((t) => t.dueAt != null && !t.parentId);
    const text = writeIcs(tasks.map((t) => ({ task: t, reminders: actions.reminders(t.id).map((r) => r.offsetMinutes).filter((m): m is number => m != null) })));
    const url = URL.createObjectURL(new Blob([text], { type: "text/calendar" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "tiktak.ics";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast({ text: `Сохранено записей: ${tasks.length}` });
  };

  const events = items?.filter((i) => i.task.isEvent).length ?? 0;
  return (
    <Dialog title="Импорт и экспорт .ics" onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Закрыть</button>
      {items && items.length > 0 && <button className="btn primary" onClick={importNow}>Импортировать {items.length}</button>}
    </>}>
      <div className="form">
        <p className="muted small" style={{ margin: 0 }}>
          Файл .ics можно скачать из Google Календаря, Outlook или Apple Календаря. Повторный импорт того же файла
          обновит записи, а не создаст копии.
        </p>
        <label className="btn" style={{ justifyContent: "center" }}>
          <Icon name="upload" size={18} />Выбрать файл .ics
          <input type="file" accept=".ics,text/calendar" style={{ display: "none" }} onChange={(e) => void read(e.target.files?.[0])} />
        </label>
        {error && <div className="error-text small">{error}</div>}
        {items && items.length > 0 && (
          <>
            <div className="small">В файле: событий — {events}, задач — {items.length - events}.</div>
            {events > 0 && (
              <label className="row">События в календарь
                <select className="field" style={{ width: "auto" }} value={calendarId} onChange={(e) => setCalendarId(e.target.value)}>
                  {calendars.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </label>
            )}
          </>
        )}
        <div className="section-title">Экспорт</div>
        <button className="btn" style={{ justifyContent: "center" }} onClick={exportAll}><Icon name="download" size={18} />Сохранить всё в tiktak.ics</button>
      </div>
    </Dialog>
  );
}
