package app.tinyagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    private fun chrome(dy: Int = 0, discover: String = "Discover") = app(
        node(hint = "Search or type web address", cls = "android.widget.EditText", l = 40, t = 100 + dy, r = 800, b = 200 + dy, click = true, edit = true),
        node(desc = "Voice search", cls = "android.widget.ImageButton", l = 820, t = 110 + dy, r = 900, b = 190 + dy, click = true),
        node(cls = "android.widget.ImageView", l = 950, t = 110 + dy, r = 1060, b = 190 + dy, click = true),
        node(cls = "android.widget.LinearLayout", l = 40, t = 400 + dy, r = 280, b = 600 + dy, click = true,
            kids = listOf(node(cls = "android.widget.ImageView", l = 60, t = 410 + dy, r = 260, b = 520 + dy), textNode("YouTube", 530 + dy, 60))),
        node(cls = "android.widget.LinearLayout", l = 300, t = 400 + dy, r = 540, b = 600 + dy, click = true,
            kids = listOf(textNode("Amazon.in", 530 + dy, 320))),
        node(cls = "androidx.recyclerview.widget.RecyclerView", l = 0, t = 650 + dy, r = W, b = H, fwd = true,
            kids = listOf(textNode(discover, 700 + dy))),
        label = "Chrome", pkg = "com.android.chrome",
    )

    @Test fun tokenEstimate() {
        assertEquals(0, Screen.tokens(""))
        assertEquals(2, Screen.tokens("abcdefg")) // ceil(7 / 3.5)
        assertEquals(1, Screen.tokens("é"))
        assertEquals(1 + 2, Screen.tokens("abc日本")) // ceil(3 / 3.5) + 2 non-ASCII
    }

    @Test fun chromeExampleMatchesBrief() {
        val d = digest(chrome())
        assertEquals("""
            App: Chrome | popup: no | keyboard: no
            1 input (Search or type web address)
            2 button Voice search
            3 button (icon, top right)
            4 button YouTube
            5 button Amazon.in
            6 text Discover
            more below: scroll down
        """.trimIndent(), d.text)
        assertEquals(5, d.actionable)
        assertEquals(1, d.unlabeled)
        assertTrue(Screen.tokens(d.text) < 120)
        assertEquals(listOf("input", "button", "button", "button", "button", "text"), d.items.map { it.kind })
    }

    @Test fun passwordTextNeverShown() {
        val d = digest(app(input("Password", 300, text = "hunter2", password = true)))
        assertTrue(d.text.contains("1 input password (Password)"))
        assertFalse(d.text.contains("hunter2"))
    }

    @Test fun inputShowsCurrentText() {
        val d = digest(app(input("Search", 300, text = "weather tokyo")))
        assertTrue(d.text, d.text.contains("1 input (Search) = \"weather tokyo\""))
    }

    @Test fun popupListedFirstAndKeyboardFlagged() {
        val base = app(button("Settings", 1000), layer = 1, active = false)
        val dialog = win(node(cls = "android.widget.FrameLayout", l = 100, t = 900, r = 980, b = 1400,
            kids = listOf(textNode("Allow access?", 950, 140), button("Allow", 1200, 600))), "Permission", "com.perm", layer = 2)
        val ime = win(node(l = 0, t = 1600, r = W, b = H, kids = listOf(button("q", 1700))), "Keyboard", "com.ime", layer = 3, ime = true, active = false)
        val own = win(node(l = 0, t = 0, r = W, b = 200, kids = listOf(button("Stop", 50))), "TinyAgent", "app.tinyagent", layer = 4, own = true)
        val d = digest(base, dialog, ime, own)
        val lines = d.text.lines()
        assertEquals("App: App | popup: yes | keyboard: yes", lines[0])
        assertEquals("1 text Allow access?", lines[1])
        assertEquals("2 button Allow", lines[2])
        assertEquals("3 button Settings", lines[3])
        assertFalse(d.text.contains("Stop"))
        assertFalse(d.text.contains(" q"))
        assertTrue(d.popup && d.keyboard)
    }

    @Test fun inactiveSystemUiSkipped() {
        val bar = win(node(l = 0, t = 0, r = W, b = 80, kids = listOf(textNode("12:30", 10))), "System UI", "com.android.systemui", layer = 5, active = false)
        val d = digest(app(button("OK", 500)), bar)
        assertFalse(d.text.contains("12:30"))
        assertTrue(d.text.contains("popup: no"))
    }

    @Test fun hashIgnoresBoundsButNotContent() {
        assertEquals(digest(chrome()).hash, digest(chrome(dy = 30)).hash)
        assertNotEquals(digest(chrome()).hash, digest(chrome(discover = "Trending")).hash)
    }

    @Test fun switchRowReadsAsOneSwitch() {
        val row = node(cls = "android.widget.LinearLayout", l = 0, t = 300, r = W, b = 420, click = true, kids = listOf(
            textNode("Wi-Fi", 320), node(cls = "android.widget.Switch", l = 900, t = 320, r = 1040, b = 400, click = true, checkable = true, checked = true)))
        val d = digest(app(row))
        assertEquals(1, d.items.size)
        assertTrue(d.text, d.text.contains("1 switch on Wi-Fi"))
    }

    @Test fun actBudgetAndReadBudget() {
        val many = (0 until 30).map { button("Item $it", 100 + it * 70) }.toTypedArray()
        val act = digest(app(*many))
        assertEquals(16, act.items.size)
        assertTrue(act.text.endsWith("more below: scroll down"))
        val read = digest(app(*many), mode = Mode.READ)
        assertEquals(6, read.items.size)
    }

    @Test fun trimsToTokenBudget() {
        val many = (0 until 16).map { button("A fairly long button label $it", 100 + it * 90) }.toTypedArray()
        val d = digest(app(*many), max = 60)
        assertTrue(Screen.tokens(d.text) <= 60)
        assertTrue(d.items.size in 1..15)
        assertTrue(d.text.contains("more below: scroll down"))
    }

    @Test fun labelsTruncatedPerMode() {
        val long = "This label is definitely longer than sixty characters in total, really"
        assertTrue(digest(app(textNode(long, 300))).items[0].label.let { it.length == 28 && it.endsWith("…") })
        assertTrue(digest(app(textNode(long, 300)), mode = Mode.READ).items[0].label.let { it.length == 60 && it.endsWith("…") })
    }

    @Test fun dedupeNearbySameLabel() {
        val d = digest(app(button("Next", 500), button("Next", 520, l = 400)))
        assertEquals(1, d.items.size)
        assertEquals(2, digest(app(button("Next", 500), button("Next", 900))).items.size)
    }

    @Test fun skipsHiddenZeroAreaAndOffscreen() {
        val d = digest(app(
            node(text = "hidden", l = 0, t = 100, r = 100, b = 200, visible = false),
            node(text = "flat", l = 0, t = 300, r = 100, b = 300),
            node(text = "offscreen", l = 0, t = 3000, r = 100, b = 3100),
            textNode("shown", 500)))
        assertEquals(listOf("shown"), d.items.map { it.label })
    }

    @Test fun rowOrderingUsesTolerance() {
        val d = digest(app(button("Right", 108, l = 700), button("Left", 100, l = 40), button("Below", 300)))
        assertEquals(listOf("Left", "Right", "Below"), d.items.map { it.label })
    }

    @Test fun moreAboveFooter() {
        val d = digest(win(node(l = 0, t = 0, r = W, b = H, back = true, kids = listOf(button("A", 300)))))
        assertTrue(d.text.endsWith("more above: scroll up"))
    }

    @Test fun shrinkKeepsHeaderAndFooters() {
        val text = "App: X | popup: no | keyboard: no\n1 button A\n2 button B\n3 button C\nmore above: scroll up"
        assertEquals("App: X | popup: no | keyboard: no\n1 button A\n2 button B\nmore below: scroll down\nmore above: scroll up", Screen.shrink(text, 2))
        assertEquals(text, Screen.shrink(text, 3))
    }

    @Test fun pickAppOrder() {
        val labels = listOf("Clock", "Calculator", "Google Chrome", "Chrome Beta", "Chrome")
        assertEquals(0, Screen.pickApp("clock", labels))
        assertEquals(4, Screen.pickApp("Chrome", labels))
        assertEquals(4, Screen.pickApp("chr", labels)) // starts-with: shortest wins
        assertEquals(1, Screen.pickApp("calc", labels))
        assertEquals(2, Screen.pickApp("goo", labels))
        assertEquals(0, Screen.pickApp("the Clock app", labels))
        assertEquals(-1, Screen.pickApp("WhatsApp", labels))
        assertEquals(-1, Screen.pickApp("", labels))
    }

    @Test fun urlDetection() {
        assertTrue(Screen.isUrl("google.com"))
        assertTrue(Screen.isUrl("https://m.youtube.com/watch"))
        assertFalse(Screen.isUrl("Google Maps"))
        assertFalse(Screen.isUrl("weather in st. louis"))
    }

    @Test fun marksAndGrid() {
        val marks = Screen.marks(listOf(chrome()), W, H)
        assertTrue(marks.size >= 3)
        assertTrue(marks.all { it.r - it.l >= 24 })
        val g = Screen.grid(4, 6, intArrayOf(0, 0, W, H))
        assertEquals(24, g.size)
        assertEquals(listOf(0, 0, 270, 400), g[0].toList())
        assertEquals(listOf(810, 2000, 1080, 2400), g[23].toList())
    }
}
