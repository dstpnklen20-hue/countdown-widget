/** What goes on paper for a task. */
export interface PrintedTask {
  title: string;
  when: string | null;
  list: string | null;
  content: string;
  checklist: { title: string; checked: boolean }[];
  subtasks: { title: string; done: boolean }[];
}

/** Prints only the task, not the page around it, through a hidden frame. */
export function printTask(t: PrintedTask): void {
  const frame = document.createElement("iframe");
  frame.title = "Печать";
  frame.setAttribute("style", "position:fixed;width:0;height:0;border:0;visibility:hidden");
  document.body.append(frame);
  const doc = frame.contentDocument!;
  doc.title = t.title;
  const add = (tag: string, text: string, parent: HTMLElement = doc.body) => {
    const el = doc.createElement(tag);
    el.textContent = text;
    parent.append(el);
    return el;
  };
  const style = doc.createElement("style");
  style.textContent = "body{font:14px/1.5 system-ui,sans-serif;margin:24px;color:#000} h1{font-size:20px;margin:0 0 4px} .meta{color:#555;margin:0 0 16px} .text{white-space:pre-wrap} h2{font-size:14px;margin:16px 0 4px} ul{list-style:none;padding:0;margin:0}";
  doc.head.append(style);
  add("h1", t.title || "(без названия)");
  const meta = [t.when, t.list].filter(Boolean).join(" · ");
  if (meta) add("p", meta).className = "meta";
  if (t.content.trim()) add("div", t.content).className = "text";
  const list = (title: string, rows: { title: string; on: boolean }[]) => {
    if (!rows.length) return;
    add("h2", title);
    const ul = add("ul", "");
    for (const r of rows) add("li", `${r.on ? "☑" : "☐"} ${r.title}`, ul);
  };
  list("Чек-лист", t.checklist.map((c) => ({ title: c.title, on: c.checked })));
  list("Подзадачи", t.subtasks.map((s) => ({ title: s.title, on: s.done })));
  // The frame needs a moment to lay out; it goes away when the print window closes.
  setTimeout(() => {
    const win = frame.contentWindow!;
    win.addEventListener("afterprint", () => frame.remove());
    win.focus();
    win.print();
  }, 50);
}
