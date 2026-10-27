import { type Kind, KINDS, type Tables, idOf, stampAfter } from "./model";
import { type RemoteRecord, type Session, SyncApi, SyncError, refresh } from "./supabase";

/**
 * This browser's copy of the account's records, kept like the app keeps its database
 * (data/sync/SyncEngine.kt): pull what the server stored since the last pull (with a minute of
 * overlap), merge "newer updatedAt wins", push local edits right away. A copy and the edits not
 * yet sent are cached in IndexedDB, so the page opens at once and survives going offline.
 */
export type SyncState = "loading" | "idle" | "syncing" | "offline" | "error";

export interface SyncStatus {
  state: SyncState;
  message?: string;
  lastSyncAt?: number;
  /** Edits not yet on the server. */
  pending: number;
}

const PAGE = 1000;
const BATCH = 500;
const OVERLAP_MS = 60_000;
const POLL_MS = 20_000;

type RowMaps = { [K in Kind]: Map<string, Tables[K]> };

const emptyMaps = (): RowMaps => Object.fromEntries(KINDS.map((k) => [k, new Map()])) as unknown as RowMaps;

/** Rows from a backup or an older app may carry true/false: the app's own rows use 0/1. */
function normalize(data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) out[k] = typeof v === "boolean" ? (v ? 1 : 0) : v;
  return out;
}

export class Store {
  private rows: RowMaps = emptyMaps();
  private pending = new Map<string, RemoteRecord>();
  private pulledUpTo: string | null = null;
  private listeners = new Set<() => void>();
  private api: SyncApi;
  private timer: ReturnType<typeof setInterval> | undefined;
  private flushTimer: ReturnType<typeof setTimeout> | undefined;
  private retryMs = 0;
  private running = false;
  private again = false;
  /** Bumped on every change, for React's useSyncExternalStore. */
  version = 0;
  status: SyncStatus = { state: "loading", pending: 0 };

  constructor(private session: Session, private onSession: (s: Session | null) => void) {
    this.api = new SyncApi(async (force) => {
      if (force || this.session.expiresAt - 60 < Date.now() / 1000) {
        this.session = await refresh(this.session);
        this.onSession(this.session);
      }
      return this.session;
    });
  }

  get userEmail(): string {
    return this.session.email;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private emit() {
    this.version++;
    for (const l of this.listeners) l();
  }

  private setStatus(s: Partial<SyncStatus>) {
    this.status = { ...this.status, ...s, pending: this.pending.size };
    this.emit();
  }

  // --- Reading ---

  get<K extends Kind>(kind: K, id: string | null | undefined): Tables[K] | undefined {
    return id == null ? undefined : this.rows[kind].get(id);
  }

  /** Every row of [kind], deleted ones included (the trash needs them). */
  all<K extends Kind>(kind: K): Tables[K][] {
    return [...this.rows[kind].values()];
  }

  /** Rows of [kind] not deleted. */
  live<K extends Kind>(kind: K): Tables[K][] {
    return this.all(kind).filter((r) => !(r as { deleted: number }).deleted);
  }

  // --- Writing ---

  /**
   * Stores edited or new rows and sends them. Each is stamped after the version it replaces, so
   * it wins on every device. [rows] must be complete (see model.ts).
   */
  put<K extends Kind>(kind: K, ...rows: Tables[K][]): Tables[K][] {
    const out: Tables[K][] = [];
    for (const row of rows) {
      const id = idOf(kind, row);
      const stored = this.rows[kind].get(id) as { updatedAt?: number } | undefined;
      const stamped = { ...row, updatedAt: stampAfter(Math.max(row.updatedAt ?? 0, stored?.updatedAt ?? 0)) } as Tables[K];
      this.rows[kind].set(id, stamped);
      this.pending.set(`${kind}|${id}`, {
        kind, id, updatedAt: stamped.updatedAt, deleted: !!(stamped as { deleted: number }).deleted, data: stamped as unknown as Record<string, unknown>,
      });
      out.push(stamped);
    }
    this.setStatus({});
    this.scheduleFlush(300);
    return out;
  }

  /**
   * Stores a row only if there is none with its id, keeping its own stamp: the starting rows every
   * device makes alike (Inbox, the personal calendar) are stamped 0, so any real edit wins.
   */
  seed<K extends Kind>(kind: K, row: Tables[K]): void {
    const id = idOf(kind, row);
    if (this.rows[kind].has(id)) return;
    this.rows[kind].set(id, row);
    this.pending.set(`${kind}|${id}`, { kind, id, updatedAt: row.updatedAt, deleted: false, data: row as unknown as Record<string, unknown> });
    this.setStatus({});
    this.scheduleFlush(300);
  }

  /** Removes rows for good, here and on the server (phones keep theirs until they purge them). */
  async purge(kind: Kind, ids: string[]): Promise<void> {
    for (const id of ids) {
      this.rows[kind].delete(id);
      this.pending.delete(`${kind}|${id}`);
    }
    this.setStatus({});
    void this.saveCache();
    try {
      await this.api.remove(kind, ids);
    } catch (e) {
      this.fail(e);
    }
  }

  // --- Sync ---

  /** [onReady] runs once the first sync went through (what is on the server is known by then). */
  async start(onReady?: () => void): Promise<void> {
    await this.loadCache();
    this.emit();
    await this.sync();
    if (this.status.state === "idle") onReady?.();
    this.timer = setInterval(() => void this.sync(), POLL_MS);
    document.addEventListener("visibilitychange", this.onVisible);
    window.addEventListener("online", this.onVisible);
  }

  stop(): void {
    clearInterval(this.timer);
    clearTimeout(this.flushTimer);
    document.removeEventListener("visibilitychange", this.onVisible);
    window.removeEventListener("online", this.onVisible);
  }

  private onVisible = () => {
    if (document.visibilityState === "visible") void this.sync();
  };

  private scheduleFlush(ms: number) {
    clearTimeout(this.flushTimer);
    this.flushTimer = setTimeout(() => void this.sync(), ms);
  }

  /** Push, then pull; runs one at a time (a request during a run makes one more run). */
  async sync(): Promise<void> {
    if (this.running) {
      this.again = true;
      return;
    }
    this.running = true;
    if (this.status.state !== "loading") this.setStatus({ state: "syncing" });
    try {
      do {
        this.again = false;
        await this.push();
        await this.pull();
      } while (this.again);
      this.retryMs = 0;
      this.setStatus({ state: "idle", message: undefined, lastSyncAt: Date.now() });
    } catch (e) {
      this.fail(e);
    } finally {
      this.running = false;
      void this.saveCache();
    }
  }

  private fail(e: unknown) {
    const err = e instanceof SyncError ? e : new SyncError(String(e));
    if (err.authLost) {
      this.stop();
      this.onSession(null);
      return;
    }
    const offline = !navigator.onLine || err.code === 0;
    this.setStatus({ state: offline ? "offline" : "error", message: err.message });
    // Try again later, slower each time, up to a minute.
    this.retryMs = Math.min(Math.max(this.retryMs * 2, 5_000), 60_000);
    if (this.pending.size) this.scheduleFlush(this.retryMs);
  }

  private async push() {
    if (!this.pending.size) return;
    const batch = [...this.pending.values()];
    for (let i = 0; i < batch.length; i += BATCH) {
      const chunk = batch.slice(i, i + BATCH);
      await this.api.upsert(chunk);
      // An edit made while sending stays pending (it has a newer stamp).
      for (const rec of chunk) {
        const key = `${rec.kind}|${rec.id}`;
        if (this.pending.get(key)?.updatedAt === rec.updatedAt) this.pending.delete(key);
      }
    }
    this.setStatus({});
  }

  private async pull() {
    let since = this.pulledUpTo ? new Date(Date.parse(this.pulledUpTo) - OVERLAP_MS).toISOString() : null;
    let newest = this.pulledUpTo;
    let changed = false;
    for (;;) {
      const page = await this.api.pull(since, PAGE);
      for (const rec of page) changed = this.merge(rec) || changed;
      const last = page[page.length - 1]?.serverAt;
      if (last) {
        since = last;
        if (!newest || Date.parse(last) > Date.parse(newest)) newest = last;
      }
      if (page.length < PAGE) break;
    }
    this.pulledUpTo = newest;
    if (changed) this.emit();
  }

  /** Last write wins, as on the phones. */
  private merge(rec: RemoteRecord): boolean {
    const kind = rec.kind as Kind;
    const map = this.rows[kind];
    if (!map) return false; // a table this page does not know yet
    const local = map.get(rec.id) as { updatedAt: number } | undefined;
    if (local && local.updatedAt >= rec.updatedAt) return false;
    map.set(rec.id, normalize(rec.data) as never);
    return true;
  }

  // --- Cache (IndexedDB) ---

  private cacheKey() {
    return `account:${this.session.userId}`;
  }

  private async loadCache() {
    const saved = await idbGet<{ rows: Record<string, [string, unknown][]>; pulledUpTo: string | null; pending: RemoteRecord[] }>(this.cacheKey());
    if (!saved) return;
    for (const kind of KINDS) this.rows[kind] = new Map(saved.rows[kind] ?? []) as never;
    this.pulledUpTo = saved.pulledUpTo;
    for (const rec of saved.pending ?? []) this.pending.set(`${rec.kind}|${rec.id}`, rec);
    this.status = { ...this.status, pending: this.pending.size };
  }

  private async saveCache() {
    const rows = Object.fromEntries(KINDS.map((k) => [k, [...this.rows[k].entries()]]));
    await idbSet(this.cacheKey(), { rows, pulledUpTo: this.pulledUpTo, pending: [...this.pending.values()] });
  }

  /** Forgets this browser's copy (on sign-out). */
  async clearCache() {
    await idbSet(this.cacheKey(), undefined);
  }
}

// --- A tiny IndexedDB key-value store ---

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("tiktak", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("kv");
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function idbGet<T>(key: string): Promise<T | undefined> {
  try {
    const db = await openDb();
    return await new Promise((resolve, reject) => {
      const req = db.transaction("kv").objectStore("kv").get(key);
      req.onsuccess = () => resolve(req.result as T | undefined);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return undefined;
  }
}

async function idbSet(key: string, value: unknown): Promise<void> {
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("kv", "readwrite");
      if (value === undefined) tx.objectStore("kv").delete(key);
      else tx.objectStore("kv").put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // Private windows may refuse storage: the page still works, just without the cache.
  }
}
