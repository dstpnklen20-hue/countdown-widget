import { useState } from "react";
import { type DayOfWeek, type LocalDate, dayOfWeek, lengthOfMonth, parts, plusDays } from "../core/date";
import { type Freq, type RepeatRule, parseRule, rule as makeRule, toRRule } from "../core/repeatRule";
import { describe } from "../core/repeatText";
import { Dialog } from "./common";
import { WEEKDAYS_SHORT } from "./format";

const DAY_ACC = ["понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье"];
/** Gender ending of the ordinal for each weekday: "2-й вторник", "2-ю среду". */
const ENDINGS = ["й", "й", "ю", "й", "ю", "ю", "е"];
const DAY_CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];
const ORDINAL_WORDS = ["", "1", "2", "3", "4", "5"];

/** Repeats offered for a date, like repeatPresets() in EventEditor.kt. */
export function repeatPresets(day: LocalDate): { label: string; rule: string | null }[] {
  const dow = dayOfWeek(day);
  const [, , d] = parts(day);
  const last = d + 7 > lengthOfMonth(day);
  const n = Math.floor((d - 1) / 7) + 1;
  const ending = ENDINGS[dow - 1];
  return [
    { label: "Не повторять", rule: null },
    { label: "Каждый день", rule: "FREQ=DAILY" },
    { label: "По будням (пн–пт)", rule: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" },
    { label: `Каждую неделю (${DAY_ACC[dow - 1]})`, rule: `FREQ=WEEKLY;BYDAY=${DAY_CODES[dow - 1]}` },
    { label: `Каждый месяц (${d}-го)`, rule: `FREQ=MONTHLY;BYMONTHDAY=${d}` },
    last
      ? { label: `Каждый месяц в последн${ending === "й" ? "ий" : ending === "ю" ? "юю" : "ее"} ${DAY_ACC[dow - 1]}`, rule: `FREQ=MONTHLY;BYDAY=-1${DAY_CODES[dow - 1]}` }
      : { label: `Каждый месяц в ${ORDINAL_WORDS[n]}-${ending} ${DAY_ACC[dow - 1]}`, rule: `FREQ=MONTHLY;BYDAY=${n}${DAY_CODES[dow - 1]}` },
    { label: "Каждый год", rule: "FREQ=YEARLY" },
    ...(d === lengthOfMonth(day) ? [{ label: "Каждый месяц в последний день", rule: "FREQ=MONTHLY;BYMONTHDAY=-1" }] : []),
  ];
}

const CUSTOM = "__custom__";

/** A choice of repeat for an entry due on [day]: the usual presets, the current rule and «Настроить…». */
export function RepeatPicker({ day, rule, onChange, style }: { day: LocalDate; rule: string | null; onChange: (r: string | null) => void; style?: React.CSSProperties }) {
  const [custom, setCustom] = useState(false);
  const presets = repeatPresets(day);
  const parsed = parseRule(rule);
  return (
    <>
      <select className="field" style={style} value={rule ?? ""} aria-label="Повтор"
        onChange={(e) => (e.target.value === CUSTOM ? setCustom(true) : onChange(e.target.value || null))}>
        {presets.map((p) => <option key={p.label} value={p.rule ?? ""}>{p.label}</option>)}
        {rule && parsed && !presets.some((p) => p.rule === rule) && <option value={rule}>{describe(parsed)}</option>}
        <option value={CUSTOM}>Настроить…</option>
      </select>
      {custom && <CustomRepeat day={day} initial={parsed} onClose={() => setCustom(false)} onSave={(r) => { onChange(r); setCustom(false); }} />}
    </>
  );
}

type MonthMode = "DAY" | "LAST_DAY" | "WEEKDAY";
type EndMode = "NEVER" | "COUNT" | "UNTIL";

/** Every N days/weeks/months/years, on chosen weekdays, ending never, after N times or on a date (RepeatDialog.kt). */
function CustomRepeat({ day, initial, onSave, onClose }: { day: LocalDate; initial: RepeatRule | null; onSave: (r: string) => void; onClose: () => void }) {
  const dow = dayOfWeek(day);
  const [, , d] = parts(day);
  const [freq, setFreq] = useState<Freq>(initial?.freq ?? "WEEKLY");
  const [interval, setInterval] = useState(initial?.interval ?? 1);
  const [days, setDays] = useState<DayOfWeek[]>(initial?.byDay.filter((w) => w.ordinal === 0).map((w) => w.day) ?? [dow]);
  const [monthMode, setMonthMode] = useState<MonthMode>(initial?.byMonthDay === -1 ? "LAST_DAY" : initial?.byDay.some((w) => w.ordinal !== 0) ? "WEEKDAY" : "DAY");
  const [endMode, setEndMode] = useState<EndMode>(initial?.count != null ? "COUNT" : initial?.until != null ? "UNTIL" : "NEVER");
  const [count, setCount] = useState(initial?.count ?? 10);
  const [until, setUntil] = useState<LocalDate>(initial?.until ?? plusDays(day, 30));
  const ordinal = d + 7 > lengthOfMonth(day) ? -1 : Math.floor((d - 1) / 7) + 1;

  const build = (): RepeatRule => makeRule(freq, {
    interval,
    byDay: freq === "WEEKLY" ? [...new Set(days.length ? days : [dow])].sort().map((x) => ({ day: x, ordinal: 0 }))
      : freq === "MONTHLY" && monthMode === "WEEKDAY" ? [{ day: dow, ordinal }] : [],
    byMonthDay: freq === "MONTHLY" ? (monthMode === "LAST_DAY" ? -1 : monthMode === "DAY" ? d : null) : null,
    count: endMode === "COUNT" ? count : null,
    until: endMode === "UNTIL" ? until : null,
  });

  return (
    <Dialog title="Повтор" onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" onClick={() => onSave(toRRule(build()))}>Готово</button>
    </>}>
      <div className="form">
        <div className="prop">
          Каждые
          <input className="field" type="number" min={1} max={99} style={{ width: 70 }} value={interval}
            onChange={(e) => setInterval(Math.max(1, Math.min(99, Number(e.target.value) || 1)))} />
          <div className="seg">
            {([["DAILY", "дни"], ["WEEKLY", "недели"], ["MONTHLY", "месяцы"], ["YEARLY", "годы"]] as [Freq, string][]).map(([f, label]) => (
              <button key={f} className={freq === f ? "on" : ""} onClick={() => setFreq(f)}>{label}</button>
            ))}
          </div>
        </div>
        {freq === "WEEKLY" && (
          <div className="prop">{([1, 2, 3, 4, 5, 6, 7] as DayOfWeek[]).map((x) => (
            <button key={x} className={`hday ${days.includes(x) ? "on" : ""}`}
              onClick={() => setDays(days.includes(x) ? days.filter((y) => y !== x) : [...days, x])}>{WEEKDAYS_SHORT[x - 1]}</button>
          ))}</div>
        )}
        {freq === "MONTHLY" && (
          <div className="prop">
            <button className={`chip ${monthMode === "DAY" ? "on" : ""}`} onClick={() => setMonthMode("DAY")}>{d} числа</button>
            <button className={`chip ${monthMode === "LAST_DAY" ? "on" : ""}`} onClick={() => setMonthMode("LAST_DAY")}>Последний день</button>
            <button className={`chip ${monthMode === "WEEKDAY" ? "on" : ""}`} onClick={() => setMonthMode("WEEKDAY")}>
              {describe(makeRule("MONTHLY", { byDay: [{ day: dow, ordinal }] })).replace(/^.*?, /, "")}
            </button>
          </div>
        )}
        <div className="muted small">Окончание</div>
        <div className="prop">
          <button className={`chip ${endMode === "NEVER" ? "on" : ""}`} onClick={() => setEndMode("NEVER")}>Никогда</button>
          <button className={`chip ${endMode === "COUNT" ? "on" : ""}`} onClick={() => setEndMode("COUNT")}>После N раз</button>
          <button className={`chip ${endMode === "UNTIL" ? "on" : ""}`} onClick={() => setEndMode("UNTIL")}>До даты</button>
          {endMode === "COUNT" && <input className="field" type="number" min={1} max={999} style={{ width: 80 }} value={count} aria-label="Сколько раз"
            onChange={(e) => setCount(Math.max(1, Math.min(999, Number(e.target.value) || 1)))} />}
          {endMode === "UNTIL" && <input className="field" type="date" style={{ width: "auto" }} value={until} aria-label="До даты"
            onChange={(e) => e.target.value && setUntil(e.target.value)} />}
        </div>
        <div className="muted small">{describe(build())}</div>
      </div>
    </Dialog>
  );
}
