package com.claudecode.countdown.domain

/**
 * A list named "🏠 Дом" shows 🏠 as its icon and "Дом" as its name. The icon stays inside the name,
 * so the data doesn't change and older versions just show the whole name. Port: web/src/domain/listIcon.ts.
 */
data class ListName(val icon: String?, val text: String)

private fun isFlagPart(c: Int) = c in 0x1F1E6..0x1F1FF
private fun isPictograph(c: Int) = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF || c in 0x2300..0x23FF

fun splitListName(name: String): ListName {
    val s = name.trimStart()
    var i = 0
    fun at() = if (i < s.length) s.codePointAt(i) else 0
    fun step() { i += Character.charCount(at()) }
    if (!isPictograph(at())) return ListName(null, name)
    val flag = isFlagPart(at())
    step()
    if (flag && isFlagPart(at())) step()
    while (true) {
        val c = at()
        if (c == 0xFE0F || c == 0x20E3 || c in 0x1F3FB..0x1F3FF) step()
        else if (c == 0x200D) {
            step()
            if (isPictograph(at())) step()
        } else break
    }
    val text = s.substring(i).trim()
    // A name of nothing but an emoji stays a name: a list needs something to read.
    return if (text.isNotEmpty()) ListName(s.substring(0, i), text) else ListName(null, name)
}
