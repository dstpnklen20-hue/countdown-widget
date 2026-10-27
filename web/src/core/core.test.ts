import { describe as group, expect, test } from "vitest";
import { plusDays, plusWeeks, time } from "./date";
import { QuickAddParser } from "./quickAdd";
import { WEEKDAYS, advanced, nextAfter, parseRule, rule, toRRule } from "./repeatRule";
import { describe } from "./repeatText";

// Ported from core/src/test (RepeatRuleTest.kt, QuickAddParserTest.kt): the web must agree with the app.

const next = (r: string, from: string) => nextAfter(parseRule(r)!, from);

group("RepeatRule", () => {
  test("daily", () => {
    expect(next("FREQ=DAILY", "2026-09-28")).toBe("2026-09-29");
    expect(next("FREQ=DAILY;INTERVAL=3", "2026-09-28")).toBe("2026-10-01");
  });
  test("weekly by days", () => {
    expect(next("FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-09-28")).toBe("2026-09-30");
    expect(next("FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-10-02")).toBe("2026-10-05");
    expect(next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR", "2026-10-02")).toBe("2026-10-12");
    expect(next("FREQ=WEEKLY", "2026-09-28")).toBe("2026-10-05");
  });
  test("weekdays", () => {
    expect(next("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "2026-10-02")).toBe("2026-10-05");
  });
  test("monthly by day clamps without drift", () => {
    expect(next("FREQ=MONTHLY;BYMONTHDAY=31", "2026-01-31")).toBe("2026-02-28");
    expect(next("FREQ=MONTHLY;BYMONTHDAY=31", "2026-02-28")).toBe("2026-03-31");
    expect(next("FREQ=MONTHLY;BYMONTHDAY=-1", "2026-01-31")).toBe("2026-02-28");
  });
  test("monthly by ordinal weekday", () => {
    expect(next("FREQ=MONTHLY;BYDAY=2MO", "2026-09-28")).toBe("2026-10-12");
    expect(next("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-01")).toBe("2026-10-30");
    expect(next("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-30")).toBe("2026-11-27");
  });
  test("yearly leap day", () => {
    expect(next("FREQ=YEARLY", "2028-02-29")).toBe("2029-02-28");
  });
  test("count and until stop the series", () => {
    const r = parseRule("FREQ=DAILY;COUNT=2")!;
    expect(nextAfter(r, "2026-09-28")).toBe("2026-09-29");
    expect(nextAfter(advanced(r), "2026-09-29")).toBeNull();
    expect(next("FREQ=DAILY;UNTIL=20260930", "2026-09-30")).toBeNull();
    expect(next("FREQ=DAILY;UNTIL=20260930T235959Z", "2026-09-29")).toBe("2026-09-30");
  });
  test("round trip", () => {
    for (const s of ["FREQ=DAILY", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR", "FREQ=MONTHLY;BYDAY=-1FR", "FREQ=MONTHLY;BYMONTHDAY=15;COUNT=3", "FREQ=YEARLY;UNTIL=20301231"]) {
      expect(toRRule(parseRule(s)!)).toBe(s);
    }
  });
  test("describe in Russian", () => {
    expect(describe(rule("DAILY"))).toBe("Каждый день");
    expect(describe(rule("DAILY", { interval: 3 }))).toBe("Каждые 3 дня");
    expect(describe(parseRule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")!)).toBe("По будням");
    expect(describe(rule("WEEKLY", { byDay: [{ day: 3, ordinal: 0 }] }))).toBe("Каждую неделю в среду");
    expect(describe(rule("WEEKLY", { interval: 2, byDay: [{ day: 5, ordinal: 0 }, { day: 1, ordinal: 0 }] }))).toBe("Каждые 2 недели: пн, пт");
    expect(describe(rule("MONTHLY", { byMonthDay: -1 }))).toBe("Ежемесячно, последний день");
    expect(describe(parseRule("FREQ=MONTHLY;BYDAY=2MO")!)).toBe("Ежемесячно, второй пн");
    expect(describe(rule("YEARLY", { count: 5 }))).toBe("Ежегодно, ещё 5 раз");
  });
});

group("QuickAddParser", () => {
  // Monday, 28 September 2026, 14:00.
  const today = "2026-09-28";
  const parser = new QuickAddParser(today, time(14));
  const p = (s: string) => parser.parse(s);
  const t = time;

  test("spec example", () => {
    const r = p("завтра в 10 позвонить !высокий #работа");
    expect(r.title).toBe("позвонить");
    expect(r.date).toBe(plusDays(today, 1));
    expect(r.time).toBe(t(10));
    expect(r.priority).toBe(3);
    expect(r.tags).toEqual(["работа"]);
  });
  test("event with length or range", () => {
    const r = p("Встреча завтра в 15:00 2 часа");
    expect(r.title).toBe("Встреча");
    expect(r.date).toBe(plusDays(today, 1));
    expect(r.time).toBe(t(15));
    expect(r.durationMinutes).toBe(120);
    expect(r.dateGiven).toBe(true);

    const range = p("Планёрка с 10 до 11:30");
    expect(range.title).toBe("Планёрка");
    expect(range.time).toBe(t(10));
    expect(range.endTime).toBe(t(11, 30));
    expect(range.dateGiven).toBe(false);

    const colon = p("Lecture 9:00-10:30 tomorrow");
    expect(colon.title).toBe("Lecture");
    expect(colon.time).toBe(t(9));
    expect(colon.endTime).toBe(t(10, 30));

    expect(p("Созвон в 16 на полчаса").durationMinutes).toBe(30);
    expect(p("Тренировка полтора часа").durationMinutes).toBe(90);
    expect(p("Обед на час").durationMinutes).toBe(60);
    expect(p("Run for 45 min").durationMinutes).toBe(45);
    expect(p("с 5 до 7 вечера кино").time).toBe(t(17, 0));
  });
  test("hours word after time is not left in title", () => {
    const r = p("в 2 часа дня врач");
    expect(r.title).toBe("врач");
    expect(r.time).toBe(t(14));
    expect(r.durationMinutes).toBeNull();
    expect(p("через 2 часа позвонить").durationMinutes).toBeNull();
  });
  test("english example", () => {
    const r = p("call mom tomorrow at 5pm !high #family ~Home");
    expect(r.title).toBe("call mom");
    expect(r.date).toBe(plusDays(today, 1));
    expect(r.time).toBe(t(17));
    expect(r.priority).toBe(3);
    expect(r.tags).toEqual(["family"]);
    expect(r.listName).toBe("Home");
  });
  test("plain title is untouched", () => {
    const r = p("Купить 10 яиц и молоко");
    expect(r.title).toBe("Купить 10 яиц и молоко");
    expect(r.date).toBeNull();
    expect(r.time).toBeNull();
    expect(r.priority).toBeNull();
  });
  test("relative days and hours", () => {
    expect(p("через 3 дня отчёт").date).toBe(plusDays(today, 3));
    const inTwoHours = p("через 2 часа выключить духовку");
    expect(inTwoHours.title).toBe("выключить духовку");
    expect(inTwoHours.date).toBe(today);
    expect(inTwoHours.time).toBe(t(16));
    expect(p("in an hour stretch").time).toBe(t(15));
    expect(p("через неделю визит").date).toBe(plusWeeks(today, 1));
    expect(p("послезавтра встреча").date).toBe(plusDays(today, 2));
  });
  test("weekdays", () => {
    expect(p("в пятницу сдать проект").date).toBe("2026-10-02");
    expect(p("в пятницу сдать проект").title).toBe("сдать проект");
    expect(p("meeting on wed").date).toBe("2026-09-30");
    expect(p("понедельник планёрка").date).toBe(today);
    expect(p("в следующий понедельник планёрка").date).toBe("2026-10-05");
    expect(p("sun cream").title).toBe("sun cream");
  });
  test("absolute dates", () => {
    expect(p("15 октября день рождения").date).toBe("2026-10-15");
    expect(p("8 марта цветы").date).toBe("2027-03-08");
    expect(p("31.12 ёлка").date).toBe("2026-12-31");
    expect(p("dentist jan 5").date).toBe("2027-01-05");
    expect(p("29.02.2028 високосный").date).toBe("2028-02-29");
  });
  test("times", () => {
    expect(p("кино в 7 вечера").time).toBe(t(19));
    expect(p("созвон в 10:30").time).toBe(t(10, 30));
    expect(p("зарядка 9:15").time).toBe(t(9, 15));
    expect(p("обед в полдень").time).toBe(t(12));
    expect(p("утром пробежка").time).toBe(t(9));
    expect(p("утром пробежка").title).toBe("пробежка");
    expect(p("в 9 будильник").date).toBe(plusDays(today, 1));
    expect(p("в 18 ужин").date).toBe(today);
    expect(p("отчёт в 2026").time).toBeNull();
  });
  test("priorities", () => {
    expect(p("срочно !!!").priority).toBe(3);
    expect(p("задача !!").priority).toBe(2);
    expect(p("задача !низкий").priority).toBe(1);
    expect(p("task !2").priority).toBe(2);
    expect(p("Ура!").title).toBe("Ура!");
  });
  test("repeats", () => {
    const daily = p("каждый день медитация");
    expect(daily.title).toBe("медитация");
    expect(daily.repeat?.freq).toBe("DAILY");
    expect(daily.date).toBe(today);

    const weekdays = p("стендап по будням в 10");
    expect(weekdays.title).toBe("стендап");
    expect(weekdays.repeat?.byDay.map((w) => w.day)).toEqual(WEEKDAYS);
    expect(weekdays.time).toBe(t(10));

    const tueThu = p("тренировка по вторникам и четвергам");
    expect(tueThu.repeat?.byDay.map((w) => w.day)).toEqual([2, 4]);
    expect(tueThu.date).toBe("2026-09-29");

    expect(p("every monday review").repeat?.byDay.map((w) => w.day)).toEqual([1]);
    const biweekly = p("каждые 2 недели уборка");
    expect(biweekly.repeat?.freq).toBe("WEEKLY");
    expect(biweekly.repeat?.interval).toBe(2);
    expect(p("оплатить интернет ежемесячно").repeat?.freq).toBe("MONTHLY");
    expect(p("renew passport yearly").repeat?.freq).toBe("YEARLY");
  });
  test("tags with Cyrillic and several tags", () => {
    const r = p("купить хлеб #дом #магазин");
    expect(r.title).toBe("купить хлеб");
    expect(r.tags).toEqual(["дом", "магазин"]);
  });
});
