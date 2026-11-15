import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";

const MARGIN = 8;

/**
 * A small window beside what was clicked in the calendar, so the day stays in view: to the right
 * of the block when it fits, else to the left, always inside the screen. A click elsewhere or Esc
 * closes it, unless [canClose] says that would lose what was typed. On a phone it fills the screen.
 */
export function EntryCard({ anchor, label, canClose = true, onClose, children }: {
  /** What was clicked (or the time picked for a new event); without it, the right side of the screen. */
  anchor?: DOMRect | null; label: string; canClose?: boolean; onClose: () => void; children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);
  const latest = useRef({ canClose, onClose });
  latest.current = { canClose, onClose };

  // Placed in the layout pass, before the card is ever drawn: it shows up where it stays. (It used to wait
  // for a new event's block to appear; on a busy week that took long enough for the card to show at the
  // edge of the screen first and then jump beside the block.)
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const place = () => {
      const w = el.offsetWidth, h = el.offsetHeight, vw = window.innerWidth, vh = window.innerHeight;
      const r = anchor;
      let left = vw - w - 16, top = 72;
      if (r) {
        left = r.right + MARGIN;
        if (left + w > vw - MARGIN) left = r.left - w - MARGIN;
        if (left < MARGIN) left = Math.max(MARGIN, vw - w - MARGIN);
        top = r.top;
      }
      setPos({ left, top: Math.max(MARGIN, Math.min(top, vh - h - MARGIN)) });
    };
    place();
    // It grows when a section opens: still inside the screen, without moving sideways.
    const seen = new ResizeObserver(place);
    seen.observe(el);
    window.addEventListener("resize", place);
    return () => {
      seen.disconnect();
      window.removeEventListener("resize", place);
    };
  }, [anchor]);

  useEffect(() => {
    // Windows opened from this one (a custom repeat) and its menus are not "elsewhere".
    const outside = (e: MouseEvent) => {
      const t = e.target as HTMLElement;
      if (ref.current?.contains(t) || t.closest?.(".dialog-scrim, .menu")) return;
      if (latest.current.canClose) latest.current.onClose();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !document.querySelector(".dialog-scrim")) latest.current.onClose();
    };
    window.addEventListener("mousedown", outside);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("mousedown", outside);
      window.removeEventListener("keydown", key);
    };
  }, []);

  return (
    <div ref={ref} className="entry-card" role="dialog" aria-label={label}
      style={pos ? { left: pos.left, top: pos.top } : { left: 0, top: 0, opacity: 0, pointerEvents: "none" }}>
      {children}
    </div>
  );
}
