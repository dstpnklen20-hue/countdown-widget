import { DateTime } from "luxon";
import { type LocalDate, plusDays } from "../core/date";
import { parseRule, toRRule } from "../core/repeatRule";
import { allDayDue, dueDay, localDateOf, zone as deviceZone } from "../domain/dates";
import { isCountdown, isDone, skippedDays } from "../domain/tasks";
import { type Task, blank } from "./model";

/**
 * Port of data/Ics.kt: iCalendar (RFC 5545) files, to import events and tasks from other
 * calendars and to export ours. Covers title, description, place, start/end or due, all-day,
 * RRULE, EXDATE, VALARM triggers and the done state of tasks.
 */

/** One VEVENT or VTODO read from a file: the entry and its reminders (minutes before). */
export interface IcsItem { task: Task; reminders: number[] }

interface Prop { name: string; params: Record<string, string>; value: string }

export function parseIcs(text: string, zone: string = deviceZone()): IcsItem[] {
  // Long lines are folded: a line starting with a space or tab continues the previous one.
  const lines: string[] = [];
  for (const raw of text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.length) lines[lines.length - 1] += raw.slice(1);
    else if (raw.trim()) lines.push(raw);
  }
  const out: IcsItem[] = [];
  let props: Prop[] | null = null;
  let alarms: number[] = [];
  let inAlarm = false;
  let kind = "";
  for (const line of lines) {
    const prop = parseLine(line);
    if (!prop) continue;
    if (prop.name === "BEGIN" && (prop.value === "VEVENT" || prop.value === "VTODO")) {
      props = [];
      alarms = [];
      kind = prop.value;
    } else if (prop.name === "BEGIN" && prop.value === "VALARM") inAlarm = true;
    else if (prop.name === "END" && prop.value === "VALARM") inAlarm = false;
    else if (prop.name === "END" && (prop.value === "VEVENT" || prop.value === "VTODO")) {
      if (props) {
        const item = toItem(props, alarms, kind === "VEVENT", zone);
        if (item) out.push(item);
      }
      props = null;
    } else if (inAlarm) {
      if (prop.name === "TRIGGER") {
        const m = triggerMinutes(prop.value);
        if (m != null) alarms.push(m);
      }
    } else props?.push(prop);
  }
  return out;
}

function parseLine(line: string): Prop | null {
  // NAME;PARAM=V;PARAM=V:VALUE — the value may contain ':' itself.
  let quoted = false;
  let colon = -1;
  for (let i = 0; i < line.length; i++) {
    if (line[i] === '"') quoted = !quoted;
    if (line[i] === ":" && !quoted) { colon = i; break; }
  }
  if (colon < 0) return null;
  const head = line.slice(0, colon).split(";");
  const params: Record<string, string> = {};
  for (const p of head.slice(1)) {
    const i = p.indexOf("=");
    if (i > 0) params[p.slice(0, i).toUpperCase()] = p.slice(i + 1).replace(/^"|"$/g, "");
  }
  return { name: head[0].toUpperCase(), params, value: line.slice(colon + 1) };
}

const unescape = (s: string) => s.replace(/\\[nN]/g, "\n").replace(/\\,/g, ",").replace(/\\;/g, ";").replace(/\\\\/g, "\\");

interface When { at: number; allDay: boolean; date: LocalDate }

function parseWhen(p: Prop, zone: string): When | null {
  const v = p.value.trim();
  const ymd = (s: string) => `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
  if (!/^\d{8}/.test(v)) return null;
  if (p.params.VALUE === "DATE" || v.length === 8) {
    const d = ymd(v);
    return { at: allDayDue(d, zone).at, allDay: true, date: d };
  }
  const m = /^(\d{8})T(\d{2})(\d{2})(\d{2})/.exec(v);
  if (!m) return null;
  let z = zone;
  if (v.endsWith("Z")) z = "UTC";
  else if (p.params.TZID && DateTime.now().setZone(p.params.TZID).isValid) z = p.params.TZID;
  const at = DateTime.fromISO(`${ymd(m[1])}T${m[2]}:${m[3]}:${m[4]}`, { zone: z });
  if (!at.isValid) return null;
  return { at: at.toMillis(), allDay: false, date: localDateOf(at.toMillis(), zone) };
}

/** "-PT15M", "-P1D", "-PT1H30M", "PT0S" → minutes before the start. */
export function triggerMinutes(value: string): number | null {
  const m = /^(-)?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value.trim());
  if (!m) return null;
  const n = (s?: string) => (s ? Number(s) : 0);
  const total = n(m[2]) * 7 * 1440 + n(m[3]) * 1440 + n(m[4]) * 60 + n(m[5]);
  return m[1] === "-" || total === 0 ? total : null;
}

function toItem(props: Prop[], alarms: number[], event: boolean, zone: string): IcsItem | null {
  const one = (name: string) => props.find((p) => p.name === name);
  const uid = one("UID")?.value ?? crypto.randomUUID();
  const title = (one("SUMMARY") ? unescape(one("SUMMARY")!.value).trim() : "") || "(Без названия)";
  // The same id as the app gives this UID, so a file imported on both stays one entry.
  const id = nameUuid(`ics:${uid}`);
  const parsedRule = parseRule(one("RRULE")?.value);
  const repeatRule = parsedRule ? toRRule(parsedRule) : null;
  const content = one("DESCRIPTION") ? unescape(one("DESCRIPTION")!.value) : "";
  const location = one("LOCATION") ? unescape(one("LOCATION")!.value).trim() || null : null;
  if (!event) {
    const dueProp = one("DUE") ?? one("DTSTART");
    const due = dueProp ? parseWhen(dueProp, zone) : null;
    const done = one("STATUS")?.value === "COMPLETED";
    return {
      task: blank.task({
        id, title, content, dueAt: due?.at ?? null, isAllDay: due?.allDay ? 1 : 0, timeZone: zone, repeatRule,
        status: done ? "DONE" : "OPEN", completedAt: done ? Date.now() : null,
      }),
      reminders: alarms,
    };
  }
  const startProp = one("DTSTART");
  const start = startProp ? parseWhen(startProp, zone) : null;
  if (!start) return null;
  const endProp = one("DTEND");
  let end = endProp ? parseWhen(endProp, zone) : null;
  if (!end) {
    const dur = one("DURATION")?.value.replace(/^\+/, "");
    const minutes = dur ? triggerMinutes(`-${dur}`) : null;
    if (minutes != null && start.allDay) {
      const d = plusDays(start.date, Math.floor(minutes / 1440));
      end = { at: allDayDue(d, zone).at, allDay: true, date: d };
    } else if (minutes != null) end = { at: start.at + minutes * 60_000, allDay: false, date: start.date };
    else if (start.allDay) end = { at: allDayDue(plusDays(start.date, 1), zone).at, allDay: true, date: plusDays(start.date, 1) };
    else end = start;
  }
  const exDates = props.filter((p) => p.name === "EXDATE").flatMap((p) =>
    p.value.split(",").map((v) => parseWhen({ name: "EXDATE", params: p.params, value: v }, zone)?.date).filter((d): d is string => !!d));
  let task: Task;
  if (start.allDay) {
    // The end of an all-day event is the day after its last day.
    const lastDay = plusDays(end.date, -1);
    const last = lastDay > start.date ? lastDay : start.date;
    task = blank.task({ id, title, isEvent: 1, isAllDay: 1, startAt: start.at, dueAt: allDayDue(last, zone).at, timeZone: zone });
  } else {
    task = blank.task({ id, title, isEvent: 1, startAt: start.at, dueAt: Math.max(end.at, start.at), timeZone: zone });
  }
  const ex = [...new Set(exDates)].sort().join(",");
  return { task: { ...task, content, location, repeatRule, exDates: ex || null }, reminders: alarms };
}

// --- Writing ---

const escape = (s: string) => s.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\n/g, "\\n");

const utc = (at: number) => DateTime.fromMillis(at, { zone: "UTC" }).toFormat("yyyyMMdd'T'HHmmss") + "Z";
const basic = (d: LocalDate) => d.replace(/-/g, "");

export function writeIcs(items: IcsItem[], zone: string = deviceZone()): string {
  const out: string[] = [];
  // Lines longer than 75 characters are folded, as the format asks.
  const line = (text: string) => {
    let rest = text;
    let first = true;
    while (rest.length) {
      const take = first ? 75 : 74;
      out.push((first ? "" : " ") + rest.slice(0, take));
      rest = rest.slice(take);
      first = false;
    }
  };
  line("BEGIN:VCALENDAR");
  line("VERSION:2.0");
  line("PRODID:-//Tik Tak//RU");
  line("CALSCALE:GREGORIAN");
  const stamp = utc(Date.now());
  for (const { task: t, reminders } of items) {
    const due = t.dueAt;
    if (due == null) continue;
    const kind = t.isEvent || isCountdown(t) ? "VEVENT" : "VTODO";
    line(`BEGIN:${kind}`);
    line(`UID:${t.id}@tiktak`);
    line(`DTSTAMP:${stamp}`);
    line(`SUMMARY:${escape(t.title)}`);
    const start = t.startAt != null && t.startAt <= due ? t.startAt : due;
    if (kind === "VEVENT") {
      if (t.isAllDay) {
        const first = localDateOf(start, zone);
        const last = dueDay(t, zone) ?? first;
        line(`DTSTART;VALUE=DATE:${basic(first)}`);
        line(`DTEND;VALUE=DATE:${basic(plusDays(last, 1))}`);
      } else {
        line(`DTSTART:${utc(start)}`);
        line(`DTEND:${utc(due > start ? due : start + 30 * 60_000)}`);
      }
    } else {
      line(t.isAllDay ? `DUE;VALUE=DATE:${basic(dueDay(t, zone) ?? localDateOf(Date.now(), zone))}` : `DUE:${utc(due)}`);
      line("STATUS:" + (isDone(t) ? "COMPLETED" : "NEEDS-ACTION"));
    }
    if (t.repeatRule) line(`RRULE:${t.repeatRule}`);
    const skipped = [...skippedDays(t)].sort();
    if (skipped.length) {
      if (t.isAllDay) line("EXDATE;VALUE=DATE:" + skipped.map(basic).join(","));
      else {
        const time = DateTime.fromMillis(start, { zone });
        const lead = Math.round((Date.parse(dueDay(t, zone) ?? "") - Date.parse(localDateOf(start, zone))) / 86_400_000) || 0;
        line("EXDATE:" + skipped.map((d) => utc(DateTime.fromISO(plusDays(d, -lead), { zone }).set({ hour: time.hour, minute: time.minute }).toMillis())).join(","));
      }
    }
    if (t.location) line(`LOCATION:${escape(t.location)}`);
    if (t.content.trim()) line(`DESCRIPTION:${escape(t.content)}`);
    for (const m of reminders) {
      line("BEGIN:VALARM");
      line("ACTION:DISPLAY");
      line(`DESCRIPTION:${escape(t.title)}`);
      line("TRIGGER:" + (m === 0 ? "PT0S" : `-PT${m}M`));
      line("END:VALARM");
    }
    line(`END:${kind}`);
  }
  line("END:VCALENDAR");
  return out.join("\r\n") + "\r\n";
}

// --- Java's UUID.nameUUIDFromBytes (an MD5-based version 3 UUID) ---

export function nameUuid(name: string): string {
  const bytes = md5(new TextEncoder().encode(name));
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** MD5 (RFC 1321) of [data]. */
function md5(data: Uint8Array): Uint8Array {
  const s = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
  const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);
  const len = data.length;
  const padded = new Uint8Array(((len + 8) >> 6) * 64 + 64);
  padded.set(data);
  padded[len] = 0x80;
  const bits = len * 8;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, bits >>> 0, true);
  view.setUint32(padded.length - 4, Math.floor(bits / 2 ** 32), true);
  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (let off = 0; off < padded.length; off += 64) {
    const M = Array.from({ length: 16 }, (_, i) => view.getUint32(off + i * 4, true));
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F: number, g: number;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      F = (F + A + K[i] + M[g]) >>> 0;
      A = D;
      D = C;
      C = B;
      const r = s[(i >> 4) * 4 + (i % 4)];
      B = (B + ((F << r) | (F >>> (32 - r)))) >>> 0;
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
  }
  const out = new Uint8Array(16);
  const ov = new DataView(out.buffer);
  [a0, b0, c0, d0].forEach((w, i) => ov.setUint32(i * 4, w, true));
  return out;
}
