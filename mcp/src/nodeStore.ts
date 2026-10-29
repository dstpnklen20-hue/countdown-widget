import { type Kind, KINDS, type Tables, idOf, stampAfter } from "../../web/src/data/model";
import type { Rows } from "../../web/src/data/store";
import { type RemoteRecord, type Session, SyncApi } from "../../web/src/data/supabase";

/**
 * The connector's copy of the account (like web/src/data/store.ts, without the browser): it pulls
 * what the server stored since the last pull (with a minute of overlap) before each request,
 * merges "newer updatedAt wins", and sends edits before answering, so the phone sees them at once.
 */
const PAGE = 1000;
const BATCH = 500;
const OVERLAP_MS = 60_000;

type RowMaps = { [K in Kind]: Map<string, Tables[K]> };

/** Rows from a backup or an older app may carry true/false: the app's own rows use 0/1. */
function normalize(data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) out[k] = typeof v === "boolean" ? (v ? 1 : 0) : v;
  return out;
}

export class NodeStore implements Rows {
  private rows = Object.fromEntries(KINDS.map((k) => [k, new Map()])) as unknown as RowMaps;
  private pending = new Map<string, RemoteRecord>();
  private pulledUpTo: string | null = null;
  private api: SyncApi;

  constructor(session: (forceRefresh: boolean) => Promise<Session>) {
    this.api = new SyncApi(session);
  }

  get<K extends Kind>(kind: K, id: string | null | undefined): Tables[K] | undefined {
    return id == null ? undefined : this.rows[kind].get(id);
  }

  all<K extends Kind>(kind: K): Tables[K][] {
    return [...this.rows[kind].values()];
  }

  live<K extends Kind>(kind: K): Tables[K][] {
    return this.all(kind).filter((r) => !(r as { deleted: number }).deleted);
  }

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
    return out;
  }

  seed<K extends Kind>(kind: K, row: Tables[K]): void {
    const id = idOf(kind, row);
    if (this.rows[kind].has(id)) return;
    this.rows[kind].set(id, row);
    this.pending.set(`${kind}|${id}`, { kind, id, updatedAt: row.updatedAt, deleted: false, data: row as unknown as Record<string, unknown> });
  }

  async purge(kind: Kind, ids: string[]): Promise<void> {
    for (const id of ids) {
      this.rows[kind].delete(id);
      this.pending.delete(`${kind}|${id}`);
    }
    await this.api.remove(kind, ids);
  }

  /** Sends edits, then takes what changed on the server. */
  async sync(): Promise<void> {
    await this.push();
    await this.pull();
  }

  /** Sends edits not yet on the server. */
  async push(): Promise<void> {
    const batch = [...this.pending.values()];
    for (let i = 0; i < batch.length; i += BATCH) {
      const chunk = batch.slice(i, i + BATCH);
      await this.api.upsert(chunk);
      for (const rec of chunk) {
        const key = `${rec.kind}|${rec.id}`;
        if (this.pending.get(key)?.updatedAt === rec.updatedAt) this.pending.delete(key);
      }
    }
  }

  private async pull() {
    let since = this.pulledUpTo ? new Date(Date.parse(this.pulledUpTo) - OVERLAP_MS).toISOString() : null;
    let newest = this.pulledUpTo;
    for (;;) {
      const page = await this.api.pull(since, PAGE);
      for (const rec of page) this.merge(rec);
      const last = page[page.length - 1]?.serverAt;
      if (last) {
        since = last;
        if (!newest || Date.parse(last) > Date.parse(newest)) newest = last;
      }
      if (page.length < PAGE) break;
    }
    this.pulledUpTo = newest;
  }

  private merge(rec: RemoteRecord) {
    const map = this.rows[rec.kind as Kind];
    if (!map) return;
    const local = map.get(rec.id) as { updatedAt: number } | undefined;
    if (local && local.updatedAt >= rec.updatedAt) return;
    map.set(rec.id, normalize(rec.data) as never);
  }
}
