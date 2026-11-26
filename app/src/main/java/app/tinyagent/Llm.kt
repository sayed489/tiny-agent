// Llm.kt: the 5 prompt builders, token budgeting, reply parser and plan parser. Transport lives in Api.kt.
package app.tinyagent

/** One plan step. A step with an empty [doing] is the planner's direct answer (no phone action), held in [doneWhen]. */
data class Step(val doing: String, val doneWhen: String, val risky: Boolean)

sealed class Cmd {
    data class Open(val target: String) : Cmd()
    data class Tap(val id: Int) : Cmd()
    data class Type(val id: Int?, val text: String) : Cmd()
    data class Scroll(val dir: String) : Cmd()
    data class Key(val k: String) : Cmd()
    /** Not a phone tool: the model says the current step is finished. The harness verifies it with CHECK before ticking. */
    object Done : Cmd() { override fun toString() = "Done" }
}

interface Brain {
    fun plan(goal: String, facts: List<String>, screen: String, apps: List<String> = emptyList()): List<Step>
    fun act(goal: String, step: Step, k: Int, n: Int, last: String, tried: List<String>, screen: String): String
    fun check(doneWhen: String, screen: String, last: String = "none", doing: String = ""): Boolean
    fun report(goal: String, status: String, screen: String): String
    fun look(step: Step, jpeg: ByteArray): String
}

/** Per-task counters written by Llm and read by Agent for runs.csv. */
class Usage { @Volatile var calls = 0; @Volatile var maxPrompt = 0; @Volatile var vision = 0 }

/** Static system prompts: byte-identical on every call of the same type (prefix-cache friendly). */
object Prompts {
    const val PLAN = "You plan phone tasks. Write 1 to 6 steps, one per line:\n" +
        "<action> => <short result the screen shows>\n" +
        "One step per part of the task. Keep names, numbers and text from the task in the steps.\n" +
        "Use the app the user named, else the best one in Apps; a website only if none fits.\n" +
        "Start a line with ! if it sends, buys or deletes.\n" +
        "If no phone action is needed, write only: answer: <reply>\n" +
        "Example: text Sam \"hi\" on WhatsApp\n" +
        "open WhatsApp => WhatsApp is open\n" +
        "open Sam's chat => Sam's chat is open\n" +
        "!send \"hi\" => \"hi\" is sent"
    const val ACT = "You control an Android phone. Reply with ONE command and nothing else:\n" +
        "open <app name or website>\n" +
        "tap <id>\n" +
        "type <id> <text>\n" +
        "scroll down|up|left|right\n" +
        "key back|home|enter|notifications\n" +
        "done (the current step is already finished)\n" +
        "Use ids from Screen and exact names, numbers and text from Goal. Do only the current step. " +
        "To type, there must be an input; if not, tap a search or text box first. " +
        "If a popup is in the way, close it or answer it safely. Never repeat a command in Tried."
    const val CHECK = "Is the step done? Answer Y or N. Y only if the screen shows its result; names, numbers and times must match."
    const val REPORT = "Tell the user the result of their phone task in one short sentence, using only what the screen shows. If it failed, say what is missing."
    const val LOOK = "This is a phone screenshot with numbered boxes. Reply with ONE command: tap <number>, scroll down, or key back."
}

class Llm(
    private val api: ChatApi, private val planApi: ChatApi = api, private val visionApi: ChatApi? = null,
    private val usage: Usage = Usage(), private val yesNo: Boolean = false, private val imgTokens: Int = 264,
    private val log: (String) -> Unit = {},
) : Brain {
    companion object {
        fun from(c: Config, usage: Usage, log: (String) -> Unit): Llm {
            val main = ChatApi.create(c.main)
            val plan = if (c.planModel.isBlank()) main else ChatApi.create(c.main.copy(model = c.planModel.trim()))
            return Llm(main, plan, c.vision?.let { ChatApi.create(it) }, usage, c.constrainCheck, (c.imgW / 32) * (c.imgH / 32), log)
        }
    }

    override fun plan(goal: String, facts: List<String>, screen: String, apps: List<String>): List<Step> {
        val f = facts.joinToString("; ").ifEmpty { "none" }
        val a = appList(apps, 130).ifEmpty { "unknown" } // installed apps, so the planner picks a real app over a website
        val user = fit(Prompts.PLAN, screen) { "Task: $goal\nFacts: $f\nApps: $a\nScreen now:\n$it" }
        var r = send("PLAN", planApi, Prompts.PLAN, user, 0.3, 240) // fast first; a thinking model may think on the retry
        return parsePlan(r).ifEmpty { r = send("PLAN", planApi, Prompts.PLAN, user, 0.5, 240, think = true); parsePlan(r) }.ifEmpty {
            log("⚠ Could not read the model's plan " + if (r.isBlank()) "(empty reply)" else "“${r.replace('\n', ' ').take(80)}”")
            listOf(Step(goal, "the goal is done", false))
        }
    }

    override fun act(goal: String, step: Step, k: Int, n: Int, last: String, tried: List<String>, screen: String): String {
        val t = tried.joinToString(", ").ifEmpty { "none" }
        val user = fit(Prompts.ACT, screen) { "Goal: $goal\nStep $k of $n: ${step.doing} (done when: ${step.doneWhen})\nLast: $last\nTried: $t\nScreen:\n$it" }
        return send("ACT", api, Prompts.ACT, user, 0.1, 48)
    }

    /** Asks about the whole step (what it does + its result), not only the literal done-when text, plus the last action as evidence. */
    override fun check(doneWhen: String, screen: String, last: String, doing: String): Boolean {
        val user = fit(Prompts.CHECK, screen) {
            (if (doing.isEmpty()) "" else "Step: $doing\n") + "Done when: $doneWhen\nLast action: $last\nScreen:\n$it\nIs this step done?"
        }
        val extra: Map<String, Any?> = if (yesNo) mapOf("structured_outputs" to mapOf("choice" to listOf("Y", "N"))) else emptyMap()
        return yes(send("CHECK", api, Prompts.CHECK, user, 0.1, 16, extra)) // room for "Answer: Y" or "Yes, it is open"
    }

    override fun report(goal: String, status: String, screen: String): String {
        val user = fit(Prompts.REPORT, screen) { "Task: $goal\nStatus: $status\nScreen:\n$it" }
        return firstLine(send("REPORT", api, Prompts.REPORT, user, 0.3, 80))
    }

    override fun look(step: Step, jpeg: ByteArray): String {
        val v = visionApi ?: return ""; usage.vision++
        return send("LOOK", v, Prompts.LOOK, "Step: ${step.doing} (done when: ${step.doneWhen})", 0.1, 24, jpeg = jpeg)
    }

    /** Shrink only the digest (16 -> 10 -> 6 items) until the estimated prompt is <= 430 tokens. */
    private fun fit(sys: String, screen: String, build: (String) -> String): String {
        var u = build(screen)
        for (m in intArrayOf(16, 10, 6)) { if (est(sys, u, 0) <= 430) break; u = build(Screen.shrink(screen, m)) }
        return u
    }
    private fun est(sys: String, user: String, img: Int) = Screen.tokens(sys) + Screen.tokens(user) + 10 + img // +10: chat template

    private fun send(type: String, api: ChatApi, sys: String, user: String, temp: Double, max: Int,
                     extra: Map<String, Any?> = emptyMap(), jpeg: ByteArray? = null, think: Boolean = false): String {
        val e = est(sys, user, if (jpeg != null) imgTokens else 0)
        val r = api.chat(listOf(Msg("system", sys), Msg("user", user, jpeg)), temp, max, extra, think)
        val p = if (r.promptTokens >= 0) r.promptTokens else e
        synchronized(usage) { usage.calls++; if (p > usage.maxPrompt) usage.maxPrompt = p } // CHECK and ACT can run in parallel
        log("$type est=$e prompt=${r.promptTokens} out=${r.completionTokens} ${r.ms}ms: ${r.text.replace('\n', ' ').take(120)}")
        if (p > 450) log("WARN $type prompt is $p tokens (limit 450)")
        return r.text
    }
}

private val VERBS = setOf("open", "tap", "type", "scroll", "key", "done")
private val ALIAS = mapOf("click" to "tap", "press" to "key", "swipe" to "scroll", "launch" to "open", "input" to "type", "finished" to "done", "complete" to "done", "completed" to "done")
private val DIRS = setOf("down", "up", "left", "right")
private val KEYS = setOf("back", "home", "enter", "notifications")
/** Drop <think>...</think> (and a dangling </think>), return the first non-empty line. */
fun firstLine(reply: String): String {
    var s = reply
    while (true) {
        val a = s.indexOf("<think>"); if (a < 0) break
        val b = s.indexOf("</think>", a)
        s = if (b < 0) s.substring(0, a) else s.substring(0, a) + s.substring(b + 8)
    }
    val e = s.lastIndexOf("</think>")
    if (e >= 0) s = s.substring(e + 8)
    return s.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
}

/** Launcher labels for PLAN, shortest first (core apps have short names) until [maxTokens], then alphabetical. */
fun appList(apps: List<String>, maxTokens: Int): String {
    val out = ArrayList<String>(); var used = 0
    for (a in apps.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(compareBy({ it.length }, { it }))) {
        val t = Screen.tokens("$a, "); if (used + t > maxTokens) break; used += t; out += a
    }
    return out.sorted().joinToString(", ")
}

/** The first yes/no word anywhere in the reply ("Y", "Yes.", "Answer: Y", "**N**", "y\n"). No such word counts as no. */
fun yes(reply: String): Boolean {
    val s = reply.substringAfterLast("</think>").let { if (it.contains("<think>")) it.substringBefore("<think>") else it }
    return s.lowercase().split(' ', '\n', ':', ',', '.', '*', '"', '\'', '(', ')', '!', '-', '`').firstOrNull { it in YES || it in NO }?.let { it in YES } == true
}
private val YES = setOf("y", "yes", "true", "done"); private val NO = setOf("n", "no", "false", "not")

/** Strip backticks, list markers, numbering, a leading "Action:"/"Command:" and surrounding quotes. */
fun cleanLine(line: String): String {
    var s = line.replace("`", "").trim().trimStart('-', '*', '•', '>', ' ')
    var i = 0
    while (i < s.length && s[i].isDigit()) i++
    if (i > 0 && i < s.length && (s[i] == '.' || s[i] == ')') && (i + 1 == s.length || s[i + 1] == ' ')) s = s.substring(i + 1).trim()
    for (p in listOf("action:", "command:")) if (s.lowercase().startsWith(p)) s = s.substring(p.length).trim()
    if (s.lowercase().startsWith("step ")) s.substring(5).trimStart().let { t -> t.takeWhile { it.isDigit() }.let { d ->
        if (d.isNotEmpty() && t.length > d.length && t[d.length] in ":.)") s = t.substring(d.length + 1).trim() } }
    return s.replace("**", "").trim('"', '\'', '“', '”', ' ')
}

private fun unq(s: String) = s.trim().trim('"', '\'', '“', '”', '`').trim()
private fun word(s: String) = unq(s).trim('(', ')', '[', ']').trimEnd('.', '!', ',', ';').lowercase()
private fun valid(id: Int, items: List<Item>, maxMark: Int) = if (maxMark > 0) id in 1..maxMark else items.any { it.id == id }

fun parse(reply: String, items: List<Item>, maxMark: Int = 0): Pair<Cmd?, String?> {
    val line = cleanLine(firstLine(reply))
    if (line.isEmpty()) return null to "empty reply"
    // Tiny models write "I will tap 3" or "tap(3)": take the verb from the first 3 words, the rest is its argument.
    val toks = line.split(' ').filter { it.isNotEmpty() }
    fun verbOf(t: String) = t.takeWhile { it.isLetter() }.lowercase().let { ALIAS[it] ?: it }
    // A bare number ("3", "<3>", "[3]") means tap it: tiny models and vision models often answer that way.
    line.trim('<', '>', '[', ']', '(', ')', '#', '.', ' ').toIntOrNull()?.let { return if (valid(it, items, maxMark)) Cmd.Tap(it) to null else null to "no id $it on Screen" }
    val vi = toks.take(3).indexOfFirst { verbOf(it) in VERBS }
    if (vi < 0) return null to "unknown command ${verbOf(toks[0])}"
    val verb = verbOf(toks[vi])
    // "open Clock tap Alarms scroll down": keep only the first command (type text is kept whole).
    val args = toks.drop(vi + 1).let { a -> if (verb == "type") a else a.take(a.indexOfFirst { verbOf(it) in VERBS }.let { i -> if (i < 0) a.size else i }) }
    val rest = (toks[vi].dropWhile { it.isLetter() } + " " + args.joinToString(" ")).trim(':', ' ')
    fun idOf(tok: String): Int? = tok.trim('[', ']', '(', ')', '#', '.', ',').toIntOrNull()
    fun valid(id: Int) = valid(id, items, maxMark)
    /** Exact unique label; with [loose], also "tap on the X button" (longest label inside) or "tap Voice" (unique prefix). */
    fun byLabel(s: String, pool: List<Item>, loose: Boolean = false): Int? {
        val q = unq(s).lowercase()
        pool.filter { it.label.equals(q, ignoreCase = true) }.singleOrNull()?.let { return it.id }
        if (!loose || q.length < 3) return null
        val inside = pool.filter { it.label.length >= 3 && q.contains(it.label.lowercase()) }
        val top = inside.filter { it.label.length == inside.maxOfOrNull { i -> i.label.length } }
        if (top.isNotEmpty()) return top.singleOrNull()?.id
        return pool.filter { it.label.lowercase().startsWith(q) }.singleOrNull()?.id
    }
    return when (verb) {
        "done" -> Cmd.Done to null
        "open" -> unq(rest).trimEnd('.').let { if (it.isEmpty()) null to "open needs an app or website" else Cmd.Open(it) to null }
        "scroll" -> word(rest).let { if (it in DIRS) Cmd.Scroll(it) to null else null to "scroll needs down, up, left or right" }
        "key" -> word(rest).let { if (it in KEYS) Cmd.Key(it) to null else null to "key needs back, home, enter or notifications" }
        "tap" -> when (val id = rest.split(' ').filter { it.isNotEmpty() }.let { t -> (if (t.firstOrNull()?.lowercase() in setOf("id", "number", "no", "item")) t.getOrNull(1) else t.firstOrNull())?.let(::idOf) }
            ?: (if (maxMark == 0) byLabel(rest, items, loose = true) else null)) {
            null -> null to "tap needs an id from Screen"
            else -> if (valid(id)) Cmd.Tap(id) to null else null to "no id $id on Screen"
        }
        else -> { // type
            val toks = rest.split(' ').filter { it.isNotEmpty() }
            var id = toks.firstOrNull()?.let(::idOf)
            var text = if (id != null) toks.drop(1).joinToString(" ") else rest
            if (id == null) for (j in toks.size - 1 downTo 1) {
                val hit = byLabel(toks.take(j).joinToString(" "), items.filter { it.kind == "input" }) ?: continue
                id = hit; text = toks.drop(j).joinToString(" "); break
            }
            text = unq(text)
            when {
                id != null && !valid(id) -> null to "no id $id on Screen"
                text.isEmpty() || text.length > 200 -> null to "type needs text of 1 to 200 characters"
                else -> Cmd.Type(id, text) to null
            }
        }
    }
}

private val ARROWS = listOf("=>", "->", "→", "⇒")
/**
 * Plan lines are "<action> => <done when>" (also ->, →, ⇒), with numbering and bullets stripped and "!" marking risky.
 * A list without arrows still becomes steps. A lone "answer: …" line means no phone action is needed. At most 6 steps.
 */
fun parsePlan(reply: String): List<Step> {
    val raw = reply.substringAfterLast("</think>").lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun step(line: String, done: String?): Step? {
        val a = line.trim().trimStart('!').trim()
        return if (a.isEmpty()) null else Step(a, (done ?: "$a is done").trim().trimEnd('.'), line.trim().startsWith("!"))
    }
    val arrowed = raw.map(::cleanLine).mapNotNull { l ->
        val arrow = ARROWS.map { l.indexOf(it) to it.length }.filter { it.first > 0 }.minByOrNull { it.first } ?: return@mapNotNull null
        l.substring(arrow.first + arrow.second).trim().takeIf { it.isNotEmpty() }?.let { step(l.substring(0, arrow.first), it) }
    }
    if (arrowed.isNotEmpty()) return arrowed.take(6)
    raw.map(::cleanLine).firstOrNull { it.startsWith("answer:", ignoreCase = true) }
        ?.let { a -> return listOf(Step("", a.substringAfter(':').trim().ifEmpty { "OK" }, false)) }
    val listed = raw.filter { it[0].isDigit() || it[0] in "-*•" || it.startsWith("step ", ignoreCase = true) }
    if (listed.isNotEmpty()) return listed.mapNotNull { step(cleanLine(it), null) }.take(6)
    val plain = raw.map(::cleanLine).filter { !it.endsWith(":") }
    // No markers: one short line ending in ? or ! is a chat reply; two or more short lines are steps; anything else is unreadable.
    if (plain.size == 1 && plain[0].contains(' ') && (plain[0].endsWith("?") || plain[0].endsWith("!"))) return listOf(Step("", plain[0], false))
    return if (plain.size >= 2 && plain.all { it.length <= 80 && !it.endsWith("?") }) plain.mapNotNull { step(it, null) }.take(6) else emptyList()
}
