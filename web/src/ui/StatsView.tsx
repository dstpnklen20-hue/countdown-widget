import { useMemo, useState } from "react";
import { type LocalDate, dayOfWeek, parts, plusDays } from "../core/date";
import { plural } from "../core/repeatText";
import { cssColor, PERSONAL_CALENDAR_ID } from "../data/model";
import { epochAt, localDateOf } from "../domain/dates";
import { countPerDay, countPerMonth, dayRange, eventMinutesByCalendar, focusMinutesPerDay, focusMinutesPerMonth, minusMonths, monthRange } from "../domain/stats";
import { useData, useToday } from "./context";
import { MONTHS_NOM, WEEKDAYS_SHORT, formatDuration } from "./format";

const tasksText = (n: number) => `${n} ${plural(n, "задача", "задачи", "задач")}`;
const ALL_TIME = 0;

/** Statistics like the app's StatsScreen: completed tasks, pomodoros, focus minutes, time per calendar. */
export function StatsView() {
  const { store } = useData();
  const today = useToday();
  const [days, setDays] = useState(7);
  const allTime = days === ALL_TIME;
  const from = allTime ? "1970-01-01" : plusDays(today, -(days - 1));

  const data = useMemo(() => {
    const since = allTime ? 0 : epochAt(from, 0);
    const completed = store.live("tasks").filter((t) => t.status === "DONE" && t.completedAt != null && t.completedAt >= since).map((t) => t.completedAt!);
    const sessions = store.live("focus_sessions").filter((s) => s.kind === "FOCUS" && s.startedAt >= since);
    const todayStart = epochAt(today, 0);
    return {
      completed, sessions,
      doneToday: completed.filter((c) => c >= todayStart).length,
      focusToday: Math.floor(sessions.filter((s) => s.startedAt >= todayStart).reduce((a, s) => a + s.durationMs, 0) / 60_000),
    };
  }, [store.version, from, allTime, today]); // eslint-disable-line react-hooks/exhaustive-deps

  const charts = useMemo(() => {
    if (allTime) {
      const current = today.slice(0, 7);
      const first = [...data.completed, ...data.sessions.map((s) => s.startedAt)].reduce((a, b) => Math.min(a, b), Infinity);
      const firstMonth = Number.isFinite(first) ? localDateOf(first).slice(0, 7) : current;
      const start = [firstMonth, minusMonths(current, 5)].sort()[0];
      const months = monthRange(start, current);
      const labels = months.map((m) => MONTHS_NOM[Number(m.slice(5)) - 1].slice(0, 3).toLowerCase());
      return {
        done: { values: countPerMonth(data.completed, months), labels, titles: months.map((m) => `${MONTHS_NOM[Number(m.slice(5)) - 1]} ${m.slice(0, 4)}`), current: months.length - 1 },
        focus: { values: focusMinutesPerMonth(data.sessions, months) },
        doneTitle: "Выполненные задачи по месяцам", focusTitle: "Фокус по месяцам",
      };
    }
    const range = dayRange(from, days);
    // A month has no room for 30 captions: Mondays only.
    const labels = range.map((d) => (days <= 7 ? WEEKDAYS_SHORT[dayOfWeek(d) - 1] : dayOfWeek(d) === 1 ? String(parts(d)[2]) : ""));
    return {
      done: { values: countPerDay(data.completed, from, days), labels, titles: range.map((d) => `${parts(d)[2]} ${MONTHS_NOM[parts(d)[1] - 1].toLowerCase()}`), current: range.indexOf(today) },
      focus: { values: focusMinutesPerDay(data.sessions, from, days) },
      doneTitle: "Выполненные задачи", focusTitle: "Минуты фокуса",
    };
  }, [data, allTime, from, days, today]);

  const focusMs = data.sessions.reduce((a, s) => a + s.durationMs, 0);
  return (
    <>
      <div className="main-head">
        <h1>Статистика</h1>
        <div className="spacer" />
        <div className="seg">
          {([[7, "Неделя"], [30, "Месяц"], [ALL_TIME, "За всё время"]] as [number, string][]).map(([n, label]) => (
            <button key={n} className={days === n ? "on" : ""} onClick={() => setDays(n)}>{label}</button>
          ))}
        </div>
      </div>
      <div className="main-body" style={{ maxWidth: 760 }}>
        <div className="card-box">
          <div className="muted small">{days === 7 ? "За неделю" : days === 30 ? "За 30 дней" : "За всё время"}</div>
          <div className="stats tiles">
            <div><b>{data.completed.length}</b><span className="muted small">задач выполнено</span></div>
            <div><b>{data.sessions.length}</b><span className="muted small">помидоров</span></div>
            <div><b>{formatDuration(Math.floor(focusMs / 60_000))}</b><span className="muted small">фокуса</span></div>
          </div>
          <div className="muted small">Сегодня: {tasksText(data.doneToday)} · {data.focusToday} мин фокуса</div>
        </div>
        <CalendarTime from={allTime ? plusDays(today, -364) : from} to={today} />
        <div className="card-box">
          <div className="chart-title">{charts.doneTitle}</div>
          <BarChart values={charts.done.values} labels={charts.done.labels} titles={charts.done.titles} current={charts.done.current} text={tasksText} />
        </div>
        <div className="card-box">
          <div className="chart-title">{charts.focusTitle}</div>
          <BarChart values={charts.focus.values} labels={charts.done.labels} titles={charts.done.titles} current={charts.done.current} text={(m) => formatDuration(m)} />
        </div>
      </div>
    </>
  );
}

/** Hours of events per calendar over the period, each a bar in the calendar's colour. */
function CalendarTime({ from, to }: { from: LocalDate; to: LocalDate }) {
  const { store } = useData();
  const rows = useMemo(() => {
    const minutes = eventMinutesByCalendar(store.live("tasks"), from, to);
    return store.live("calendars")
      .map((c) => ({ c, m: (minutes.get(c.id) ?? 0) + (c.id === PERSONAL_CALENDAR_ID ? minutes.get(null) ?? 0 : 0) }))
      .filter((r) => r.m > 0)
      .sort((a, b) => b.m - a.m);
  }, [store.version, from, to]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!rows.length) return null;
  const top = rows[0].m;
  return (
    <div className="card-box">
      <div className="chart-title">Время по календарям</div>
      <div className="muted small">Часы событий за период, с повторами</div>
      {rows.map(({ c, m }) => (
        <div key={c.id} className="hbar">
          <span className="name">{c.name}</span>
          <span className="track"><span style={{ width: `${(m / top) * 100}%`, background: cssColor(c.color) }} /></span>
          <span className="val">{formatDuration(m)}</span>
        </div>
      ))}
    </div>
  );
}

/** Bars per day or month; a tap on a bar shows its value above the chart (DayBarChart.kt). */
export function BarChart({ values, labels, titles, current, text }: {
  values: number[]; labels: string[]; titles: string[]; current: number; text: (n: number) => string;
}) {
  const [picked, setPicked] = useState<number | null>(null);
  const shown = picked ?? (current >= 0 ? current : values.length - 1);
  const max = Math.max(1, ...values);
  return (
    <div className="bars-box">
      <div className="small" style={{ minHeight: 18 }}>{shown >= 0 ? `${titles[shown]}: ${text(values[shown] ?? 0)}` : ""}</div>
      <div className="bars" onMouseLeave={() => setPicked(null)}>
        {values.map((v, i) => (
          <button key={i} className={`bar ${i === shown ? "on" : ""}`} title={`${titles[i]}: ${text(v)}`} aria-label={`${titles[i]}: ${text(v)}`}
            onClick={() => setPicked(i)} onMouseEnter={() => setPicked(i)}>
            <span style={{ height: `${Math.max((v / max) * 100, v ? 3 : 1)}%` }} />
          </button>
        ))}
      </div>
      <div className="bars labels">{labels.map((l, i) => <span key={i} className={i === current ? "on" : ""}>{l}</span>)}</div>
    </div>
  );
}
