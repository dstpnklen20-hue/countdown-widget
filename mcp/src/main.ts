import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { signIn, signOut } from "../../web/src/data/supabase";
import { NodeStore } from "./nodeStore";
import { clearSession, loadSession, saveSession, sessionFile, sessionProvider } from "./session";
import { registerTools } from "./tools";

/**
 * `node tiktak-mcp.mjs` — the connector Claude starts (MCP over stdin/stdout: nothing else may
 * be printed to stdout).
 * `node tiktak-mcp.mjs login | logout | status | install` — run by the user in a terminal.
 */

const VERSION = "0.1.0";

function ask(question: string, hidden = false): Promise<string> {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) {
      // Print the question, then nothing of what is typed.
      const write = (rl as unknown as { _writeToOutput: (s: string) => void });
      process.stdout.write(question);
      write._writeToOutput = () => {};
      rl.question("", (answer) => {
        process.stdout.write("\n");
        rl.close();
        resolve(answer);
      });
    } else {
      rl.question(question, (answer) => {
        rl.close();
        resolve(answer);
      });
    }
  });
}

async function login() {
  const email = process.env.TIKTAK_EMAIL ?? await ask("Почта аккаунта Tik Tak: ");
  const password = process.env.TIKTAK_PASSWORD ?? await ask("Пароль (не отображается): ", true);
  const session = await signIn(email, password);
  saveSession(session);
  console.log(`Готово: коннектор вошёл как ${session.email}. Пароль не сохранён, только ключ входа в ${sessionFile()}`);
  console.log("Перезапустите Claude, чтобы он подхватил вход.");
}

async function logout() {
  const s = loadSession();
  if (s) await signOut(s);
  clearSession();
  console.log("Коннектор вышел из аккаунта Tik Tak.");
}

/** Claude Desktop's config files on this computer (the data folder moved between app versions). */
function desktopConfigs(): string[] {
  const local = process.env.LOCALAPPDATA ?? "";
  const found: string[] = [];
  const dataRoot = join(local, "Claude-Data");
  if (existsSync(dataRoot)) {
    for (const d of readdirSync(dataRoot)) {
      const f = join(dataRoot, d, "claude_desktop_config.json");
      if (existsSync(f)) found.push(f);
    }
  }
  const roaming = join(process.env.APPDATA ?? "", "Claude", "claude_desktop_config.json");
  if (existsSync(roaming)) found.push(roaming);
  return found;
}

function claudeDesktopRunning(): boolean {
  if (process.platform !== "win32") return false;
  try {
    return /Claude\.exe/i.test(execFileSync("tasklist", ["/FI", "IMAGENAME eq Claude.exe", "/NH"], { encoding: "utf8" }));
  } catch {
    return false;
  }
}

/**
 * Adds this connector to Claude Code (~/.claude.json) and Claude Desktop's chat. The desktop app
 * keeps its config in memory and writes it back on exit, so it must be closed while this runs.
 */
function install() {
  const server = fileURLToPath(import.meta.url);
  const node = process.execPath;
  const write = (file: string, entry: Record<string, unknown>) => {
    const json = JSON.parse(readFileSync(file, "utf8"));
    copyFileSync(file, `${file}.before-tiktak`);
    json.mcpServers = { ...(json.mcpServers ?? {}), tiktak: entry };
    writeFileSync(file, JSON.stringify(json, null, 2));
    console.log(`Добавлено: ${file}`);
  };
  const codeConfig = join(homedir(), ".claude.json");
  if (existsSync(codeConfig)) write(codeConfig, { type: "stdio", command: node, args: [server], env: {} });
  const desktop = desktopConfigs();
  if (!desktop.length) {
    console.log("Файл настроек Claude Desktop не найден: откройте в Claude Settings → Developer → Edit Config, закройте Claude и повторите.");
  } else if (claudeDesktopRunning()) {
    console.log("Claude Desktop запущен — он перезапишет настройки при выходе. Полностью закройте его (значок у часов → Quit) и повторите команду.");
    process.exitCode = 1;
  } else {
    for (const f of desktop) write(f, { command: node, args: [server] });
    console.log("Готово. Запустите Claude и откройте новый чат.");
  }
}

function status() {
  const s = loadSession();
  console.log(s ? `Вход выполнен: ${s.email} (${sessionFile()})` : "Вход не выполнен. Команда: login");
}

async function serve() {
  const server = new McpServer(
    { name: "tiktak", version: VERSION },
    {
      instructions:
        "Tik Tak — личный планировщик пользователя (задачи, списки, события календаря), данные синхронизируются с его телефоном и сайтом. " +
        "Даты — ГГГГ-ММ-ДД, время — ЧЧ:ММ в поясе компьютера. Для «что у меня сегодня/на неделе» — tiktak_agenda; " +
        "id в ответах — первые 8 символов, их можно передавать обратно.",
    },
  );
  registerTools(server, new NodeStore(sessionProvider()));
  await server.connect(new StdioServerTransport());
  console.error(`tiktak-mcp ${VERSION}: ${loadSession() ? `аккаунт ${loadSession()!.email}` : "вход не выполнен"}`);
}

const command = process.argv[2];
const job = command === "login" ? login()
  : command === "logout" ? logout()
  : command === "status" ? Promise.resolve(status())
  : command === "install" ? Promise.resolve(install())
  : serve();
job.catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
