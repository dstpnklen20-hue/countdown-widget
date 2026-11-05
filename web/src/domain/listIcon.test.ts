import { describe, expect, it } from "vitest";
import { splitListName } from "./listIcon";

describe("splitListName", () => {
  it("takes an emoji at the start as the icon", () => {
    expect(splitListName("🏠 Дом")).toEqual({ icon: "🏠", text: "Дом" });
    expect(splitListName("🏠Дом")).toEqual({ icon: "🏠", text: "Дом" });
    expect(splitListName("  ⭐ Важное ")).toEqual({ icon: "⭐", text: "Важное" });
  });
  it("keeps whole sequences together", () => {
    expect(splitListName("❤️ Семья")).toEqual({ icon: "❤️", text: "Семья" });
    expect(splitListName("👨‍💻 Работа")).toEqual({ icon: "👨‍💻", text: "Работа" });
    expect(splitListName("🏃🏽 Спорт")).toEqual({ icon: "🏃🏽", text: "Спорт" });
    expect(splitListName("🇷🇺 Поездка")).toEqual({ icon: "🇷🇺", text: "Поездка" });
  });
  it("only one icon: a second emoji belongs to the name", () => {
    expect(splitListName("🏠🏠 Дом")).toEqual({ icon: "🏠", text: "🏠 Дом" });
  });
  it("leaves other names alone", () => {
    expect(splitListName("Дом")).toEqual({ icon: null, text: "Дом" });
    expect(splitListName("Дом 🏠")).toEqual({ icon: null, text: "Дом 🏠" });
    expect(splitListName("1 курс")).toEqual({ icon: null, text: "1 курс" });
    expect(splitListName("🏠")).toEqual({ icon: null, text: "🏠" });
    expect(splitListName("")).toEqual({ icon: null, text: "" });
  });
});
