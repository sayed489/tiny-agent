// Llm.kt: the 5 prompt builders, token budgeting, reply parser and plan parser. Transport lives in Api.kt.
package app.tinyagent

data class Step(val doing: String, val doneWhen: String, val risky: Boolean)

sealed class Cmd {
    data class Open(val target: String) : Cmd()
    data class Tap(val id: Int) : Cmd()
    data class Type(val id: Int?, val text: String) : Cmd()
    data class Scroll(val dir: String) : Cmd()
    data class Key(val k: String) : Cmd()
}

interface Brain {
    fun plan(goal: String, facts: List<String>, screen: String): List<Step>
    fun act(goal: String, step: Step, k: Int, n: Int, last: String, tried: List<String>, screen: String): String
    fun check(doneWhen: String, screen: String): Boolean
    fun report(goal: String, status: String, screen: String): String
    fun look(step: Step, jpeg: ByteArray): String
}

/** Per-task counters written by Llm and read by Agent for runs.csv. */
class Usage { @Volatile var calls = 0; @Volatile var maxPrompt = 0; @Volatile var vision = 0 }

/** Static system prompts: byte-identical on every call of the same type (prefix-cache friendly). */
object Prompts {
    const val PLAN = "You plan phone tasks. Write 1 to 6 steps, one per line:\n" +
        "<action> => <what the screen shows when it is done>\n" +
        "Describe results, not exact buttons. Use the app the user named. If no app is named or it is missing, use a website.\n" +
        "Start a line with ! if it sends, posts, buys, books or deletes something.\n" +
        "The last step must show the result on screen.\n" +
        "Example task: weather in Paris\n" +
        "open google.com => Google is open\n" +
        "search weather Paris => weather results are shown\n" +
        "read the temperature => Paris temperature is visible"
    const val ACT = "You control an Android phone. Reply with ONE command and nothing else:\n" +
        "open <app name or website>\n" +
        "tap <id>\n" +
        "type <id> <text>\n" +
        "scroll down|up|left|right\n" +
        "key back|home|enter|notifications\n" +
        "Use ids from Screen. Do only the current step. If a popup is in the way, close it or answer it safely. Never repeat a command in Tried."
    const val CHECK = "Answer with one letter: Y or N."
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

    override fun plan(goal: String, facts: List<String>, screen: String): List<Step> {
        val f = facts.joinToString("; ").ifEmpty { "none" }
        val user = fit(Prompts.PLAN, screen) { "Task: $goal\nFacts: $f\nScreen now:\n$it" }
        return parsePlan(send("PLAN", planApi, Prompts.PLAN, user, 0.3, 150))
            .ifEmpty { parsePlan(send("PLAN", planApi, Prompts.PLAN, user, 0.5, 150)) }
            .ifEmpty { listOf(Step(goal, "the goal is done", false)) }
    }

    override fun act(goal: String, step: Step, k: Int, n: Int, last: String, tried: List<String>, screen: String): String {
        val t = tried.joinToString(", ").ifEmpty { "none" }
        val user = fit(Prompts.ACT, screen) { "Goal: $goal\nStep $k of $n: ${step.doing} (done when: ${step.doneWhen})\nLast: $last\nTried: $t\nScreen:\n$it" }
        return send("ACT", api, Prompts.ACT, user, 0.1, 24)
    }

    override fun check(doneWhen: String, screen: String): Boolean {
        val user = fit(Prompts.CHECK, screen) { "Screen:\n$it\nDoes the screen show this: $doneWhen?" }
        val extra: Map<String, Any?> = if (yesNo) mapOf("structured_outputs" to mapOf("choice" to listOf("Y", "N"))) else emptyMap()
        return cleanLine(firstLine(send("CHECK", api, Prompts.CHECK, user, 0.1, 2, extra))).startsWith("y", ignoreCase = true)
    }

    override fun report(goal: String, status: String, screen: String): String {
        val user = fit(Prompts.REPORT, screen) { "Task: $goal\nStatus: $status\nScreen:\n$it" }
        return firstLine(send("REPORT", api, Prompts.REPORT, user, 0.3, 60))
    }

    override fun look(step: Step, jpeg: ByteArray): String {
        val v = visionApi ?: return ""; usage.vision++
        return send("LOOK", v, Prompts.LOOK, "Step: ${step.doing} (done when: ${step.doneWhen})", 0.1, 12, jpeg = jpeg)
    }

    /** Shrink only the digest (16 -> 10 -> 6 items) until the estimated prompt is <= 430 tokens. */
    private fun fit(sys: String, screen: String, build: (String) -> String): String {
        var u = build(screen)
        for (m in intArrayOf(16, 10, 6)) { if (est(sys, u, 0) <= 430) break; u = build(Screen.shrink(screen, m)) }
        return u
    }
    private fun est(sys: String, user: String, img: Int) = Screen.tokens(sys) + Screen.tokens(user) + 10 + img // +10: chat template

    private fun send(type: String, api: ChatApi, sys: String, user: String, temp: Double, max: Int,
                     extra: Map<String, Any?> = emptyMap(), jpeg: ByteArray? = null): String {
        val e = est(sys, user, if (jpeg != null) imgTokens else 0)
        val r = api.chat(listOf(Msg("system", sys), Msg("user", user, jpeg)), temp, max, extra)
        usage.calls++; val p = if (r.promptTokens >= 0) r.promptTokens else e
        if (p > usage.maxPrompt) usage.maxPrompt = p
        log("$type est=$e prompt=${r.promptTokens} out=${r.completionTokens} ${r.ms}ms: ${r.text.replace('\n', ' ').take(120)}")
        if (p > 450) log("WARN $type prompt is $p tokens (limit 450)")
        return r.text
    }
}

private val VERBS = setOf("open", "tap", "type", "scroll", "key")
private val ALIAS = mapOf("click" to "tap", "press" to "key", "swipe" to "scroll", "launch" to "open", "input" to "type")
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

/** Strip backticks, list markers, numbering, a leading "Action:"/"Command:" and surrounding quotes. */
fun cleanLine(line: String): String {
    var s = line.replace("`", "").trim().trimStart('-', '*', '•', '>', ' ')
    var i = 0
    while (i < s.length && s[i].isDigit()) i++
    if (i > 0 && i < s.length && (s[i] == '.' || s[i] == ')') && (i + 1 == s.length || s[i + 1] == ' ')) s = s.substring(i + 1).trim()
    for (p in listOf("action:", "command:")) if (s.lowercase().startsWith(p)) s = s.substring(p.length).trim()
    return s.trim('"', '\'', '“', '”', ' ')
}

private fun unq(s: String) = s.trim().trim('"', '\'', '“', '”', '`').trim()
private fun word(s: String) = unq(s).trimEnd('.', '!', ',', ';').lowercase()

fun parse(reply: String, items: List<Item>, maxMark: Int = 0): Pair<Cmd?, String?> {
    val line = cleanLine(firstLine(reply))
    if (line.isEmpty()) return null to "empty reply"
    val sp = line.indexOf(' ')
    val v0 = (if (sp < 0) line else line.substring(0, sp)).lowercase().trimEnd(':')
    val verb = ALIAS[v0] ?: v0
    val rest = if (sp < 0) "" else line.substring(sp + 1).trim()
    if (verb !in VERBS) return null to "unknown command $v0"
    fun idOf(tok: String): Int? = tok.trim('[', ']', '(', ')', '#', '.', ',').toIntOrNull()
    fun valid(id: Int) = if (maxMark > 0) id in 1..maxMark else items.any { it.id == id }
    fun byLabel(s: String, pool: List<Item>): Int? =
        pool.filter { it.label.equals(unq(s), ignoreCase = true) }.singleOrNull()?.id
    return when (verb) {
        "open" -> unq(rest).trimEnd('.').let { if (it.isEmpty()) null to "open needs an app or website" else Cmd.Open(it) to null }
        "scroll" -> word(rest).let { if (it in DIRS) Cmd.Scroll(it) to null else null to "scroll needs down, up, left or right" }
        "key" -> word(rest).let { if (it in KEYS) Cmd.Key(it) to null else null to "key needs back, home, enter or notifications" }
        "tap" -> when (val id = idOf(rest.substringBefore(' ')) ?: (if (maxMark == 0) byLabel(rest, items) else null)) {
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

/** Keep lines with "=>", strip numbering/bullets, "!" marks risky, at most 6 steps. */
fun parsePlan(reply: String): List<Step> {
    var s = reply
    val e = s.lastIndexOf("</think>")
    if (e >= 0) s = s.substring(e + 8)
    return s.lines().mapNotNull { raw ->
        val l = cleanLine(raw)
        val i = l.indexOf("=>")
        if (i <= 0) return@mapNotNull null
        var a = l.substring(0, i).trim()
        val risky = a.startsWith("!")
        a = a.trimStart('!').trim()
        val b = l.substring(i + 2).trim().trimEnd('.')
        if (a.isEmpty() || b.isEmpty()) null else Step(a, b, risky)
    }.take(6)
}
