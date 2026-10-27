import { type DayOfWeek, parts } from "./date";
import { type RepeatRule, WEEKDAYS } from "./repeatRule";

/** Port of core/.../RepeatText.kt. */
export const SHORT_DAYS: Record<number, string> = { 1: "пн", 2: "вт", 3: "ср", 4: "чт", 5: "пт", 6: "сб", 7: "вс" };

const DAY_ACC: Record<number, string> = {
  1: "понедельник", 2: "вторник", 3: "среду", 4: "четверг", 5: "пятницу", 6: "субботу", 7: "воскресенье",
};

const MONTHS_SHORT = ["янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."];

export function shortDayName(day: DayOfWeek): string {
  return SHORT_DAYS[day];
}

export function plural(n: number, one: string, few: string, many: string): string {
  const m100 = n % 100, m10 = n % 10;
  if (m100 >= 11 && m100 <= 14) return many;
  if (m10 === 1) return one;
  if (m10 >= 2 && m10 <= 4) return few;
  return many;
}

function ordinalName(n: number): string {
  switch (n) {
    case -1: return "последний";
    case 1: return "первый";
    case 2: return "второй";
    case 3: return "третий";
    case 4: return "четвёртый";
    default: return `${n}-й`;
  }
}

/** Russian description, e.g. "Каждые 2 недели: пн, ср" or "Ежемесячно, последний день". */
export function describe(r: RepeatRule): string {
  let base: string;
  const n = r.interval;
  switch (r.freq) {
    case "DAILY":
      base = n === 1 ? "Каждый день" : `Каждые ${n} ${plural(n, "день", "дня", "дней")}`;
      break;
    case "WEEKLY": {
      const days = r.byDay.map((w) => w.day).sort((a, b) => a - b);
      if (n === 1 && days.join() === WEEKDAYS.join()) base = "По будням";
      else if (n === 1 && days.length === 1) base = `Каждую неделю в ${DAY_ACC[days[0]]}`;
      else {
        const head = n === 1 ? "Каждую неделю" : `Каждые ${n} ${plural(n, "неделю", "недели", "недель")}`;
        base = days.length ? head + ": " + days.map((d) => SHORT_DAYS[d]).join(", ") : head;
      }
      break;
    }
    case "MONTHLY": {
      const head = n === 1 ? "Ежемесячно" : `Каждые ${n} ${plural(n, "месяц", "месяца", "месяцев")}`;
      const ordinal = r.byDay.find((w) => w.ordinal !== 0);
      if (ordinal) base = `${head}, ${ordinalName(ordinal.ordinal)} ${SHORT_DAYS[ordinal.day]}`;
      else if (r.byMonthDay === -1) base = `${head}, последний день`;
      else if (r.byMonthDay != null) base = `${head}, ${r.byMonthDay} числа`;
      else base = head;
      break;
    }
    case "YEARLY":
      base = n === 1 ? "Ежегодно" : `Каждые ${n} ${plural(n, "год", "года", "лет")}`;
      break;
  }
  let tail = "";
  if (r.count != null) tail = `, ещё ${r.count} ${plural(r.count, "раз", "раза", "раз")}`;
  else if (r.until != null) {
    const [y, m, d] = parts(r.until);
    tail = `, до ${d} ${MONTHS_SHORT[m - 1]} ${y}`;
  }
  return base + tail;
}
