import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { type Session, SyncError, refresh } from "../../web/src/data/supabase";

/**
 * The account the connector works in, kept outside the repository (%APPDATA%\tiktak-mcp on
 * Windows). Only tokens are stored, never the password: `login` signs in once, then the
 * connector refreshes the token itself and writes the new one back.
 */
export const sessionFile = (): string => process.env.TIKTAK_SESSION_FILE
  ?? join(process.env.APPDATA ?? join(homedir(), ".config"), "tiktak-mcp", "session.json");

export function loadSession(): Session | null {
  try {
    const s = JSON.parse(readFileSync(sessionFile(), "utf8")) as Session;
    return s.refreshToken && s.userId ? s : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session): void {
  const file = sessionFile();
  mkdirSync(dirname(file), { recursive: true });
  // Written whole and then renamed, so a second connector process never reads half a file.
  const tmp = `${file}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(s, null, 2), { encoding: "utf8", mode: 0o600 });
  renameSync(tmp, file);
}

export function clearSession(): void {
  rmSync(sessionFile(), { force: true });
}

export const NOT_SIGNED_IN = "Коннектор не вошёл в аккаунт Tik Tak. Выполните в терминале: node \"<папка mcp>/dist/tiktak-mcp.mjs\" login — и перезапустите Claude.";

/**
 * The current session, refreshed when it is about to expire or [force]d. Refresh tokens are
 * single-use: when two connectors (Claude Desktop and Claude Code) run at once, the other one may
 * have refreshed first — then its new token is in the file.
 */
export function sessionProvider() {
  let current = loadSession();
  return async (force: boolean): Promise<Session> => {
    if (!current) current = loadSession();
    if (!current) throw new SyncError(NOT_SIGNED_IN, 401, true);
    if (!force && current.expiresAt - 60 > Date.now() / 1000) return current;
    const onDisk = loadSession();
    if (onDisk && onDisk.refreshToken !== current.refreshToken && onDisk.expiresAt - 60 > Date.now() / 1000 && !force) {
      current = onDisk;
      return current;
    }
    try {
      current = await refresh(onDisk ?? current);
    } catch (e) {
      const again = loadSession();
      if (e instanceof SyncError && e.authLost && again && again.refreshToken !== (onDisk ?? current).refreshToken) {
        current = await refresh(again);
      } else {
        if (e instanceof SyncError && e.authLost) throw new SyncError(`Сеанс Tik Tak истёк. ${NOT_SIGNED_IN}`, 401, true);
        throw e;
      }
    }
    saveSession(current);
    return current;
  };
}
