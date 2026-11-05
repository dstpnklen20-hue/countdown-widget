import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";
import { argbOf, cssColor } from "../data/model";
import { PALETTE, PRIORITY_COLORS } from "./format";
import { Icon } from "./icons";

export function Dialog({ title, children, actions, onClose }: { title: string; children: ReactNode; actions?: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  return (
    <div className="dialog-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-label={title}>
        <h2>{title}</h2>
        {children}
        {actions && <div className="actions">{actions}</div>}
      </div>
    </div>
  );
}

/** The round-cornered box of a task, coloured by priority like the app's PriorityCheckbox. */
export function Check({ done, wont, priority, onToggle, label }: { done: boolean; wont?: boolean; priority: number; onToggle: () => void; label?: string }) {
  const color = PRIORITY_COLORS[priority];
  return (
    <button
      className={`check ${done ? "on" : ""}`}
      style={!done && color ? { borderColor: color } : undefined}
      aria-label={label ?? (done ? "Вернуть в работу" : "Выполнить")}
      onClick={(e) => { e.stopPropagation(); onToggle(); }}
    >
      {done && <Icon name={wont ? "close" : "done"} size={14} />}
    </button>
  );
}

/** Colour choice: none (the list's colour) or one of the app's palette. */
export function Swatches({ value, onChange, allowNone = true, palette = PALETTE }: {
  value: number | null; onChange: (c: number | null) => void; allowNone?: boolean; palette?: string[];
}) {
  const current = cssColor(value);
  return (
    <div className="swatches">
      {allowNone && <button className={`swatch none ${value == null ? "on" : ""}`} title="Без цвета" aria-label="Без цвета" onClick={() => onChange(null)} />}
      {palette.map((c) => (
        <button key={c} className={`swatch ${current === c ? "on" : ""}`} style={{ background: c }} aria-label={`Цвет ${c}`} onClick={() => onChange(argbOf(c))} />
      ))}
    </div>
  );
}

export interface MenuItem {
  /** A line above this item: the start of another group. */
  group?: boolean;
  label: string;
  icon?: string;
  danger?: boolean;
  onClick: () => void;
}

/** A button that opens a small menu under it. */
export function MenuButton({ icon = "more", iconColor, label, items, up, children }: {
  icon?: string; iconColor?: string; label: string; items: MenuItem[];
  /** Opens above the button (at the bottom of a card). */
  up?: boolean;
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLDivElement>(null);
  // A long menu near the edge of the screen shifts to stay on it.
  useLayoutEffect(() => {
    const el = list.current;
    if (!open || !el) return;
    el.style.transform = "";
    const r = el.getBoundingClientRect();
    const dy = r.top < 8 ? 8 - r.top : r.bottom > window.innerHeight - 8 ? Math.max(window.innerHeight - 8 - r.bottom, 8 - r.top) : 0;
    if (dy) el.style.transform = `translateY(${dy}px)`;
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [open]);
  return (
    <div ref={ref} style={{ position: "relative", display: "inline-block" }}>
      {children ? (
        <button className="btn ghost" onClick={(e) => { e.stopPropagation(); setOpen(!open); }}>{children}</button>
      ) : (
        <button className="icon-btn" title={label} aria-label={label} onClick={(e) => { e.stopPropagation(); setOpen(!open); }}>
          <Icon name={icon} color={iconColor} />
        </button>
      )}
      {open && (
        <div ref={list} className="menu" role="menu" style={up ? { right: 0, bottom: "100%" } : { right: 0, top: "100%" }}>
          {items.map((it) => (
            <button key={it.label} role="menuitem" className={`${it.danger ? "error-text" : ""} ${it.group ? "menu-group" : ""}`} onClick={(e) => { e.stopPropagation(); setOpen(false); it.onClick(); }}>
              {it.icon && <Icon name={it.icon} size={18} />}
              {it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Asks for a name (new list, rename). */
export function NameDialog({ title, initial = "", withColor, initialColor = null, onSave, onClose }: {
  title: string; initial?: string; withColor?: boolean; initialColor?: number | null;
  onSave: (name: string, color: number | null) => void; onClose: () => void;
}) {
  const [name, setName] = useState(initial);
  const [color, setColor] = useState<number | null>(initialColor);
  const save = () => {
    if (!name.trim()) return;
    onSave(name.trim(), color);
    onClose();
  };
  return (
    <Dialog title={title} onClose={onClose} actions={<>
      <button className="btn" onClick={onClose}>Отмена</button>
      <button className="btn primary" onClick={save} disabled={!name.trim()}>Сохранить</button>
    </>}>
      <div className="form">
        <input className="field" autoFocus value={name} placeholder="Название" onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && save()} />
        {withColor && <Swatches value={color} onChange={setColor} />}
      </div>
    </Dialog>
  );
}

export function Empty({ emoji, text }: { emoji: string; text: string }) {
  return <div className="empty"><div className="big">{emoji}</div>{text}</div>;
}
