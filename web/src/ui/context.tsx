import { createContext, useContext, useEffect, useState, useSyncExternalStore } from "react";
import type { Actions } from "../data/actions";
import type { Store } from "../data/store";
import { todayDate } from "../domain/dates";

export interface Toast {
  text: string;
  undo?: () => void;
}

export interface AppContext {
  store: Store;
  actions: Actions;
  toast: (t: Toast) => void;
}

export const Ctx = createContext<AppContext>(null as unknown as AppContext);

/** The store and actions; the component re-renders whenever the data changes. */
export function useData(): AppContext {
  const ctx = useContext(Ctx);
  useSyncExternalStore(ctx.store.subscribe, () => ctx.store.version);
  return ctx;
}

/** Today's date, updated at midnight (and when the tab comes back). */
export function useToday() {
  const [today, setToday] = useState(todayDate());
  useEffect(() => {
    const tick = () => setToday(todayDate());
    const id = setInterval(tick, 60_000);
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", tick);
    };
  }, []);
  return today;
}

/** The current time, ticking every [ms]. */
export function useNow(ms = 30_000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

/** A value remembered in this browser (view choices, not data). */
export function useLocal<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(`tiktak.${key}`);
      return raw != null ? (JSON.parse(raw) as T) : initial;
    } catch {
      return initial;
    }
  });
  const set = (v: T) => {
    setValue(v);
    try { localStorage.setItem(`tiktak.${key}`, JSON.stringify(v)); } catch { /* storage refused */ }
  };
  return [value, set];
}
