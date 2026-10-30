import { useEffect, useRef } from "react";
import type { Task } from "../data/model";
import { localTimeOf } from "../domain/dates";
import { dueReminders, habitTriggers } from "../domain/reminders";
import { formatTime, toEpochDay } from "../core/date";
import { useData, useLocal } from "./context";

/** Reminders that came due while the site was closed are shown if no older than this (the phone has the rest). */
const MISSED_MS = 15 * 60_000;
const CHECK_MS = 20_000;

export const notifySupported = () => typeof window !== "undefined" && "Notification" in window;

/** Whether this browser shows reminders (a choice per computer, off until asked for). */
export function useNotifySetting() {
  return useLocal<boolean>("notify", false);
}

/**
 * Shows task, event and habit reminders while a tab of the site is open, as notifications of the
 * computer (ReminderScheduler.deliverDueAndReschedule). Tabs take turns through a lock and share
 * the time checked up to, so each reminder shows once.
 */
export function useTabReminders(onOpen: (t: Task) => void) {
  const { store, toast } = useData();
  const [enabled] = useNotifySetting();
  const openRef = useRef(onOpen);
  openRef.current = onOpen;

  useEffect(() => {
    if (!enabled || !notifySupported()) return;
    const key = `tiktak.remindersCheckedUntil:${store.userEmail}`;
    const check = () => {
      // Not before the first sync: reminders that arrive with it would be counted as already checked.
      if (!store.status.lastSyncAt) return;
      const now = Date.now();
      let from = Number(localStorage.getItem(key) ?? now);
      if (!Number.isFinite(from) || from > now) from = now;
      from = Math.max(from, now - MISSED_MS);
      localStorage.setItem(key, String(now));
      const tasks = new Map(store.live("tasks").filter((t) => t.status === "OPEN").map((t) => [t.id, t]));
      const due = dueReminders(tasks, store.live("reminders"), from, now);
      const seen = new Set<string>();
      for (const { task, at } of due) {
        if (seen.has(task.id)) continue;
        seen.add(task.id);
        const when = task.isAllDay ? "сегодня" : formatTime(localTimeOf(task.isEvent && task.startAt != null ? task.startAt : task.dueAt ?? at));
        show(task.title || "Напоминание", task.isEvent ? `Событие · ${when}${task.location ? ` · ${task.location}` : ""}` : `Задача · ${when}`, `${task.id}@${at}`, () => openRef.current(task));
        toast({ text: `🔔 ${task.title}` });
      }
      for (const { habit, day } of habitTriggers(store.live("habits"), from, now)) {
        const epochDay = toEpochDay(day);
        const done = (store.index("habit_checkins", "habitId").get(habit.id) ?? []).some((c) => c.day === epochDay && c.count >= habit.goal);
        if (!done) show(`${habit.emoji} ${habit.name}`, "Привычка на сегодня", `habit:${habit.id}@${day}`, () => undefined);
      }
    };
    const run = () => {
      // One tab at a time: the next one sees the time the first checked up to.
      if (navigator.locks) void navigator.locks.request("tiktak-reminders", async () => check());
      else check();
    };
    run();
    const id = setInterval(run, CHECK_MS);
    return () => clearInterval(id);
  }, [enabled, store, toast]);
}

function show(title: string, body: string, tag: string, onClick: () => void) {
  if (Notification.permission !== "granted") return;
  try {
    const n = new Notification(title, { body, tag, icon: "./icon-192.png" });
    n.onclick = () => {
      window.focus();
      onClick();
      n.close();
    };
  } catch {
    // Some mobile browsers notify only from a service worker.
    void navigator.serviceWorker?.ready.then((reg) => reg.showNotification(title, { body, tag, icon: "./icon-192.png" })).catch(() => undefined);
  }
}

/** Asks the browser for permission and shows a sample. */
export async function enableNotifications(): Promise<boolean> {
  if (!notifySupported()) return false;
  const p = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
  if (p !== "granted") return false;
  show("Tik Tak", "Напоминания будут приходить сюда, пока сайт открыт", "tiktak-test", () => undefined);
  return true;
}
