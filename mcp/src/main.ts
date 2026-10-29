import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createInterface } from "node:readline";
import { signIn, signOut } from "../../web/src/data/supabase";
import { NodeStore } from "./nodeStore";
import { clearSession, loadSession, saveSession, sessionFile, sessionProvider } from "./session";
import { registerTools } from "./tools";

/**
 * `node tiktak-mcp.mjs` — the connector Claude starts (MCP over stdin/stdout: nothing else may
 * be printed to stdout).
 * `node tiktak-mcp.mjs login | logout | status` — run by the user in a terminal.
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
const job = command === "login" ? login() : command === "logout" ? logout() : command === "status" ? Promise.resolve(status()) : serve();
job.catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
