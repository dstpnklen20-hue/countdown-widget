/**
 * End-to-end check of the connector without the real server: an in-memory stand-in for Supabase
 * (auth refresh + the sync_records table, with its "keep the newer row" trigger) answers fetch(),
 * and an MCP client calls the tools as Claude would. Run: npm test
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { plusDays } from "../../web/src/core/date";
import { blank } from "../../web/src/data/model";
import { todayDate } from "../../web/src/domain/dates";
import { NodeStore } from "../src/nodeStore";
import { sessionProvider } from "../src/session";
import { registerTools } from "../src/tools";

// --- A fake Supabase ---

type Row = { user_id: string; kind: string; id: string; updated_at: number; deleted: boolean; data: any; server_updated_at: string };
const table = new Map<string, Row>();
let clock = Date.parse("2026-01-01T00:00:00Z");
const serverNow = () => new Date((clock += 1000)).toISOString();
let refreshes = 0;

function storeRow(r: Omit<Row, "server_updated_at">) {
  const key = `${r.kind}|${r.id}`;
  const old = table.get(key);
  if (old && old.updated_at >= r.updated_at) return; // the server trigger keeps the newer row
  table.set(key, { ...r, server_updated_at: serverNow() });
}

globalThis.fetch = (async (input: string | URL, init?: RequestInit) => {
  const url = new URL(String(input));
  const method = init?.method ?? "GET";
  const json = (code: number, body: unknown) => new Response(JSON.stringify(body), { status: code });
  if (url.pathname === "/auth/v1/token") {
    refreshes++;
    return json(200, { access_token: `access-${refreshes}`, refresh_token: `refresh-${refreshes}`, expires_in: 3600, user: { id: "user-1", email: "test@example.org" } });
  }
  assert.equal(url.pathname, "/rest/v1/sync_records");
  assert.match(String((init?.headers as Record<string, string>).Authorization), /^Bearer access-/);
  if (method === "POST") {
    for (const r of JSON.parse(String(init!.body))) storeRow(r);
    return new Response("", { status: 201 });
  }
  if (method === "DELETE") {
    const kind = url.searchParams.get("kind")!.slice(3);
    const ids = url.searchParams.get("id")!.slice(4, -1).split(",").map((s) => s.replace(/"/g, ""));
    for (const id of ids) table.delete(`${kind}|${id}`);
    return new Response("", { status: 204 });
  }
  const since = url.searchParams.get("server_updated_at")?.slice(3);
  const rows = [...table.values()]
    .filter((r) => !since || r.server_updated_at > since)
    .sort((a, b) => a.server_updated_at.localeCompare(b.server_updated_at))
    .slice(0, Number(url.searchParams.get("limit")));
  return json(200, rows.map(({ kind, id, updated_at, deleted, data, server_updated_at }) => ({ kind, id, updated_at, deleted, data, server_updated_at })));
}) as typeof fetch;

// An expired session, so the first call has to refresh it (and write the new one back).
const dir = mkdtempSync(join(tmpdir(), "tiktak-mcp-test-"));
process.env.TIKTAK_SESSION_FILE = join(dir, "session.json");
writeFileSync(process.env.TIKTAK_SESSION_FILE, JSON.stringify({ accessToken: "old", refreshToken: "refresh-0", expiresAt: 0, userId: "user-1", email: "test@example.org" }));

// A list made on the "phone" before the connector starts.
const work = blank.list({ name: "Работа", sortOrder: 1 });
storeRow({ user_id: "user-1", kind: "task_lists", id: work.id, updated_at: work.updatedAt, deleted: false, data: work });

const server = new McpServer({ name: "tiktak", version: "test" });
registerTools(server, new NodeStore(sessionProvider()));
const [a, b] = InMemoryTransport.createLinkedPair();
await server.connect(a);
const client = new Client({ name: "smoke", version: "1" });
await client.connect(b);

async function call(name: string, args: Record<string, unknown> = {}): Promise<string> {
  const r = (await client.callTool({ name, arguments: args })) as { content: { text: string }[]; isError?: boolean };
  const out = r.content.map((c) => c.text).join("\n");
  if (r.isError) throw new Error(`${name}: ${out}`);
  return out;
}
const idIn = (s: string) => /\[([0-9a-f]{8})\]/.exec(s)![1];
const rowOf = (shortId: string) => [...table.values()].find((r) => r.kind === "tasks" && r.id.startsWith(shortId))!;
const allColumns = Object.keys(blank.task({})).sort();

const today = todayDate();
const tomorrow = plusDays(today, 1);
let passed = 0;
const step = async (name: string, fn: () => Promise<void>) => {
  await fn();
  passed++;
  console.log(`ok  ${name}`);
};

await step("tools listed", async () => {
  const { tools } = await client.listTools();
  assert.equal(tools.length, 12);
});

await step("overview seeds Inbox and the personal calendar, sees the phone's list", async () => {
  const out = await call("tiktak_overview");
  assert.match(out, /Входящие/);
  assert.match(out, /Работа/);
  assert.match(out, /Личное/);
  assert.ok(table.has("task_lists|inbox") && table.has("calendars|personal"));
  assert.equal(table.get("task_lists|inbox")!.updated_at, 0);
  assert.ok(refreshes >= 1, "the expired token was refreshed");
});

let taskId = "";
await step("create a task with time and duration: full row, reminder at time", async () => {
  const out = await call("tiktak_create_task", { title: "Отчёт", list: "работа", date: tomorrow, time: "10:00", end_time: "11:30", priority: 3, tags: ["отчёты"] });
  taskId = idIn(out);
  const row = rowOf(taskId);
  assert.deepEqual(Object.keys(row.data).sort(), allColumns);
  assert.equal(row.data.listId, work.id);
  assert.equal(row.data.dueAt - row.data.startAt, 90 * 60_000);
  assert.equal(row.data.priority, 3);
  const reminders = [...table.values()].filter((r) => r.kind === "reminders" && r.data.taskId === row.id);
  assert.equal(reminders.length, 1);
  assert.equal(reminders[0].data.offsetMinutes, 0);
  assert.ok([...table.values()].some((r) => r.kind === "tags" && r.data.name === "отчёты"));
});

let quickId = "";
await step("quick add parses date, time, priority, tag", async () => {
  const out = await call("tiktak_quick_add", { text: "завтра в 18 позвонить маме !высокий #семья" });
  quickId = idIn(out);
  const row = rowOf(quickId);
  assert.equal(row.data.title, "позвонить маме");
  assert.equal(row.data.priority, 3);
  assert.equal(row.data.listId, "inbox");
  assert.match(out, /#семья/);
});

let eventId = "";
await step("create an event: start..end, default reminder of the calendar", async () => {
  const out = await call("tiktak_create_event", { title: "Стоматолог", date: tomorrow, time: "14:00", end_time: "15:30", location: "Ленина 1" });
  eventId = idIn(out);
  const row = rowOf(eventId);
  assert.equal(row.data.isEvent, 1);
  assert.equal(row.data.dueAt - row.data.startAt, 90 * 60_000);
  const reminders = [...table.values()].filter((r) => r.kind === "reminders" && r.data.taskId === row.id);
  assert.equal(reminders[0]?.data.offsetMinutes, 30);
});

await step("overnight event ends the next morning", async () => {
  const out = await call("tiktak_create_event", { title: "Сон", date: today, time: "23:00", end_time: "07:00" });
  const row = rowOf(idIn(out));
  assert.equal(row.data.dueAt - row.data.startAt, 8 * 3_600_000);
});

await step("agenda for tomorrow shows tasks and events in time order", async () => {
  const out = await call("tiktak_agenda", { from: tomorrow });
  const lines = out.split("\n");
  const i = (s: string) => lines.findIndex((l) => l.includes(s));
  assert.ok(i("Отчёт") > 0 && i("Стоматолог") > i("Отчёт") && i("позвонить маме") > i("Стоматолог"), out);
  assert.match(out, /10:00–11:30/);
  assert.match(out, /Сон.*\(день 2 из 2\)/);
});

await step("an edit made on the phone shows up (newer stamp wins)", async () => {
  const row = rowOf(taskId);
  storeRow({ ...row, updated_at: row.updated_at + 5_000, data: { ...row.data, title: "Отчёт за октябрь", updatedAt: row.updated_at + 5_000 } });
  const out = await call("tiktak_search", { query: "октябрь" });
  assert.match(out, /Отчёт за октябрь/);
});

await step("update only the time keeps the day; edit beats the phone's stamp", async () => {
  const before = rowOf(taskId);
  await call("tiktak_update", { id: taskId, time: "12:00", end_time: "13:00" });
  const row = rowOf(taskId);
  assert.ok(row.updated_at > before.updated_at);
  assert.match(await call("tiktak_get", { id: taskId }), new RegExp(`${tomorrow} 12:00–13:00`));
  assert.equal(row.data.title, "Отчёт за октябрь");
});

await step("tasks filter by list", async () => {
  const out = await call("tiktak_tasks", { list: "Работа" });
  assert.match(out, /Отчёт за октябрь/);
  assert.doesNotMatch(out, /позвонить маме/);
});

await step("completing a repeating task moves it on and keeps a done copy", async () => {
  const out = await call("tiktak_create_task", { title: "Зарядка", date: today, time: "07:30", repeat: "FREQ=DAILY" });
  const id = idIn(out);
  const done = await call("tiktak_complete", { id });
  assert.match(done, /следующий раз/);
  const series = rowOf(id);
  assert.equal(series.data.status, "OPEN");
  assert.ok([...table.values()].some((r) => r.kind === "tasks" && r.data.title === "Зарядка" && r.data.status === "DONE" && !r.data.repeatRule));
});

await step("checklist and plain completion", async () => {
  await call("tiktak_checklist_add", { id: quickId, items: ["купить торт"] });
  assert.match(await call("tiktak_get", { id: quickId }), /☐ купить торт/);
  await call("tiktak_complete", { id: quickId });
  assert.equal(rowOf(quickId).data.status, "DONE");
});

await step("delete goes to the trash (deleted = 1, row kept)", async () => {
  await call("tiktak_delete", { id: eventId });
  const row = rowOf(eventId);
  assert.equal(row.deleted, true);
  assert.equal(row.data.deleted, 1);
  await assert.rejects(call("tiktak_get", { id: eventId }), /Нет задачи/);
});

await step("unknown list is a clear error", async () => {
  await assert.rejects(call("tiktak_create_task", { title: "x", list: "Нет такого" }), /Нет списка/);
});

await client.close();
console.log(`\n${passed} проверок пройдено.`);
