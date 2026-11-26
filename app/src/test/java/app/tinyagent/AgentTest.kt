package app.tinyagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone made of named screens. [on] receives each action as text and may switch [screen]. */
class FakeDevice(var screen: String, private val screens: Map<String, List<Win>>, private val on: FakeDevice.(String) -> Res = { Res(true, "ok") }) : Device {
    val actions = ArrayList<String>()
    var locked = false
    var shotResult: ByteArray? = byteArrayOf(9)
    val shots = ArrayList<Pair<Int, IntArray?>>()
    private fun act(a: String): Res { actions += a; return on(a) }
    override fun windows() = screens.getValue(screen)
    override fun size() = W to H
    override fun locked() = locked
    override fun open(target: String) = act("open $target")
    override fun tap(n: N) = act("tap ${n.text ?: n.desc ?: n.hint ?: "?"}")
    override fun tapXY(x: Int, y: Int) = act("tapXY $x,$y")
    override fun type(n: N?, text: String) = act("type ${n?.hint ?: "-"} $text")
    override fun scroll(dir: String, n: N?) = act("scroll $dir")
    var homes = 0
    override fun key(k: String) = if (k == "home" && actions.isEmpty()) { homes++; Res(true, "home") } else act("key $k")
    override fun settle(maxMs: Long) {}
    override fun shot(boxes: List<IntArray>, outW: Int, outH: Int, crop: IntArray?): ByteArray? { shots += boxes.size to crop; return shotResult }
}

/** Scripted brain: plans from a queue, ACT/LOOK replies from functions, CHECK by substring of the READ digest. */
class FakeBrain(
    private val plans: ArrayDeque<List<Step>>,
    private val acts: (screen: String, last: String) -> String,
    private val looks: ArrayDeque<String> = ArrayDeque(),
) : Brain {
    val planFacts = ArrayList<List<String>>()
    val actLasts = ArrayList<String>()
    var checks = 0; var reports = 0
    private var lastPlan = emptyList<Step>()
    override fun plan(goal: String, facts: List<String>, screen: String): List<Step> { planFacts += facts; lastPlan = plans.removeFirstOrNull() ?: lastPlan; return lastPlan }
    override fun act(goal: String, step: Step, k: Int, n: Int, last: String, tried: List<String>, screen: String): String { actLasts += last; return acts(screen, last) }
    override fun check(doneWhen: String, screen: String): Boolean { checks++; return screen.contains(doneWhen) }
    override fun report(goal: String, status: String, screen: String): String { reports++; return "report: $status" }
    override fun look(step: Step, jpeg: ByteArray): String = looks.removeFirstOrNull() ?: "key back"
}

class FakeUi(vararg answers: Boolean) : Ui {
    val logs = ArrayList<String>(); val asked = ArrayList<String>()
    private val answers = ArrayDeque(answers.toList())
    override fun log(s: String) { logs += s }
    override fun status(s: String) {}
    override fun ask(q: String): Boolean { asked += q; return answers.removeFirstOrNull() ?: true }
}

class AgentTest {
    private val cfg = Config(Endpoint("http://x", "", "tiny"))
    private val home = listOf(app(textNode("Launcher home", 300), button("Clock", 800), label = "Launcher"))
    private val google = listOf(app(input("Search", 300), textNode("Google", 150), label = "Chrome"))
    private val typed = listOf(app(input("Search", 300, text = "weather Tokyo", focused = true), label = "Chrome"))
    private val results = listOf(app(input("Search", 300, text = "weather Tokyo"), textNode("Tokyo 18°C sunny", 600), label = "Chrome"))

    private fun weatherDevice() = FakeDevice("home", mapOf("home" to home, "google" to google, "typed" to typed, "results" to results)) { a ->
        when {
            a == "open google.com" -> { screen = "google"; Res(true, "opened") }
            a.startsWith("type Search") -> { screen = "typed"; Res(true, "typed; key enter submits") }
            a == "key enter" && screen == "typed" -> { screen = "results"; Res(true, "pressed enter") }
            else -> Res(true, "ok")
        }
    }

    @Test fun directAnswerNeedsNoPhone() {
        val dev = weatherDevice()
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("", "Hi! What should I do on your phone?", false)))), { _, _ -> "tap 1" })
        val rows = ArrayList<String>()
        assertEquals("Hi! What should I do on your phone?", Agent(dev, brain, FakeUi(), cfg, csv = { rows += it }).run("Hello"))
        assertTrue(dev.actions.isEmpty())
        assertEquals(0, dev.homes) // didn't even leave the chat
        assertEquals(0, brain.reports)
        assertTrue(rows.single().endsWith(",answered"))
    }

    @Test fun replanCannotBailOutWithAnAnswer() {
        val dev = FakeDevice("home", mapOf("home" to home))
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open the clock", "Alarm", false)), listOf(Step("", "I can't", false)))), { _, _ -> "tap 2" })
        val ui = FakeUi()
        val msg = Agent(dev, brain, ui, cfg).run("set an alarm")
        assertTrue(msg, msg.startsWith("report: stuck at step 1"))
        assertTrue(ui.logs.contains("↻ Replanning (1/2)"))
    }

    @Test fun emptyModelRepliesAreShownToTheUser() {
        val dev = FakeDevice("home", mapOf("home" to home))
        val ui = FakeUi()
        Agent(dev, FakeBrain(ArrayDeque(listOf(listOf(Step("open the clock", "Alarm", false)))), { _, _ -> "" }), ui, cfg).run("alarm")
        assertTrue(ui.logs.contains("⚠ Model reply not understood: (empty)"))
    }

    @Test fun happyPathPlanActCheck() {
        val dev = weatherDevice()
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open google.com", "Google", false), Step("search weather Tokyo", "Tokyo 18°C", false)))), { s, _ ->
            when { s.contains("Launcher") -> "open google.com"; s.contains("= \"weather Tokyo\"") -> "key enter"; else -> "type Search weather Tokyo" }
        })
        val rows = ArrayList<String>()
        val usage = Usage().apply { calls = 7; maxPrompt = 321 }
        val msg = Agent(dev, brain, FakeUi(), cfg, usage, { rows += it }).run("weather in Tokyo")
        assertEquals("report: done", msg)
        assertEquals(listOf("open google.com", "type Search weather Tokyo", "key enter"), dev.actions)
        assertEquals(1, brain.reports)
        val row = rows.single().split(',')
        assertEquals(listOf("weather in Tokyo", "tiny", "3", "7", "321", "0", "0"), row.take(7))
        assertEquals("done", row.last())
    }

    @Test fun lastAndTriedUseLabelForm() {
        val dev = weatherDevice()
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open google.com", "Google", false), Step("search", "Tokyo 18°C", false)))), { s, _ ->
            when { s.contains("Launcher") -> "open google.com"; s.contains("= \"weather Tokyo\"") -> "key enter"; else -> "type Search weather Tokyo" }
        })
        Agent(dev, brain, FakeUi(), cfg).run("weather in Tokyo")
        assertTrue(brain.actLasts.contains("type \"Search\" \"weather Tokyo\" -> screen changed"))
    }

    @Test fun unchangedScreenNeverCallsCheckAndRepeatsAreBlocked() {
        val dev = FakeDevice("home", mapOf("home" to home))
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open the clock", "Alarm", false)))), { _, _ -> "tap 2" })
        val msg = Agent(dev, brain, FakeUi(), cfg).run("set an alarm")
        assertEquals(listOf("tap Clock"), dev.actions) // the same (screen, command) never runs twice in a task
        assertEquals(3, brain.planFacts.size) // first plan + 2 replans, then stop
        assertTrue(brain.planFacts[1].contains("stuck at: open the clock"))
        assertTrue(brain.actLasts.contains("tap \"Clock\" -> screen unchanged"))
        assertTrue(brain.actLasts.contains("ERR already tried on this screen"))
        assertEquals(2, brain.checks) // only the pre-checks after each replan; unchanged screens never trigger CHECK
        assertTrue(msg, msg.startsWith("report: stuck at step 1"))
    }

    @Test fun missingAppTriggersImmediateReplanWithFact() {
        val dev = FakeDevice("home", mapOf("home" to home, "google" to google)) { a ->
            if (a == "open WhatsApp") Res(false, "no app named WhatsApp", missingApp = "WhatsApp") else { screen = "google"; Res(true, "ok") }
        }
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open WhatsApp", "WhatsApp", false)), listOf(Step("open web.whatsapp.com", "Google", false)))),
            { s, _ -> if (s.contains("Launcher") && dev.actions.isEmpty()) "open WhatsApp" else "open web.whatsapp.com" })
        val msg = Agent(dev, brain, FakeUi(), cfg).run("message Mom")
        assertEquals(listOf("WhatsApp is not installed; use its website"), brain.planFacts[1])
        assertEquals(listOf("open WhatsApp", "open web.whatsapp.com"), dev.actions)
        assertEquals("report: done", msg)
    }

    @Test fun passwordIsHandedToTheUser() {
        val login = listOf(app(input("Password", 300, password = true), button("Sign in", 600), label = "Bank"))
        val dev = FakeDevice("login", mapOf("login" to login))
        val ui = FakeUi(true, false)
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("log in", "Welcome", false)))), { _, _ -> "type 1 hunter2" })
        Agent(dev, brain, ui, cfg).run("log in to my bank")
        assertEquals(listOf("Type your password, then tap Continue", "Type your password, then tap Continue"), ui.asked)
        assertTrue(dev.actions.none { it.contains("hunter2") })
        assertTrue(brain.actLasts.contains("user typed the password"))
    }

    @Test fun invalidReplyIsReaskedOnceWithError() {
        val dev = weatherDevice()
        var n = 0
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open google.com", "Google", false)))), { _, _ -> if (n++ == 0) "I will now open it" else "open google.com" })
        Agent(dev, brain, FakeUi(), cfg).run("weather")
        assertEquals("ERR unknown command i. Reply with one command.", brain.actLasts[1])
        assertEquals(listOf("open google.com"), dev.actions)
    }

    @Test fun riskyStepNeedsConfirmation() {
        val dev = weatherDevice()
        val ui = FakeUi(false)
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("send the message", "Sent", true)))), { _, _ -> "tap 2" })
        val msg = Agent(dev, brain, ui, cfg).run("send hi")
        assertEquals(listOf("About to: send the message"), ui.asked)
        assertTrue(dev.actions.isEmpty())
        assertTrue(msg.startsWith("You declined"))
        assertEquals(0, brain.reports)
    }

    @Test fun lockedPhoneStopsBeforeAnyCall() {
        val dev = weatherDevice().apply { locked = true }
        val brain = FakeBrain(ArrayDeque(), { _, _ -> "tap 1" })
        assertEquals("Unlock the phone first", Agent(dev, brain, FakeUi(), cfg).run("x"))
        assertTrue(brain.planFacts.isEmpty())
    }

    @Test fun stopFlagHaltsImmediately() {
        val dev = weatherDevice()
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("open google.com", "Google", false)))), { _, _ -> "open google.com" })
        val agent = Agent(dev, brain, FakeUi(), cfg)
        agent.stop()
        assertEquals("Stopped", agent.run("x"))
        assertTrue(dev.actions.isEmpty())
    }

    @Test fun timeBudgetEnforced() {
        var t = 0L
        val dev = FakeDevice("home", mapOf("home" to home))
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("wait", "never", false)))), { _, _ -> t += 61_000; "scroll down" })
        val rows = ArrayList<String>()
        val msg = Agent(dev, brain, FakeUi(), cfg, csv = { rows += it }, now = { t }).run("x")
        assertTrue(rows.single().endsWith("stuck at step 1"))
        assertTrue(msg.startsWith("report: stuck"))
    }

    @Test fun apiErrorAsksToRetry() {
        val dev = weatherDevice()
        var failed = false
        val inner = FakeBrain(ArrayDeque(listOf(listOf(Step("open google.com", "Google", false)))), { _, _ -> "open google.com" })
        val flaky = object : Brain by inner {
            override fun plan(goal: String, facts: List<String>, screen: String): List<Step> {
                if (!failed) { failed = true; throw ApiError("HTTP 503", true) }
                return inner.plan(goal, facts, screen)
            }
        }
        val ui = FakeUi(true)
        assertEquals("report: done", Agent(dev, flaky, ui, cfg).run("weather"))
        assertTrue(ui.asked.single().startsWith("Model API error: HTTP 503"))
    }

    @Test fun visionGridWithZoomPass() {
        val canvas = listOf(app(label = "Game")) // no actionable nodes at all
        val dev = FakeDevice("canvas", mapOf("canvas" to canvas))
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("tap play", "Score", false)))), { _, _ -> "key back" }, ArrayDeque(listOf("tap 6", "tap 5")))
        val vcfg = cfg.copy(vision = Endpoint("http://v", "", "vl"))
        Agent(dev, brain, FakeUi(), vcfg).run("play")
        assertEquals(24, dev.shots[0].first) // 4x6 grid
        assertEquals(9, dev.shots[1].first) // 3x3 zoom grid
        val crop = dev.shots[1].second!!
        assertEquals(listOf(230, 360, 580, 840), crop.toList()) // cell 6 (270..540, 400..800) plus 40 px margin
        val cx = 230 + 350 / 2; val cy = 360 + 480 / 2 // centre of the middle zoom cell
        assertTrue(dev.actions[0], dev.actions[0].startsWith("tapXY"))
        val (x, y) = dev.actions[0].removePrefix("tapXY ").split(',').map { it.toInt() }
        assertTrue(kotlin.math.abs(x - cx) <= 2 && kotlin.math.abs(y - cy) <= 2)
    }

    @Test fun protectedScreenDisablesVision() {
        val canvas = listOf(app(label = "Bank"))
        val dev = FakeDevice("canvas", mapOf("canvas" to canvas)).apply { shotResult = null }
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("read balance", "Balance", false)))), { _, _ -> "scroll down" })
        Agent(dev, brain, FakeUi(), cfg.copy(vision = Endpoint("http://v", "", "vl"))).run("balance")
        assertEquals(1, dev.shots.size)
        assertTrue(brain.planFacts[1].contains("screen is protected"))
    }

    @Test fun noVisionWithoutVisionModel() {
        val dev = FakeDevice("canvas", mapOf("canvas" to listOf(app(label = "Game"))))
        val brain = FakeBrain(ArrayDeque(listOf(listOf(Step("tap play", "Score", false)))), { _, _ -> "scroll down" })
        Agent(dev, brain, FakeUi(), cfg).run("play")
        assertTrue(dev.shots.isEmpty())
        assertFalse(dev.actions.isEmpty())
    }
}
