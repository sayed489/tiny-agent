package app.tinyagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every request and answers from a script. */
class FakeApi(private vararg val replies: String) : ChatApi {
    class Call(val msgs: List<Msg>, val temp: Double, val max: Int, val extra: Map<String, Any?>)
    val calls = ArrayList<Call>()
    override fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?>): Reply {
        calls += Call(msgs, temperature, maxTokens, extra)
        val tokens = msgs.sumOf { Screen.tokens(it.text) } + 10
        return Reply(replies[minOf(calls.size - 1, replies.size - 1)], tokens, 3, 5)
    }
}

class LlmTest {
    private val items = digest(app(
        input("Search", 100), button("Voice search", 300), button("Send", 500), button("Send", 1500),
        textNode("Mom", 700)))
        .items

    private fun ok(reply: String, expect: Cmd, mark: Int = 0) {
        val (c, err) = parse(reply, items, mark)
        assertNull("unexpected error for '$reply': $err", err)
        assertEquals(expect, c)
    }

    private fun bad(reply: String, mark: Int = 0): String {
        val (c, err) = parse(reply, items, mark)
        assertNull("expected an error for '$reply', got $c", c)
        return err!!
    }

    @Test fun parsesPlainCommands() {
        ok("tap 2", Cmd.Tap(2))
        ok("open WhatsApp", Cmd.Open("WhatsApp"))
        ok("type 1 running late", Cmd.Type(1, "running late"))
        ok("scroll down", Cmd.Scroll("down"))
        ok("key enter", Cmd.Key("enter"))
        ok("key notifications", Cmd.Key("notifications"))
    }

    @Test fun stripsWrappers() {
        ok("<think>the user wants voice</think>\n\ntap 2", Cmd.Tap(2))
        ok("thinking…</think>tap 2", Cmd.Tap(2))
        ok("`tap 2`", Cmd.Tap(2))
        ok("1. tap 2", Cmd.Tap(2))
        ok("- tap 2", Cmd.Tap(2))
        ok("Action: tap 2", Cmd.Tap(2))
        ok("Command: \"scroll up\"", Cmd.Scroll("up"))
        ok("tap [2]", Cmd.Tap(2))
        ok("tap 2\nthen I will type", Cmd.Tap(2))
        ok("Tap 2.", Cmd.Tap(2))
    }

    @Test fun verbAliases() {
        ok("click 2", Cmd.Tap(2))
        ok("press back", Cmd.Key("back"))
        ok("swipe left", Cmd.Scroll("left"))
        ok("launch Clock", Cmd.Open("Clock"))
        ok("input 1 hello", Cmd.Type(1, "hello"))
    }

    @Test fun labelArguments() {
        ok("tap Voice search", Cmd.Tap(2))
        ok("tap \"voice search\"", Cmd.Tap(2))
        assertTrue(bad("tap Send").contains("id")) // two items labelled Send: not unique
        ok("type Search weather tokyo", Cmd.Type(1, "weather tokyo"))
        ok("type \"running late\"", Cmd.Type(null, "running late"))
        ok("type hello", Cmd.Type(null, "hello"))
    }

    @Test fun rejectsInvalid() {
        assertTrue(bad("tap 99").contains("99"))
        assertTrue(bad("type 42 hi").contains("42"))
        assertTrue(bad("scroll sideways").contains("down"))
        assertTrue(bad("key menu").contains("back"))
        assertTrue(bad("dance").contains("unknown"))
        assertTrue(bad("").contains("empty"))
        assertTrue(bad("<think>never closed").contains("empty"))
        assertTrue(bad("type 1 " + "x".repeat(201)).contains("200"))
        assertTrue(bad("open").contains("open"))
    }

    @Test fun visionMarks() {
        ok("tap 17", Cmd.Tap(17), mark = 20)
        bad("tap 21", mark = 20)
        bad("tap Voice search", mark = 20)
        ok("scroll down", Cmd.Scroll("down"), mark = 20)
    }

    @Test fun planParsing() {
        val p = parsePlan("""
            Here is the plan:
            1. open WhatsApp => WhatsApp is open
            2) search Mom => Mom's chat is open
            - !send "running late" => the message shows as sent
            no arrow here
            => missing action
        """.trimIndent())
        assertEquals(3, p.size)
        assertEquals(Step("open WhatsApp", "WhatsApp is open", false), p[0])
        assertEquals("search Mom", p[1].doing)
        assertTrue(p[2].risky)
        assertEquals("send \"running late\"", p[2].doing)
        assertEquals("the message shows as sent", p[2].doneWhen)
        assertEquals(6, parsePlan((1..9).joinToString("\n") { "do $it => done $it" }).size)
        assertTrue(parsePlan("I cannot help").isEmpty())
    }

    @Test fun planArrowsListsAndAnswers() {
        assertEquals(listOf(Step("open YouTube", "YouTube is open", false), Step("search Mr Indian Hacker", "the channel is shown", false)),
            parsePlan("1. open YouTube -> YouTube is open\n2. search Mr Indian Hacker → the channel is shown"))
        val list = parsePlan("Steps:\n1. Open YouTube\n2. !Order a brush on Amazon\nDone.")
        assertEquals(listOf("Open YouTube", "Order a brush on Amazon"), list.map { it.doing })
        assertTrue(list[1].risky)
        assertEquals("Open YouTube is done", list[0].doneWhen)
        assertEquals(listOf(Step("", "Hi! What should I do on your phone?", false)), parsePlan("answer: Hi! What should I do on your phone?"))
        assertEquals(listOf(Step("", "Hello!", false)), parsePlan("<think>greeting</think>\nAnswer: Hello!"))
        assertEquals("open X", parsePlan("open X => X is open\nanswer: hi").single().doing) // steps win over an answer
    }

    @Test fun planFallbackIsLogged() {
        val logs = ArrayList<String>()
        Llm(FakeApi(""), log = { logs += it }).plan("Go to YouTube", emptyList(), "App: none | popup: no | keyboard: no")
        assertTrue(logs.toString(), logs.any { it.startsWith("⚠ Could not read the model's plan (empty reply)") })
    }

    @Test fun looseTapLabels() {
        ok("tap on Voice search", Cmd.Tap(2))
        ok("tap the Voice search button", Cmd.Tap(2)) // "Search" is inside too, but the longest label wins
        ok("tap voice", Cmd.Tap(2))
        bad("tap the Send button") // two Send buttons: still ambiguous
        bad("tap on it")
    }

    @Test fun planPrefixStillWithinBudget() { assertTrue(Screen.tokens(Prompts.PLAN) <= 150) }

    @Test fun planRetriesThenFallsBack() {
        val api = FakeApi("sure!", "still no plan")
        val plan = Llm(api).plan("weather in Tokyo", emptyList(), "App: Launcher | popup: no | keyboard: no")
        assertEquals(listOf(Step("weather in Tokyo", "the goal is done", false)), plan)
        assertEquals(listOf(0.3, 0.5), api.calls.map { it.temp })
        assertTrue(api.calls.all { it.max == 150 })
    }

    @Test fun samplingPerCallType() {
        val api = FakeApi("tap 1")
        val llm = Llm(api, visionApi = api)
        val st = Step("open Chrome", "Chrome is open", false)
        llm.act("g", st, 1, 2, "none", emptyList(), "App: X | popup: no | keyboard: no")
        llm.check("Chrome is open", "App: X | popup: no | keyboard: no")
        llm.report("g", "done", "App: X | popup: no | keyboard: no")
        llm.look(st, byteArrayOf(1, 2, 3))
        assertEquals(listOf(24, 2, 60, 12), api.calls.map { it.max })
        assertEquals(listOf(0.1, 0.1, 0.3, 0.1), api.calls.map { it.temp })
        assertTrue(api.calls.last().msgs[1].jpeg != null)
    }

    @Test fun checkParsesYesNoAndStructuredOutputs() {
        assertTrue(Llm(FakeApi("Y")).check("x", "s"))
        assertTrue(Llm(FakeApi("yes")).check("x", "s"))
        assertTrue(!Llm(FakeApi("N")).check("x", "s"))
        assertTrue(!Llm(FakeApi("")).check("x", "s"))
        val api = FakeApi("Y")
        Llm(api, yesNo = true).check("x", "s")
        assertEquals(mapOf("structured_outputs" to mapOf("choice" to listOf("Y", "N"))), api.calls[0].extra)
    }

    @Test fun staticPrefixesWithinBudgetAndIdentical() {
        assertTrue(Screen.tokens(Prompts.PLAN) <= 150)
        assertTrue(Screen.tokens(Prompts.ACT) <= 150)
        val api = FakeApi("tap 1")
        val llm = Llm(api)
        val st = Step("a", "b", false)
        llm.act("goal one", st, 1, 1, "none", emptyList(), "App: X | popup: no | keyboard: no\n1 button A")
        llm.act("another goal", st, 1, 1, "tap \"A\" -> screen unchanged", listOf("tap \"A\""), "App: Y | popup: no | keyboard: no")
        assertEquals("system", api.calls[0].msgs[0].role)
        assertEquals(api.calls[0].msgs[0].text, api.calls[1].msgs[0].text)
        assertEquals(Prompts.ACT, api.calls[0].msgs[0].text)
    }

    @Test fun userPromptFormat() {
        val api = FakeApi("tap 1")
        Llm(api).act("weather in Tokyo", Step("search weather Tokyo", "results are shown", false), 2, 3,
            "type \"Search\" \"weather Tokyo\" -> screen changed", listOf("tap \"Search\""), "SCREEN")
        assertEquals("Goal: weather in Tokyo\nStep 2 of 3: search weather Tokyo (done when: results are shown)\n" +
            "Last: type \"Search\" \"weather Tokyo\" -> screen changed\nTried: tap \"Search\"\nScreen:\nSCREEN", api.calls[0].msgs[1].text)
    }

    @Test fun shrinksDigestNeverPrefixWhenOverBudget() {
        // Deliberately oversized digest (40 items, ~450 tokens) so Llm has to shrink it.
        val big = (listOf("App: X | popup: no | keyboard: no") + (1..40).map { "$it button Quite a long label number $it" }).joinToString("\n")
        assertTrue(Screen.tokens(big) > 400)
        val api = FakeApi("tap 1")
        val llm = Llm(api)
        val goal = "send a long message to my friend about the meeting tomorrow at the office near the station, please"
        llm.act(goal, Step("open WhatsApp and find the chat with my friend", "the chat with my friend is open", false), 1, 6,
            "none", listOf("tap \"Quite a long label number 1\"", "scroll down", "key back"), big)
        val sent = api.calls[0].msgs
        assertEquals(Prompts.ACT, sent[0].text)
        val total = Screen.tokens(sent[0].text) + Screen.tokens(sent[1].text) + 10
        assertTrue("prompt was $total", total <= 430)
        assertTrue(sent[1].text.contains("more below: scroll down"))
        assertTrue(sent[1].text.lines().count { it.contains(" button ") } <= 16)
    }

    @Test fun usageRecorded() {
        val u = Usage()
        val llm = Llm(FakeApi("tap 1"), usage = u)
        llm.act("g", Step("a", "b", false), 1, 1, "none", emptyList(), "S")
        llm.check("b", "S")
        assertEquals(2, u.calls)
        assertTrue(u.maxPrompt in 1..450)
    }
}
