/**
 * A list named "🏠 Дом" shows 🏠 as its icon and "Дом" as its name. The icon stays inside the name,
 * so the data doesn't change and older versions just show the whole name. Port: domain/ListIcon.kt.
 */
const isFlagPart = (c: number) => c >= 0x1f1e6 && c <= 0x1f1ff;
const isPictograph = (c: number) =>
  (c >= 0x1f000 && c <= 0x1faff) || (c >= 0x2600 && c <= 0x27bf) || (c >= 0x2b00 && c <= 0x2bff) || (c >= 0x2300 && c <= 0x23ff);

export function splitListName(name: string): { icon: string | null; text: string } {
  const s = name.trimStart();
  let i = 0;
  const at = () => s.codePointAt(i) ?? 0;
  const step = () => { i += at() > 0xffff ? 2 : 1; };
  if (!isPictograph(at())) return { icon: null, text: name };
  const flag = isFlagPart(at());
  step();
  if (flag && isFlagPart(at())) step();
  for (;;) {
    const c = at();
    if (c === 0xfe0f || c === 0x20e3 || (c >= 0x1f3fb && c <= 0x1f3ff)) step();
    else if (c === 0x200d) {
      step();
      if (isPictograph(at())) step();
    } else break;
  }
  const text = s.slice(i).trim();
  // A name of nothing but an emoji stays a name: a list needs something to read.
  return text ? { icon: s.slice(0, i), text } : { icon: null, text: name };
}
