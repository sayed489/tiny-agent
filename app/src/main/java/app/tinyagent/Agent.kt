// Agent.kt: the PLAN -> ACT -> CHECK loop, budgets, loop detection, vision trigger, replans, handoffs, run log.
package app.tinyagent

interface Ui { fun log(s: String); fun status(s: String); fun ask(q: String): Boolean } // ask blocks the worker thread

data class Config(
    val main: Endpoint, val planModel: String = "", val vision: Endpoint? = null, val imgW: Int = 352, val imgH: Int = 768,
    val confirmRisky: Boolean = true, val confirmEvery: Boolean = false, val constrainCheck: Boolean = false,
)

/** Last 200 log lines, shared between the service (writer) and the activity (reader). */
object RunLog {
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null
    fun add(s: String) {
        synchronized(this) { lines.addLast(s); while (lines.size > 200) lines.removeFirst() }
        listener?.invoke()
    }
    fun text(): String = synchronized(this) { lines.joinToString("\n") }
    fun clear() { synchronized(this) { lines.clear() }; listener?.invoke() }
}

class Halt(val status: String, why: String) : Exception(why)

class Agent(
    private val dev: Device, private val brain: Brain, private val ui: Ui, private val cfg: Config,
    private val usage: Usage = Usage(), private val csv: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object { const val MAX_ACTIONS = 40; const val MAX_MS = 6 * 60_000L; const val STEP_ACTIONS = 8 }
    private class Pick(val cmd: Cmd, val node: N?, val xy: IntArray?)

    @Volatile private var stopped = false
    fun stop() { stopped = true }

    private val facts = ArrayList<String>(); private val ran = HashSet<String>() // ran = (screen hash, command) pairs
    private var actions = 0; private var replans = 0; private var t0 = 0L; private var curK = 1
    private var vision = cfg.vision != null; private var w = 1080; private var h = 2400

    fun run(goal: String): String {
        t0 = now()
        var status = "stopped"; var msg: String
        try {
            if (dev.locked()) throw Halt("locked", "Unlock the phone first")
            dev.size().let { w = it.first; h = it.second }
            ui.status("Planning…") // the "Goal:" log line is written by the chat screen
            dev.key("home"); dev.settle()
            var plan = newPlan(goal)
            var k = 0; var fresh = false
            while (k < plan.size) {
                val st = plan[k]; curK = k + 1
                ui.status("Step ${k + 1}/${plan.size}: ${st.doing}")
                if ((k > 0 || fresh) && call { brain.check(st.doneWhen, read()) }) { ui.log("✓ ${k + 1} already done"); fresh = false; k++; continue }
                fresh = false
                if (((st.risky && cfg.confirmRisky) || cfg.confirmEvery) && !ui.ask("About to: ${st.doing}"))
                    throw Halt("stopped", "You declined: ${st.doing}")
                if (step(goal, st, k + 1, plan.size)) { ui.log("✓ ${k + 1} ${st.doing}"); k++; continue }
                if (++replans > 2) throw Halt("stuck at step ${k + 1}", "Stuck at step ${k + 1}: ${st.doing}")
                ui.status("Replanning…")
                plan = plan.take(k) + newPlan(goal) // keep ticked steps, replace the rest
                fresh = true
            }
            status = "done"
            msg = report(goal, status).ifEmpty { "Done" }
        } catch (e: Halt) {
            status = e.status
            msg = if (status.startsWith("stuck")) report(goal, status).ifEmpty { e.message ?: status } else e.message ?: status
        } catch (e: InterruptedException) {
            status = "stopped"; msg = "Stopped"
        }
        ui.log("Result ($status): $msg"); ui.status(msg)
        val secs = "${(now() - t0) / 1000}"
        csv(listOf(goal, cfg.main.model, "$actions", "${usage.calls}", "${usage.maxPrompt}", "${usage.vision}", "$replans", secs, status)
            .joinToString(",") { if (it.contains(',') || it.contains('"')) "\"" + it.replace("\"", "\"\"") + "\"" else it })
        return msg
    }

    private fun newPlan(goal: String): List<Step> {
        val p = call { brain.plan(goal, facts.toList(), Screen.digest(dev.windows(), Mode.READ, 120, w, h).text) }
        p.forEachIndexed { i, s -> ui.log("Plan ${i + 1}: ${if (s.risky) "!" else ""}${s.doing} => ${s.doneWhen}") }
        return p
    }

    /** REPORT skips the action/time budget (it runs after a budget stop) but still honours Stop. */
    private fun report(goal: String, status: String): String = if (stopped) "Stopped" else try {
        brain.report(goal, status, read())
    } catch (e: Exception) { "" }

    /** Runs one step. true = step done, false = replan needed. */
    private fun step(goal: String, st: Step, k: Int, n: Int): Boolean {
        var wins = dev.windows(); var d = act(wins)
        var noProg = 0; var acts = 0; var last = "none"
        val tried = ArrayDeque<String>(); val seen = hashSetOf(d.hash)
        while (true) {
            budget()
            if (noProg >= 4 || acts >= STEP_ACTIONS) { fact("stuck at: ${st.doing}"); return false }
            val useV = vision && (d.actionable < 3 || d.unlabeled * 2 > d.actionable || noProg >= 2)
            val pick = if (useV) look(st, wins) else null
            val cmd = pick?.cmd ?: run {
                var (c, err) = parse(call { brain.act(goal, st, k, n, last, tried.toList(), d.text) }, d.items)
                if (c == null) {
                    ui.log("bad reply: $err")
                    val r = parse(call { brain.act(goal, st, k, n, "ERR $err. Reply with one command.", tried.toList(), d.text) }, d.items)
                    c = r.first; err = r.second
                }
                if (c == null) { last = "ERR $err"; noProg++ }
                c
            } ?: continue
            if (cmd is Cmd.Type && target(cmd, d)?.node?.password == true) {
                if (!ui.ask("Type your password, then tap Continue")) throw Halt("stopped", "Stopped")
                dev.settle(); wins = dev.windows(); d = act(wins); last = "user typed the password"; continue
            }
            val label = label(cmd, d, pick)
            if (!ran.add("${d.hash}|$label")) { last = "ERR already tried on this screen"; noProg++; continue }
            val res = exec(cmd, d, pick)
            actions++; acts++
            ui.log("→ $label: ${res.msg}")
            dev.settle()
            if (res.missingApp != null) { fact("${res.missingApp} is not installed; use its website"); return false }
            val wins2 = dev.windows(); val d2 = act(wins2)
            when {
                !res.ok -> { last = "$label failed: ${res.msg}"; noProg++ }
                d2.hash == d.hash -> { last = "$label -> screen unchanged"; noProg++ }
                d2.hash in seen -> { last = "$label -> back to an earlier screen"; noProg++ }
                else -> {
                    last = "$label -> screen changed"; seen += d2.hash
                    if (call { brain.check(st.doneWhen, Screen.digest(wins2, Mode.READ, 200, w, h).text) }) return true
                }
            }
            tried.addLast(label); if (tried.size > 3) tried.removeFirst()
            wins = wins2; d = d2
        }
    }

    private fun look(st: Step, wins: List<Win>): Pick? {
        val marks = Screen.marks(wins, w, h)
        val grid = marks.size < 3
        val boxes = if (grid) Screen.grid(4, 6, intArrayOf(0, 0, w, h)) else marks.map { intArrayOf(it.l, it.t, it.r, it.b) }
        val jpg = dev.shot(boxes, cfg.imgW, cfg.imgH)
        if (jpg == null) { fact("screen is protected"); vision = false; ui.log("Screen is protected: vision off"); return null }
        val (c, err) = parse(call { brain.look(st, jpg) }, emptyList(), boxes.size)
        if (c == null) { ui.log("vision: $err"); return null }
        if (c !is Cmd.Tap) return Pick(c, null, null)
        val b = boxes[c.id - 1]
        if (!grid) return Pick(c, marks[c.id - 1], null)
        val m = 40
        val crop = intArrayOf(maxOf(0, b[0] - m), maxOf(0, b[1] - m), minOf(w, b[2] + m), minOf(h, b[3] + m))
        val sub = Screen.grid(3, 3, crop)
        val jpg2 = dev.shot(sub, cfg.imgW, cfg.imgH, crop) ?: return Pick(c, null, mid(b))
        val c2 = parse(call { brain.look(st, jpg2) }, emptyList(), sub.size).first
        return Pick(c, null, mid(if (c2 is Cmd.Tap) sub[c2.id - 1] else b))
    }

    private fun exec(c: Cmd, d: Digest, p: Pick?): Res = when (c) {
        is Cmd.Open -> dev.open(c.target)
        is Cmd.Tap -> when {
            p?.xy != null -> dev.tapXY(p.xy[0], p.xy[1])
            p?.node != null -> dev.tap(p.node)
            else -> d.items.first { it.id == c.id }.node.let { dev.tap(it) }
        }
        is Cmd.Type -> dev.type(c.id?.let { id -> d.items.firstOrNull { it.id == id }?.node }, c.text)
        is Cmd.Scroll -> dev.scroll(c.dir, null)
        is Cmd.Key -> dev.key(c.k)
    }

    /** Commands in label form: ids are renumbered on every screen, labels are not. */
    private fun label(c: Cmd, d: Digest, p: Pick?): String = when (c) {
        is Cmd.Open -> "open ${c.target}"
        is Cmd.Tap -> if (p != null) "tap box ${c.id} (vision)" else "tap \"${d.items.first { it.id == c.id }.label}\""
        is Cmd.Type -> (target(c, d)?.let { "type \"${it.label}\" " } ?: "type ") + "\"${c.text}\""
        is Cmd.Scroll -> "scroll ${c.dir}"
        is Cmd.Key -> "key ${c.k}"
    }

    private fun target(c: Cmd.Type, d: Digest): Item? = if (c.id != null) d.items.firstOrNull { it.id == c.id }
        else d.items.filter { it.kind == "input" }.let { ins -> ins.firstOrNull { it.node.focused } ?: ins.singleOrNull() }

    private fun budget() {
        if (stopped || Thread.currentThread().isInterrupted) throw Halt("stopped", "Stopped")
        if (actions >= MAX_ACTIONS) throw Halt("stuck at step $curK", "Action limit ($MAX_ACTIONS) reached")
        if (now() - t0 > MAX_MS) throw Halt("stuck at step $curK", "Time limit (6 min) reached")
    }

    /** Every model call goes through here: budget check first, then a user-approved retry on API errors. */
    private fun <T> call(f: () -> T): T {
        while (true) {
            budget()
            try { return f() } catch (e: ApiError) {
                ui.log("API error: ${e.message}")
                if (!ui.ask("Model API error: ${e.message}. Retry?")) throw Halt("stopped", "API error: ${e.message}")
            }
        }
    }

    private fun fact(s: String) { if (s !in facts) facts += s; while (facts.size > 3) facts.removeAt(0) }
    private fun read() = Screen.digest(dev.windows(), Mode.READ, 200, w, h).text
    private fun act(wins: List<Win>) = Screen.digest(wins, Mode.ACT, 200, w, h)
    private fun mid(b: IntArray) = intArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
}
