/**
 * The Supabase project behind sync, the same one the Android app uses (data/sync/Supabase.kt).
 * The publishable key is meant to ship inside apps: row-level security on the server lets each
 * account reach only its own records.
 */
export const SUPABASE_URL = "https://bjwpdcenqeckobvtpvwy.supabase.co";
export const SUPABASE_KEY = "sb_publishable_4KRzQLfSBYfhhr7CjLKdrw_dTrx72js";
const TABLE = "sync_records";
/** The server returns at most 1000 rows per request. */
const STAMPS_PAGE = 1000;

export interface Session {
  accessToken: string;
  refreshToken: string;
  /** Epoch seconds. */
  expiresAt: number;
  userId: string;
  email: string;
}

/** One record on the server: a row of an app table, as JSON. */
export interface RemoteRecord {
  kind: string;
  id: string;
  updatedAt: number;
  deleted: boolean;
  data: Record<string, unknown>;
  /** ISO time the server stored it; pulls go in that order. */
  serverAt?: string;
}

/** A failure with a message ready to show to the user. */
export class SyncError extends Error {
  constructor(message: string, public code = 0, public authLost = false) {
    super(message);
  }
}

async function http(method: string, url: string, token: string | null, body?: unknown, headers: Record<string, string> = {}) {
  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers: {
        apikey: SUPABASE_KEY,
        Authorization: `Bearer ${token ?? SUPABASE_KEY}`,
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new SyncError("Нет связи с сервером. Проверьте интернет.");
  }
  const text = await res.text();
  return { code: res.status, text };
}

/** Turns Supabase's error answers into Russian messages (as in the app). */
function failure(r: { code: number; text: string }): SyncError {
  let json: Record<string, unknown> = {};
  try { json = JSON.parse(r.text); } catch { /* not JSON */ }
  const code = String(json.error_code ?? json.error ?? "");
  const raw = String(json.msg ?? json.message ?? json.error_description ?? `HTTP ${r.code}`);
  let message: string;
  if (code === "invalid_credentials" || raw.includes("Invalid login credentials")) message = "Неверная почта или пароль.";
  else if (code === "email_not_confirmed") message = "Почта не подтверждена: откройте письмо и нажмите ссылку, затем войдите.";
  else if (code === "user_already_exists" || raw.includes("already registered")) message = "Такой аккаунт уже есть — нажмите «Войти».";
  else if (code === "weak_password" || raw.includes("Password should be")) message = "Пароль слишком простой: нужно не меньше 6 символов.";
  else if (code === "email_address_invalid" || (/invalid/i.test(raw) && /email/i.test(raw))) message = "Похоже, в адресе почты ошибка.";
  else if (code.includes("rate_limit")) message = "Слишком много попыток. Подождите немного и попробуйте снова.";
  else message = `Ошибка сервера: ${raw}`;
  const authLost = r.code === 401 || ["refresh_token_not_found", "refresh_token_already_used", "session_not_found", "invalid_grant"].includes(code);
  return new SyncError(message, r.code, authLost);
}

function sessionFrom(json: any): Session {
  return {
    accessToken: json.access_token,
    refreshToken: json.refresh_token,
    expiresAt: json.expires_at ?? Math.floor(Date.now() / 1000) + (json.expires_in ?? 3600),
    userId: json.user.id,
    email: json.user.email ?? "",
  };
}

export async function signIn(email: string, password: string): Promise<Session> {
  const r = await http("POST", `${SUPABASE_URL}/auth/v1/token?grant_type=password`, null, { email: email.trim(), password });
  if (r.code < 200 || r.code > 299) throw failure(r);
  return sessionFrom(JSON.parse(r.text));
}

/** Null when the project asks to confirm the email first. */
export async function signUp(email: string, password: string): Promise<Session | null> {
  const r = await http("POST", `${SUPABASE_URL}/auth/v1/signup`, null, { email: email.trim(), password });
  if (r.code < 200 || r.code > 299) throw failure(r);
  const json = JSON.parse(r.text);
  return json.access_token ? sessionFrom(json) : null;
}

export async function refresh(session: Session): Promise<Session> {
  const r = await http("POST", `${SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, null, { refresh_token: session.refreshToken });
  if (r.code < 200 || r.code > 299) {
    // A rejected refresh token means the session is over; a 5xx is just a bad moment.
    if (r.code >= 400 && r.code < 500) throw new SyncError("Сеанс истёк — войдите снова.", r.code, true);
    throw failure(r);
  }
  return sessionFrom(JSON.parse(r.text));
}

export async function signOut(session: Session): Promise<void> {
  try { await http("POST", `${SUPABASE_URL}/auth/v1/logout`, session.accessToken); } catch { /* signing out anyway */ }
}

/** The table `sync_records` (supabase/schema.sql): every row of every app table, as JSON. */
export class SyncApi {
  private base = `${SUPABASE_URL}/rest/v1/${TABLE}`;

  constructor(private session: (forceRefresh: boolean) => Promise<Session>) {}

  /** Retries once with a fresh token when the server says the old one expired. */
  private async call(method: string, url: string, body?: unknown, headers: Record<string, string> = {}) {
    let r = await http(method, url, (await this.session(false)).accessToken, body, headers);
    if (r.code === 401) r = await http(method, url, (await this.session(true)).accessToken, body, headers);
    if (r.code < 200 || r.code > 299) throw failure(r);
    return r;
  }

  /** The server keeps an existing record when it is as new or newer (a trigger guards it). */
  async upsert(records: RemoteRecord[]): Promise<void> {
    if (!records.length) return;
    const userId = (await this.session(false)).userId;
    const body = records.map((rec) => ({
      user_id: userId, kind: rec.kind, id: rec.id, updated_at: rec.updatedAt, deleted: rec.deleted, data: rec.data,
    }));
    await this.call("POST", `${this.base}?on_conflict=user_id,kind,id`, body, { Prefer: "resolution=merge-duplicates,return=minimal" });
  }

  /** Removes records for good (tasks purged from the trash). */
  async remove(kind: string, ids: string[]): Promise<void> {
    if (!ids.length) return;
    const userId = (await this.session(false)).userId;
    const list = ids.map((id) => `"${id.replace(/"/g, "")}"`).join(",");
    await this.call("DELETE", `${this.base}?user_id=eq.${userId}&kind=eq.${encodeURIComponent(kind)}&id=in.${encodeURIComponent(`(${list})`)}`);
  }

  /** Key and version of every record of the account, no data (SupabaseApi.stamps in the app). */
  async stamps(): Promise<{ kind: string; id: string; updatedAt: number }[]> {
    const userId = (await this.session(false)).userId;
    const out: { kind: string; id: string; updatedAt: number }[] = [];
    for (;;) {
      const r = await this.call("GET", `${this.base}?select=kind,id,updated_at&user_id=eq.${userId}&order=kind.asc,id.asc&limit=${STAMPS_PAGE}&offset=${out.length}`);
      const page = JSON.parse(r.text) as any[];
      for (const o of page) out.push({ kind: o.kind, id: o.id, updatedAt: Number(o.updated_at) });
      if (page.length < STAMPS_PAGE) return out;
    }
  }

  /** Records stored after [since] (all when null), oldest first, at most [limit]. */
  async pull(since: string | null, limit: number): Promise<RemoteRecord[]> {
    const userId = (await this.session(false)).userId;
    const filter = since ? `&server_updated_at=gt.${encodeURIComponent(since)}` : "";
    const r = await this.call(
      "GET",
      `${this.base}?select=kind,id,updated_at,deleted,data,server_updated_at&user_id=eq.${userId}${filter}&order=server_updated_at.asc&limit=${limit}`,
    );
    return (JSON.parse(r.text) as any[]).map((o) => ({
      kind: o.kind, id: o.id, updatedAt: Number(o.updated_at), deleted: !!o.deleted, data: o.data, serverAt: o.server_updated_at,
    }));
  }
}
