// Screen.kt: pure Kotlin (no android.*). Node model, screen digest, token estimate, screen hash, vision marks.
package app.tinyagent

import kotlin.math.abs
import kotlin.math.ceil

/** A copied accessibility node. Equality is identity: nodes are compared and hashed by reference, never by subtree. */
data class N(
    val text: String?, val desc: String?, val hint: String?, val viewId: String?, val cls: String,
    val l: Int, val t: Int, val r: Int, val b: Int, val visible: Boolean,
    val click: Boolean, val edit: Boolean, val checkable: Boolean, val checked: Boolean,
    val scrollFwd: Boolean, val scrollBack: Boolean, val password: Boolean, val focused: Boolean,
    val kids: List<N>, val ref: Any? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class Win(val root: N, val pkg: String, val appLabel: String, val layer: Int,
    val isIme: Boolean, val isOwn: Boolean, val isActive: Boolean)
data class Item(val id: Int, val kind: String, val label: String, val node: N)
enum class Mode { ACT, READ }
data class Digest(val text: String, val items: List<Item>, val hash: Int, val app: String,
    val actionable: Int, val unlabeled: Int, val popup: Boolean, val keyboard: Boolean)

object Screen {
    private const val SYSUI = "com.android.systemui"
    private class C(val n: N, val kind: String, val label: String, val line: String, val state: String,
        val act: Boolean, val unl: Boolean)

    fun tokens(s: String): Int {
        var a = 0; var o = 0
        for (ch in s) if (ch.code < 128) a++ else o++
        return ceil(a / 3.5).toInt() + o
    }

    /** Windows to list, top first: popups above the top full-screen window, then that window. */
    fun shown(wins: List<Win>, w: Int, h: Int): List<Win> {
        val apps = wins.filter { !it.isOwn && !it.isIme && (it.isActive || it.pkg != SYSUI) }.sortedByDescending { it.layer }
        val full = apps.indexOfFirst { area(it.root) * 10 >= w.toLong() * h * 7 }
        return if (full >= 0) apps.subList(0, full + 1) else listOfNotNull(apps.firstOrNull { it.isActive } ?: apps.firstOrNull())
    }

    fun digest(wins: List<Win>, mode: Mode, maxTokens: Int, w: Int, h: Int): Digest {
        val max = if (mode == Mode.ACT) 28 else 60
        val keyboard = wins.any { it.isIme && !it.isOwn }
        val shown = shown(wins, w, h)
        val base = shown.lastOrNull()
        var fwd = false; var back = false
        val all = ArrayList<C>()
        for (win in shown) {
            val cs = ArrayList<C>(); val used = HashSet<N>()
            fun walk(n: N) {
                if (!onScreen(n, w, h)) return
                if (n.scrollFwd) fwd = true
                if (n.scrollBack) back = true
                if (n !in used) cand(n, w, h, max, used)?.let { cs += it }
                n.kids.forEach { walk(it) }
            }
            walk(win.root)
            all += order(dedupe(cs))
        }
        var cut = false; var nb = 0; var nt = 0 // budget counters: nb = actionable, nt = text
        val sel = all.filter { c ->
            val ok = if (mode == Mode.ACT) when (c.kind) { "input" -> true; "text" -> nt++ < 4; else -> nb++ < 16 }
            else if (c.kind == "text") nt++ < 16 else nb++ < 6
            if (!ok) cut = true
            ok
        }.toMutableList()
        val head = "App: ${base?.appLabel ?: "none"} | popup: ${yn(shown.size > 1)} | keyboard: ${yn(keyboard)}"
        fun render() = buildString {
            append(head)
            sel.forEachIndexed { i, c -> append('\n').append(i + 1).append(' ').append(c.line) }
            if (cut || fwd) append("\nmore below: scroll down")
            if (back) append("\nmore above: scroll up")
        }
        var text = render()
        while (sel.isNotEmpty() && tokens(text) > maxTokens) { sel.removeAt(sel.lastIndex); cut = true; text = render() }
        var hash = (base?.pkg ?: "").hashCode() * 31 + if (keyboard) 1 else 0
        for (c in all) hash = hash * 31 + (c.kind + '\u0000' + c.label + '\u0000' + c.state).hashCode()
        return Digest(text, sel.mapIndexed { i, c -> Item(i + 1, c.kind, c.label, c.n) }, hash, base?.appLabel ?: "",
            all.count { it.act }, all.count { it.unl }, shown.size > 1, keyboard)
    }

    private fun cand(n: N, w: Int, h: Int, max: Int, used: MutableSet<N>): C? {
        if (n.edit) {
            val name = clean(n.hint, max) ?: clean(n.desc, max) ?: idName(n.viewId, max)
            val cur = if (n.password) null else clean(n.text, max)?.takeIf { it != name }
            val line = StringBuilder(if (n.password) "input password" else "input")
            if (name != null) line.append(" (").append(name).append(')')
            if (cur != null) line.append(" = \"").append(cur).append('"')
            return C(n, "input", name ?: cur ?: "input", line.toString(), (cur ?: "") + if (n.focused) "*" else "", true, false)
        }
        val own = clean(n.text, max) ?: clean(n.desc, max)
        if (!n.click && !n.checkable) return own?.let { C(n, "text", it, "text $it", "", false, false) }
        var label = own ?: clean(n.hint, max)
        if (label == null && n.click && area(n) * 10 >= w.toLong() * h * 6) return null // full-screen click container
        val got = ArrayList<String>()
        val kid = if (n.checkable) null else borrow(n, max, got, used, label == null)
        if (label == null) label = if (got.isEmpty()) idName(n.viewId, max) else clean(got.joinToString(" · "), max)
        val chk = if (n.checkable) n else kid
        val lab = label ?: "(icon, ${pos(n, w, h)})"
        if (chk != null) { val st = if (chk.checked) "on" else "off"; return C(n, "switch", lab, "switch $st $lab", st, true, label == null) }
        return C(n, "button", lab, "button $lab", "", true, label == null)
    }

    /** Up to 2 descendant labels for an unlabeled clickable; also adopts a checkable child so a row reads as a switch. */
    private fun borrow(n: N, max: Int, got: MutableList<String>, used: MutableSet<N>, want: Boolean): N? {
        var chk: N? = null
        for (k in n.kids) {
            if (!k.visible) continue
            if (k.checkable && !k.edit && chk == null) { chk = k; used += k }
            if (k.edit || (k.click && k !== chk)) continue
            if (want && got.size < 2) (clean(k.text, max) ?: clean(k.desc, max))?.let { got += it; used += k }
            borrow(k, max, got, used, want)?.let { if (chk == null) chk = it }
        }
        return chk
    }

    private fun dedupe(cs: List<C>): List<C> {
        val out = ArrayList<C>()
        for (c in cs) if (c.unl || out.none { it.kind == c.kind && it.label == c.label && abs(cy(it.n) - cy(c.n)) <= 40 }) out += c
        return out
    }

    private fun order(cs: List<C>): List<C> {
        val s = cs.sortedBy { it.n.t }; val out = ArrayList<C>(); var i = 0
        while (i < s.size) {
            var j = i
            while (j < s.size && s[j].n.t - s[i].n.t <= 16) j++
            out += s.subList(i, j).sortedBy { it.n.l }; i = j
        }
        return out
    }

    /** Keep the header, the first [maxItems] items and the footers (adds "more below" when items were cut). */
    fun shrink(text: String, maxItems: Int): String {
        val lines = text.split('\n')
        val items = lines.drop(1).filter { it.isNotEmpty() && it[0].isDigit() }
        if (items.size <= maxItems) return text
        val foot = lines.drop(1).filter { it.startsWith("more above") }
        return (listOf(lines[0]) + items.take(maxItems) + "more below: scroll down" + foot).joinToString("\n")
    }

    /** Vision marks: up to 20 nodes with real bounds, clickable first, then unlabeled leaves. */
    fun marks(wins: List<Win>, w: Int, h: Int): List<N> {
        val click = ArrayList<N>(); val other = ArrayList<N>()
        fun walk(n: N) {
            if (!onScreen(n, w, h)) return
            val ok = n.r - n.l >= 24 && n.b - n.t >= 24 && area(n) * 10 < w.toLong() * h * 6
            if (ok && n.click) click += n
            else if (ok && n.kids.isEmpty() && n.text.isNullOrBlank() && n.desc.isNullOrBlank()) other += n
            n.kids.forEach { walk(it) }
        }
        shown(wins, w, h).forEach { walk(it.root) }
        return (click + other).take(20).sortedWith(compareBy({ it.t / 16 }, { it.l }))
    }

    fun grid(cols: Int, rows: Int, a: IntArray): List<IntArray> {
        val cw = (a[2] - a[0]) / cols.toFloat(); val ch = (a[3] - a[1]) / rows.toFloat()
        return (0 until rows).flatMap { r -> (0 until cols).map { c ->
            intArrayOf((a[0] + c * cw).toInt(), (a[1] + r * ch).toInt(), (a[0] + (c + 1) * cw).toInt(), (a[1] + (r + 1) * ch).toInt())
        } }
    }

    /** Launcher label match: exact, starts-with, contains (shortest wins), then label-inside-query (longest wins). */
    fun pickApp(q: String, labels: List<String>): Int {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return -1
        val low = labels.map { it.trim().lowercase() }
        fun best(longest: Boolean = false, ok: (String) -> Boolean): Int? = low.indices.filter { low[it].isNotEmpty() && ok(low[it]) }
            .let { if (longest) it.maxByOrNull { i -> low[i].length } else it.minByOrNull { i -> low[i].length } }
        return best { it == s } ?: best { it.startsWith(s) } ?: best { it.contains(s) }
            ?: best(true) { it.length >= 3 && s.contains(it) } ?: -1
    }

    fun isUrl(s: String) = s.contains('.') && !s.trim().contains(' ')
    fun clean(s: String?, max: Int = 1000): String? {
        if (s == null) return null
        val sb = StringBuilder(); var sp = false
        for (ch in s) if (ch.isWhitespace()) sp = sb.isNotEmpty() else { if (sp) sb.append(' '); sp = false; sb.append(ch) }
        if (sb.isEmpty()) return null
        return if (sb.length > max) sb.substring(0, max - 1) + "…" else sb.toString()
    }

    private fun idName(v: String?, max: Int) = clean(v?.substringAfterLast('/')?.replace('_', ' '), max)
    private fun onScreen(n: N, w: Int, h: Int) = n.visible && n.r > n.l && n.b > n.t && n.r > 0 && n.b > 0 && n.l < w && n.t < h
    private fun area(n: N) = (n.r - n.l).toLong() * (n.b - n.t)
    private fun cy(n: N) = (n.t + n.b) / 2
    private fun yn(b: Boolean) = if (b) "yes" else "no"
    private fun pos(n: N, w: Int, h: Int): String {
        val y = cy(n); val x = (n.l + n.r) / 2
        return (if (y < h / 3) "top" else if (y < 2 * h / 3) "middle" else "bottom") + " " +
            (if (x < w / 3) "left" else if (x < 2 * w / 3) "center" else "right")
    }
}
