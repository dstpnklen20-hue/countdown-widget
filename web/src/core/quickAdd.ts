import {
  type DayOfWeek, type LocalDate, type LocalTime, MONDAY, SATURDAY, SUNDAY,
  date, isValidDate, next, nextOrSame, parts, plusDays, plusMonths, plusWeeks, plusYears,
} from "./date";
import { type Freq, type RepeatRule, WEEKDAYS, rule } from "./repeatRule";

/** Port of core/.../QuickAddParser.kt: keep the two in step (the tests are shared word for word). */
export interface QuickAddResult {
  title: string;
  date: LocalDate | null;
  time: LocalTime | null;
  /** 0..3, null when not mentioned. */
  priority: number | null;
  tags: string[];
  listName: string | null;
  repeat: RepeatRule | null;
  /** Recognized fragments, in the order they were found, for highlighting. */
  recognized: string[];
  /** True when the text named a day (not just a time, whose day is guessed). */
  dateGiven: boolean;
  /** End of a range like "с 15 до 17" or "15:00–17:30". */
  endTime: LocalTime | null;
  /** A length like "2 часа", "на полчаса", "for 45 min". */
  durationMinutes: number | null;
}

const L = "(?<![\\p{L}\\p{N}])";
const R = "(?![\\p{L}\\p{N}])";
const rx = (p: string) => new RegExp(p, "giu");

const DAY_FORMS: [DayOfWeek, string[]][] = [
  [1, ["понедельник", "понедельникам", "monday", "mondays", "mon"]],
  [2, ["вторник", "вторникам", "tuesday", "tuesdays", "tue", "tues"]],
  [3, ["среда", "среду", "средам", "wednesday", "wednesdays", "wed"]],
  [4, ["четверг", "четвергам", "thursday", "thursdays", "thu", "thur", "thurs"]],
  [5, ["пятница", "пятницу", "пятницам", "friday", "fridays", "fri"]],
  [6, ["суббота", "субботу", "субботам", "saturday", "saturdays", "sat"]],
  [7, ["воскресенье", "воскресеньям", "sunday", "sundays", "sun"]],
];
const SHORT_RU: [string, DayOfWeek][] = [["пн", 1], ["вт", 2], ["ср", 3], ["чт", 4], ["пт", 5], ["сб", 6], ["вс", 7]];
const ALL_DAY_WORDS = new Map<string, DayOfWeek>([...DAY_FORMS.flatMap(([d, forms]) => forms.map((f) => [f, d] as [string, DayOfWeek])), ...SHORT_RU]);
const byLength = (a: string, b: string) => b.length - a.length;
const DAY_ALT = [...ALL_DAY_WORDS.keys()].sort(byLength).join("|");
const FULL_DAY_ALT = DAY_FORMS.flatMap(([, f]) => f).filter((w) => w.length > 3).sort(byLength).join("|");

function dayNamesIn(text: string): DayOfWeek[] {
  const out: DayOfWeek[] = [];
  for (const m of text.matchAll(rx(`${L}(${DAY_ALT})${R}`))) {
    const d = ALL_DAY_WORDS.get(m[0].toLowerCase());
    if (d && !out.includes(d)) out.push(d);
  }
  return out;
}

const MONTHS: string[][] = [
  ["января", "январь", "янв", "january", "jan"],
  ["февраля", "февраль", "фев", "february", "feb"],
  ["марта", "март", "мар", "march", "mar"],
  ["апреля", "апрель", "апр", "april", "apr"],
  ["мая", "май", "may"],
  ["июня", "июнь", "июн", "june", "jun"],
  ["июля", "июль", "июл", "july", "jul"],
  ["августа", "август", "авг", "august", "aug"],
  ["сентября", "сентябрь", "сен", "сент", "september", "sept", "sep"],
  ["октября", "октябрь", "окт", "october", "oct"],
  ["ноября", "ноябрь", "ноя", "november", "nov"],
  ["декабря", "декабрь", "дек", "december", "dec"],
];
const MONTH_ALT = MONTHS.flat().sort(byLength).join("|");
const monthOf = (word: string) => MONTHS.findIndex((forms) => forms.includes(word.toLowerCase().replace(/\.$/, ""))) + 1;

const TAG = rx("(?<=\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_\\-/]*)");
const LIST = rx("(?<=\\s)~([\\p{L}\\p{N}_][\\p{L}\\p{N}_\\-]*)");
const PRIORITY = rx("(?<=\\s)(!!!|!!|!(?:высокий|высокая|средний|средняя|низкий|низкая|high|medium|med|low|[1-3])?)(?=\\s)");

const REPEAT_WEEKDAYS = rx(`${L}(по будням|каждый будний день|по рабочим дням|every weekday|weekdays)${R}`);
const REPEAT_WEEKENDS = rx(`${L}(по выходным|every weekend|weekends)${R}`);
const REPEAT_ON_DAYS = rx(`${L}(?:каждый|каждую|каждое|по|every)\\s+((?:${DAY_ALT})(?:\\s*(?:,|и|and)\\s*(?:${DAY_ALT}))*)${R}`);
const REPEAT_EVERY_N = rx(`${L}(?:каждые|каждый|каждую|every)\\s+(\\d{1,2})\\s+(дн(?:я|ей)|день|недел(?:и|ь|ю)|месяц(?:а|ев)?|год(?:а)?|лет|days?|weeks?|months?|years?)${R}`);
const REPEAT_SIMPLE = rx(`${L}(?:(?:каждый|каждую|every)\\s+(день|неделю|месяц|год|day|week|month|year)|(ежедневно|еженедельно|ежемесячно|ежегодно|daily|weekly|monthly|yearly|annually))${R}`);

const RELATIVE_NUM = rx(`${L}(?:через|in)\\s+(\\d{1,3})\\s+(минут[уы]?|мин|час(?:а|ов)?|ч|дн(?:я|ей)|день|недел(?:ю|и|ь)|месяц(?:а|ев)?|год(?:а)?|лет|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)${R}`);
const RELATIVE_WORD = rx(`${L}(?:через|in)\\s+(?:an?\\s+|one\\s+)?(полчаса|минуту|час|день|неделю|месяц|год|half an hour|minute|hour|day|week|month|year)${R}`);

const DAY_AFTER_TOMORROW = rx(`${L}(послезавтра|day after tomorrow)${R}`);
const TOMORROW = rx(`${L}(завтра|tomorrow|tmrw|tmr)${R}`);
const TODAY = rx(`${L}(сегодня|today)${R}`);
const NEXT_WEEK = rx(`${L}(на следующей неделе|next week)${R}`);
const WEEKEND = rx(`${L}(в выходные|на выходных|this weekend|on the weekend)${R}`);
const NEXT_WEEKDAY = rx(`${L}(?:в\\s+|во\\s+)?(?:следующий|следующую|следующее|next)\\s+(${DAY_ALT})${R}`);
const WEEKDAY = rx(`${L}(?:(?:в|во|on|this)\\s+(${DAY_ALT})|(${FULL_DAY_ALT}))${R}`);
const DAY_MONTH = rx(`${L}(\\d{1,2})\\s+(${MONTH_ALT})\\.?(?:\\s+(\\d{4}))?${R}`);
const MONTH_DAY = rx(`${L}(${MONTH_ALT})\\.?\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?${R}`);
const NUMERIC_DATE = rx(`${L}(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?(?![\\p{L}\\p{N}]|[.:/]\\d)`);

const NOON = rx(`${L}(в полдень|at noon|noon)${R}`);
// "в 2 часа дня": the word "часа" belongs to the time.
const TIME_WITH_PREP = rx(`${L}(?:в|к|at|@)\\s*(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s*час(?:а|ов)?)?(?:\\s*(утра|дня|вечера|ночи|am|pm|a\\.m\\.|p\\.m\\.))?${R}`);
const SUFFIX = "(утра|дня|вечера|ночи|am|pm)";
/** Six groups: start hour, minutes, suffix; end hour, minutes, suffix. */
const TIME_RANGE = rx(`${L}(?:с|from)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*${SUFFIX}?\\s*(?:до|to|-|–|—)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*${SUFFIX}?${R}`);
const TIME_RANGE_COLON = rx(`${L}(\\d{1,2}):(\\d{2})()\\s*[-–—]\\s*(\\d{1,2}):(\\d{2})\\s*(am|pm)?${R}`);
const TIME_SUFFIX_ONLY = new RegExp(`(\\d{1,2}):(\\d{2})\\s*${SUFFIX}?`, "iu");
const DURATION = rx(`${L}(?<!(?:в|к|at|через|in)\\s)(?:на\\s+|for\\s+)?(\\d{1,3}(?:[.,]\\d)?)\\s*(часа|часов|час|ч|минуты|минут|минуту|мин|hours|hour|hrs|hr|h|minutes|minute|mins|min)${R}`);
const DURATION_WORD = rx(`${L}(?:(?:на|for)\\s+(?:час|an hour|one hour)|(?:на\\s+)?(?:полтора часа|полчаса)|(?:for\\s+)?half an hour)${R}`);
const TIME_COLON = rx(`${L}(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?${R}`);
const TIME_SUFFIX = rx(`${L}(\\d{1,2})()\\s*(утра|дня|вечера|ночи|am|pm)${R}`);
const PART_OF_DAY = rx(`${L}(утром|днём|днем|вечером|ночью|in the morning|morning|in the afternoon|afternoon|in the evening|evening|tonight|at night)${R}`);

/** Group values like Kotlin's groupValues: unmatched groups are "". */
type Groups = string[];
const groupsOf = (m: RegExpMatchArray): Groups => Array.from(m, (g) => g ?? "");

class Scan {
  recognized: string[] = [];
  constructor(public text: string) {}

  /** Removes the first match that [accept]s and returns its groups. */
  take(regex: RegExp, accept: (g: Groups) => boolean = () => true): Groups | null {
    for (const m of this.text.matchAll(regex)) {
      const g = groupsOf(m);
      if (!accept(g)) continue;
      this.recognized.push(m[0].trim());
      this.text = this.text.slice(0, m.index!) + " " + this.text.slice(m.index! + m[0].length);
      return g;
    }
    return null;
  }
}

/** Groups: 1 = hour, 2 = minutes (optional), 3 = am/pm/утра/вечера (optional). */
function timeOfGroups(g: Groups): LocalTime | null {
  let h = parseInt(g[1], 10);
  if (Number.isNaN(h)) return null;
  const min = g[2] ? parseInt(g[2], 10) : 0;
  const suffix = (g[3] ?? "").toLowerCase().replace(/\./g, "");
  if (Number.isNaN(min) || min < 0 || min > 59) return null;
  switch (suffix) {
    case "pm": case "вечера": case "дня":
      if (h < 1 || h > 12) return null;
      if (h < 12) h += 12;
      break;
    case "am": case "утра":
      if (h < 1 || h > 12) return null;
      if (h === 12) h = 0;
      break;
    case "ночи":
      if (h < 0 || h > 12) return null;
      if (h === 12) h = 0;
      break;
  }
  if (h < 0 || h > 23) return null;
  return h * 60 + min;
}

function timeOf(hour: string, minutes: string, suffix: string): LocalTime | null {
  const m = TIME_SUFFIX_ONLY.exec(`${hour}:${minutes || "00"} ${suffix}`);
  return m ? timeOfGroups(groupsOf(m)) : null;
}

/** "с 15 до 17", "from 3pm to 5pm", "15:00-17:30": start and end, or null when not times. */
function rangeOf(g: Groups): [LocalTime, LocalTime] | null {
  const endSuffix = g[6];
  const a = timeOf(g[1], g[2], g[3] || endSuffix);
  if (a == null) return null;
  const b = timeOf(g[4], g[5], endSuffix);
  if (b == null) return null;
  return [a, b];
}

function priorityOf(raw: string): number {
  switch (raw.toLowerCase()) {
    case "!!!": case "!высокий": case "!высокая": case "!high": case "!3": return 3;
    case "!!": case "!средний": case "!средняя": case "!medium": case "!med": case "!2": return 2;
    default: return 1;
  }
}

function freqOf(word: string): Freq {
  const w = word.toLowerCase();
  if (w.startsWith("д") || w.startsWith("ежедн") || w.startsWith("day") || w === "daily") return "DAILY";
  if (w.startsWith("нед") || w.startsWith("еженед") || w.startsWith("week")) return "WEEKLY";
  if (w.startsWith("мес") || w.startsWith("ежемес") || w.startsWith("month")) return "MONTHLY";
  return "YEARLY";
}

/**
 * Pulls dates, times, repeats, priority, #tags and ~list out of a Russian or English one-liner,
 * e.g. "завтра в 10 позвонить !высокий #работа". Whatever is left becomes the title.
 */
export class QuickAddParser {
  constructor(private today: LocalDate, private now: LocalTime) {}

  parse(input: string): QuickAddResult {
    const s = new Scan(` ${input} `);
    let day: LocalDate | null = null;
    let time: LocalTime | null = null;

    const tags: string[] = [];
    for (;;) {
      const m = s.take(TAG);
      if (!m) break;
      tags.push(m[1]);
    }
    const listName = s.take(LIST)?.[1] ?? null;
    const p = s.take(PRIORITY);
    const priority = p ? priorityOf(p[1]) : null;

    const repeat = this.parseRepeat(s);

    const rel = s.take(RELATIVE_NUM, (g) => { const n = Number(g[1]); return n >= 1 && n <= 999; });
    if (rel) [day, time] = this.relative(Number(rel[1]), rel[2]);
    else {
      const w = s.take(RELATIVE_WORD);
      if (w) [day, time] = this.relative(1, w[1]);
    }

    // A range comes before single times, which would take its first half.
    let endTime: LocalTime | null = null;
    if (time == null) {
      const m = s.take(TIME_RANGE, (g) => rangeOf(g) != null) ?? s.take(TIME_RANGE_COLON, (g) => rangeOf(g) != null);
      if (m) [time, endTime] = rangeOf(m)!;
    }
    if (day == null) day = this.parseDate(s);
    const dateGiven = day != null;
    if (time == null) time = this.parseTime(s);
    const duration = endTime == null ? this.parseDuration(s) : null;

    const repeatStart = repeat ? this.firstOccurrence(repeat) : null;
    let finalDate: LocalDate | null;
    if (day != null) finalDate = day;
    else if (repeatStart != null) finalDate = repeatStart;
    else if (time != null) finalDate = time > this.now ? this.today : plusDays(this.today, 1);
    else finalDate = null;

    const title = trimChars(s.text.replace(/\s+/g, " ").trim(), ",;-—:").trim();

    return {
      title, date: finalDate, time, priority, tags, listName, repeat,
      recognized: s.recognized, dateGiven, endTime, durationMinutes: duration,
    };
  }

  private parseDuration(s: Scan): number | null {
    const w = s.take(DURATION_WORD);
    if (w) {
      const v = w[0].toLowerCase();
      if (v.includes("полтор")) return 90;
      if (v.includes("пол") || v.includes("half")) return 30;
      return 60;
    }
    const m = s.take(DURATION, (g) => { const n = Number(g[1].replace(",", ".")); return !Number.isNaN(n) && n > 0 && n <= 999; });
    if (m) {
      const n = Number(m[1].replace(",", "."));
      const unit = m[2].toLowerCase();
      const minutes = Math.trunc(unit.startsWith("м") || unit.startsWith("min") || unit === "m" ? n : n * 60);
      return minutes >= 1 && minutes <= 24 * 60 ? minutes : null;
    }
    return null;
  }

  private parseRepeat(s: Scan): RepeatRule | null {
    if (s.take(REPEAT_WEEKDAYS)) return rule("WEEKLY", { byDay: WEEKDAYS.map((day) => ({ day, ordinal: 0 })) });
    if (s.take(REPEAT_WEEKENDS)) return rule("WEEKLY", { byDay: [{ day: SATURDAY, ordinal: 0 }, { day: SUNDAY, ordinal: 0 }] });
    const on = s.take(REPEAT_ON_DAYS);
    if (on) {
      const days = dayNamesIn(on[1]);
      if (days.length) return rule("WEEKLY", { byDay: [...days].sort((a, b) => a - b).map((day) => ({ day, ordinal: 0 })) });
    }
    const every = s.take(REPEAT_EVERY_N, (g) => { const n = Number(g[1]); return n >= 1 && n <= 99; });
    if (every) return rule(freqOf(every[2]), { interval: Number(every[1]) });
    const simple = s.take(REPEAT_SIMPLE);
    if (simple) return rule(freqOf(simple[1] || simple[2]));
    return null;
  }

  private firstOccurrence(r: RepeatRule): LocalDate {
    const days = r.byDay.map((w) => w.day);
    if (r.freq === "WEEKLY" && days.length) return days.map((d) => nextOrSame(this.today, d)).sort()[0];
    return this.today;
  }

  // --- Relative "через 2 часа" / "in 3 days" ---

  private relative(n: number, unitWord: string): [LocalDate, LocalTime | null] {
    const u = unitWord.toLowerCase();
    const at = (minutes: number): [LocalDate, LocalTime] => {
      const total = this.now + minutes;
      return [plusDays(this.today, Math.floor(total / 1440)), ((total % 1440) + 1440) % 1440];
    };
    if (u.startsWith("полчас") || u.startsWith("half")) return at(30);
    if (u.startsWith("мин") || u.startsWith("min")) return at(n);
    if (u.startsWith("ч") || u.startsWith("hour") || u.startsWith("hr")) return at(n * 60);
    if (u.startsWith("д") || u.startsWith("day")) return [plusDays(this.today, n), null];
    if (u.startsWith("нед") || u.startsWith("week")) return [plusWeeks(this.today, n), null];
    if (u.startsWith("мес") || u.startsWith("month")) return [plusMonths(this.today, n), null];
    return [plusYears(this.today, n), null];
  }

  // --- Dates ---

  private parseDate(s: Scan): LocalDate | null {
    const t = this.today;
    if (s.take(DAY_AFTER_TOMORROW)) return plusDays(t, 2);
    if (s.take(TOMORROW)) return plusDays(t, 1);
    if (s.take(TODAY)) return t;
    if (s.take(NEXT_WEEK)) return next(t, MONDAY);
    if (s.take(WEEKEND)) return nextOrSame(t, SATURDAY);
    const nw = s.take(NEXT_WEEKDAY);
    if (nw) {
      const d = dayNamesIn(nw[1])[0];
      if (d) return next(t, d);
    }
    const wd = s.take(WEEKDAY, (g) => dayNamesIn(g[0]).length > 0);
    if (wd) return nextOrSame(t, dayNamesIn(wd[0])[0]);
    const dm = s.take(DAY_MONTH, (g) => this.validDate(Number(g[1]), monthOf(g[2]), g[3]) != null);
    if (dm) return this.validDate(Number(dm[1]), monthOf(dm[2]), dm[3]);
    const md = s.take(MONTH_DAY, (g) => this.validDate(Number(g[2]), monthOf(g[1]), g[3]) != null);
    if (md) return this.validDate(Number(md[2]), monthOf(md[1]), md[3]);
    const nd = s.take(NUMERIC_DATE, (g) => this.validDate(Number(g[1]), Number(g[2]), g[3]) != null);
    if (nd) return this.validDate(Number(nd[1]), Number(nd[2]), nd[3]);
    return null;
  }

  /** Date without a year means the next such date, today included. */
  private validDate(day: number, month: number, yearText: string): LocalDate | null {
    if (month < 1 || month > 12) return null;
    const y = yearText ? parseInt(yearText, 10) : NaN;
    const explicitYear = Number.isNaN(y) ? null : y < 100 ? 2000 + y : y;
    const year = explicitYear ?? parts(this.today)[0];
    if (!isValidDate(year, month, day)) return null;
    const d = date(year, month, day);
    if (explicitYear == null && d < this.today) return isValidDate(year + 1, month, day) ? date(year + 1, month, day) : null;
    return d;
  }

  // --- Times ---

  private parseTime(s: Scan): LocalTime | null {
    if (s.take(NOON)) return 12 * 60;
    for (const re of [TIME_WITH_PREP, TIME_COLON, TIME_SUFFIX]) {
      const m = s.take(re, (g) => timeOfGroups(g) != null);
      if (m) return timeOfGroups(m);
    }
    const part = s.take(PART_OF_DAY);
    if (part) {
      const w = part[0].toLowerCase();
      if (w.startsWith("утр") || w.startsWith("morning")) return 9 * 60;
      if (w.startsWith("дн") || w.startsWith("afternoon")) return 14 * 60;
      if (w.startsWith("ноч") || (w.includes("night") && !w.startsWith("to"))) return 22 * 60;
      return 19 * 60;
    }
    return null;
  }
}

function trimChars(s: string, chars: string): string {
  let a = 0, b = s.length;
  while (a < b && chars.includes(s[a])) a++;
  while (b > a && chars.includes(s[b - 1])) b--;
  return s.slice(a, b);
}
