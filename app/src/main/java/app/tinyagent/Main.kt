// Main.kt: chat screen (Main), settings screen (Options), saved settings + Keystore (Store), tiny View helpers. Views are built in code.
package app.tinyagent

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.security.keystore.*
import android.text.InputType
import android.util.Base64
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

private val ACCENT = 0xFF4F46E5.toInt(); private val INK = 0xFF1F2330.toInt(); private val GREY = 0xFF6B7280.toInt()
private val BG = 0xFFF4F5F9.toInt(); private val RED = 0xFFD93025.toInt(); private val LINE = 0xFFD5D8E3.toInt()

// ---- View helpers shared by both screens ----
private fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
private fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
private fun Context.shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
}
private fun Context.tv(s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
}
private fun Context.btn(s: String, fill: Int = ACCENT, ink: Int = Color.WHITE, f: () -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; setTextColor(ink); background = shape(fill, 22); stateListAnimator = null
    setPadding(dp(18), 0, dp(18), 0); setOnClickListener { f() }
}
private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
private fun LinearLayout.put(v: View) = addView(v, lp(-1, -2).apply { topMargin = context.dp(6) })

/** Edge-to-edge page: we apply status bar, nav bar and keyboard insets ourselves, so the input bar rides above the keyboard. */
private fun Activity.frame(v: View) {
    window.setDecorFitsSystemWindows(false)
    v.setBackgroundColor(BG)
    v.setOnApplyWindowInsetsListener { x, i ->
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); x.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
    }
    setContentView(v)
}

// ---- Permission helpers (the accessibility switch itself can only be flipped by the user, in system settings) ----
private fun Context.batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
private fun Activity.askBattery() = try {
    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
} catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
private fun Activity.askService() = AlertDialog.Builder(this).setTitle("Turn on TinyAgent")
    .setMessage("TinyAgent needs its accessibility service to see the screen and tap for you.\n\n" +
        "1. Tap “Open settings”, choose TinyAgent, and switch it on.\n" +
        "2. If it says “Restricted setting” (Android 13+): come back, tap “App info”, open the ⋮ menu, choose “Allow restricted settings”, then repeat step 1.")
    .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    .setNeutralButton("App info") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    .setNegativeButton("Later", null).show()

/** Chat screen: you type a goal, TinyAgent works on the phone and reports progress here. */
class Main : Activity() {
    private lateinit var msgs: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var input: EditText
    private lateinit var go: Button; private lateinit var banner: TextView; private lateinit var sub: TextView
    private var busy = false; private var bannerAct: () -> Unit = {}
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        sub = tv("", 12f, GREY)
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8)); setBackgroundColor(Color.WHITE)
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(tv("TinyAgent", 20f, bold = true)); addView(sub) }, lp(0, -2, 1f))
            addView(btn("New chat", Color.TRANSPARENT, ACCENT) { if (busy) toast("Stop the running task first") else RunLog.clear() })
            addView(btn("Settings", Color.TRANSPARENT, ACCENT) { startActivity(Intent(this@Main, Options::class.java)) })
        }
        banner = tv("", 13f, 0xFF5F4300.toInt()).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(0xFFFFF1CC.toInt()); setOnClickListener { bannerAct() }
        }
        msgs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { addView(msgs) }
        input = EditText(this).apply {
            hint = "Tell me what to do on your phone…"; textSize = 15f; maxLines = 4; imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = shape(Color.WHITE, 22, LINE); setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        go = btn("Send") { if (busy) Phone.instance?.stopAgent() else send() }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(BG)
            addView(input, lp(0, -2, 1f)); addView(go, lp(-2, dp(46)).apply { leftMargin = dp(8) })
        }
        frame(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bar); addView(banner); addView(scroll, lp(-1, 0, 1f)); addView(row) })
        if (!Store.flag(this, "privacy", false)) AlertDialog.Builder(this).setTitle("Before you start").setCancelable(false)
            .setMessage("TinyAgent reads the text on your screen and sends it, with your goal, to the AI provider you choose in Settings. " +
                "It never types passwords. Stop any task with the Stop button, or press volume-down.")
            .setPositiveButton("I understand") { _, _ -> Store.setFlag(this, "privacy", true); if (Phone.instance == null) askService() }.show()
    }

    override fun onResume() { super.onResume(); RunLog.listener = { runOnUiThread { render() } }; Phone.instance?.hideIfIdle(); refresh() }
    override fun onPause() { super.onPause(); RunLog.listener = null }
    private fun refresh() {
        val prob = Store.problem(this); val svc = Phone.instance != null
        if (svc && prob == null && !batteryOk() && !Store.flag(this, "askedBattery", false)) { Store.setFlag(this, "askedBattery", true); askBattery() }
        banner.visibility = View.VISIBLE
        when {
            !svc -> { banner.text = "Step 1: turn on the accessibility service. Tap here ›"; bannerAct = { askService() } }
            prob != null -> { banner.text = "Step 2: $prob. Tap here ›"; bannerAct = { startActivity(Intent(this, Options::class.java)) } }
            !batteryOk() -> { banner.text = "Recommended: let TinyAgent run in the background. Tap here ›"; bannerAct = { askBattery() } }
            else -> banner.visibility = View.GONE
        }
        sub.text = if (!svc || prob != null) "Setup needed" else "Ready"
        render()
    }

    private fun send() {
        val goal = input.text.toString().trim()
        if (goal.isEmpty() || busy) return
        val phone = Phone.instance ?: run { askService(); return }
        Store.problem(this)?.let { toast(it); startActivity(Intent(this, Options::class.java)); return }
        RunLog.add("Goal: $goal"); input.setText(""); input.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        if (!phone.start(goal, Store.config(this))) RunLog.add("Result (error): Could not start")
    }

    /** Rebuilds the chat from the shared run log: your goal, a progress bubble (plan with ✓ done / ▶ current, latest actions), the result. */
    private fun render() {
        msgs.removeAllViews(); busy = false
        var plan = ArrayList<String>(); val done = HashSet<Int>(); var acts = ArrayList<String>(); var replan = ""
        fun flush() {
            val cur = (1..plan.size).firstOrNull { it !in done }
            val lines = (if (replan.isEmpty()) emptyList() else listOf(replan)) +
                plan.mapIndexed { i, p -> (if (i + 1 in done) "✓ " else if (busy && i + 1 == cur) "▶ " else "    ") + "${i + 1}. ${p.take(44)}" } +
                (if (acts.isEmpty() || plan.isEmpty()) acts else listOf("") + acts.takeLast(4)) + (if (busy) listOf("…") else emptyList())
            if (plan.isNotEmpty() || acts.isNotEmpty()) msgs.addView(bubble(lines.joinToString("\n"), false, 0xFFE9EAF2.toInt(), 13f))
            plan = ArrayList(); done.clear(); acts = ArrayList(); replan = ""
        }
        fun note(x: String) { if (x.startsWith("· not yet")) acts.removeAll { it.startsWith("· not yet") }; if (acts.lastOrNull() != x) acts += x }
        for (l in RunLog.text().lines()) when {
            l.startsWith("Goal: ") -> { flush(); msgs.addView(bubble(l.drop(6), true)); busy = true }
            l.startsWith("Result (") -> {
                busy = false; flush()
                val st = l.substringBefore("): ").drop(8)
                msgs.addView(bubble(l.substringAfter("): "), false, when (st) { "done" -> 0xFFDDF5E4.toInt(); "stopped", "answered" -> Color.WHITE; else -> 0xFFFDE7E7.toInt() }))
            }
            l.startsWith("Usage: ") -> msgs.addView(tv(l.drop(7), 11f, GREY).apply { setPadding(dp(6), 0, 0, dp(6)) })
            l.startsWith("↻ ") -> { replan = l; acts = ArrayList() }
            l.startsWith("Plan ") -> { if (l.startsWith("Plan 1:")) plan = ArrayList(); plan += l.substringAfter(": ").substringBefore(" =>") }
            l.startsWith("✓ ") -> { l.drop(2).takeWhile { it.isDigit() }.toIntOrNull()?.let { done += it }; acts = ArrayList() }
            l.startsWith("→ ") -> acts += "· " + l.drop(2)
            l.startsWith("· not yet") || l.startsWith("· waiting") || l.startsWith("⚠ ") || l.startsWith("Asked") || l.startsWith("API error") || l.startsWith("Volume") -> note(l)
        }
        flush()
        if (msgs.childCount == 0) {
            msgs.addView(tv("Hi! Tell me what to do on your phone and I'll do it for you.", 16f).apply { setPadding(dp(4), dp(24), dp(4), dp(8)) })
            for (s in listOf("Set an alarm for 7:30", "What's the weather in Tokyo?", "Open Settings and turn on Wi-Fi")) msgs.addView(
                btn(s, Color.WHITE, ACCENT) { input.setText(s); input.setSelection(s.length) }.apply { background = shape(Color.WHITE, 22, LINE) }, lp(-2, dp(44)).apply { topMargin = dp(6) })
        }
        go.text = if (busy) "Stop" else "Send"; go.background = shape(if (busy) RED else ACCENT, 22)
        if (Phone.instance != null && Store.problem(this) == null) sub.text = if (busy) "Working…" else "Ready"
        scroll.post { scroll.scrollTo(0, msgs.height) }
    }

    private fun bubble(s: String, mine: Boolean, tint: Int = Color.WHITE, size: Float = 15f) = tv(s, size, if (mine) Color.WHITE else INK).apply {
        setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(if (mine) ACCENT else tint, 18)
        maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        layoutParams = lp(-2, -2).apply { gravity = if (mine) Gravity.END else Gravity.START; topMargin = dp(4); bottomMargin = dp(4) }
    }
}

/** Settings screen: model + API key first, then permissions, behaviour, advanced options and tools. */
class Options : Activity() {
    private val f = LinkedHashMap<String, EditText>(); private val sw = LinkedHashMap<String, Switch>()
    private lateinit var col: LinearLayout; private lateinit var adv: LinearLayout
    private lateinit var a11y: TextView; private lateinit var bat: TextView; private lateinit var out: TextView
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.WHITE)
            addView(btn("‹ Back", Color.TRANSPARENT, ACCENT) { finish() }); addView(tv("Settings", 18f, bold = true))
        }
        head("AI model"); note("Any OpenAI-compatible chat API: paste the Base URL, API key and model name exactly as your provider gives them.")
        field("url", "Base URL (the part before /chat/completions)", "https://your-provider.com/v1"); field("key", "API key (leave empty if your server has none)", "paste key", masked = true)
        field("model", "Model name (exactly as your provider lists it)", "model name")
        toggle("vis", "My model can see images: also use it to read screenshots", false)
        field("pin", "Price per 1M input tokens, USD (optional, for the cost line)", "0.10")
        field("pout", "Price per 1M output tokens, USD (optional)", "0.50")
        field("rate", "1 USD in ₹, to show the cost in rupees", "92", def = "92")
        out = note("")
        col.put(btn("Test connection") { testConnection() }); col.put(out)
        head("Permissions")
        a11y = note(""); col.put(a11y); col.put(btn("Turn on accessibility service") { askService() })
        bat = note(""); col.put(bat); col.put(btn("Allow background activity") { askBattery() })
        head("Safety")
        toggle("risky", "Ask before sending, buying, posting or deleting", true); toggle("every", "Ask before every step", false)
        col.put(btn("Advanced ▾", Color.WHITE, ACCENT) { adv.visibility = if (adv.visibility == View.GONE) View.VISIBLE else View.GONE }.apply { background = shape(Color.WHITE, 22, LINE) })
        col.addView(adv)
        head("Advanced", adv); note("Extra body is merged into every request, for example {\"chat_template_kwargs\":{\"enable_thinking\":false}} for Qwen3 on vLLM or llama.cpp.", adv)
        field("pmodel", "Plan model (optional, defaults to Model)", "", into = adv); field("extra", "Extra request body (JSON)", "{}", into = adv)
        field("vurl", "Separate vision Base URL (optional)", "", into = adv); field("vkey", "Vision API key", "", masked = true, into = adv)
        field("vmodel", "Vision model", "", into = adv); field("img", "Screenshot size WxH sent to the vision model", "352x768", def = "352x768", into = adv)
        toggle("yn", "Constrain CHECK to Y/N (vLLM only)", false, adv)
        head("Tools")
        col.put(btn("Copy debug log", Color.WHITE, ACCENT) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", RunLog.text())); toast("Log copied")
        }.apply { background = shape(Color.WHITE, 22, LINE) })
        col.put(btn("Dump screen for bug reports", Color.WHITE, ACCENT) { dump() }.apply { background = shape(Color.WHITE, 22, LINE) })
        frame(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bar); addView(ScrollView(context).apply { addView(col) }, lp(-1, 0, 1f)) })
    }

    override fun onResume() {
        super.onResume()
        a11y.text = "Accessibility service: " + if (Phone.instance != null) "ON ✓" else "OFF"
        bat.text = "Background activity: " + if (batteryOk()) "allowed ✓" else "not allowed (some phones stop the service)"
    }

    override fun onPause() { super.onPause(); f.forEach { (k, e) -> Store.put(this, k, e.text.toString().trim()) }; sw.forEach { (k, s) -> Store.setFlag(this, k, s.isChecked) } }

    /** Runs one real PLAN call and one real ACT call on a sample screen and shows what the model wrote and what TinyAgent understood. */
    private fun testConnection() {
        val v = { k: String -> f.getValue(k).text.toString().trim() }
        out.text = "Testing…"
        Thread {
            val r = try {
                val api = ChatApi.create(Endpoint(v("url"), v("key"), v("model"), v("extra")))
                val scr = "App: Launcher | popup: no | keyboard: no\n1 button YouTube\n2 button Chrome\n3 button Camera"
                val p = api.chat(listOf(Msg("system", Prompts.PLAN), Msg("user", "Task: open YouTube and search for cats\nFacts: none\nApps: Camera, Chrome, Clock, YouTube\nScreen now:\n$scr")), 0.3, 240, think = false)
                val am = listOf(Msg("system", Prompts.ACT), Msg("user", "Goal: open YouTube\nStep 1 of 1: open YouTube (done when: YouTube is open)\nLast: none\nTried: none\nScreen:\n$scr"))
                val a = api.chat(am, 0.1, 48, think = false).let { if ((api as? OpenAiCompat)?.thinks == true) api.chat(am, 0.1, 48, think = false) else it }
                val o = api as? OpenAiCompat
                val steps = parsePlan(p.text); val cmd = parse(a.text, emptyList(), 3)
                fun raw(t: String) = "“" + t.substringAfterLast("</think>").trim().replace('\n', ' ').take(90) + "”"
                "✓ Connected (${p.ms + a.ms} ms)\nPlan: " + (if (steps.isEmpty()) "✗ could not read ${raw(p.text)}" else steps.joinToString(" › ") { it.doing.ifEmpty { "answer" } }) +
                    "\nAction: " + (cmd.first?.let { "✓ $it" } ?: "✗ could not read ${raw(a.text)} (${cmd.second})") +
                    "\nSpeed: one action took ${a.ms} ms" + (if (a.completionTokens > 0) " (${a.completionTokens} output tokens)" else "") +
                    (if (o?.thinks != true) "" else o.offSwitch?.let { "\nThinking: switched off with $it ✓" } ?: "\nThinking: this model keeps thinking, so each step is slower") +
                    (if (o?.dropped.isNullOrEmpty()) "" else "\nNot supported by this server, so no longer sent: " + o!!.dropped.joinToString(", "))
            } catch (e: Exception) {
                val m = e.message.orEmpty().lowercase()
                val aboutModel = e is ApiError && e.code in listOf(400, 404, 422) && (m.contains("model") || m.contains("deployment"))
                val ids = if (aboutModel) try { ChatApi.create(Endpoint(v("url"), v("key"), v("model"), v("extra"))).models() } catch (x: Exception) { emptyList() } else emptyList()
                "✗ ${e.message}" + (if (aboutModel && cleanBaseUrl(v("url")).contains(".azure.")) "\nOn Azure, Model is your deployment name." else "") +
                    if (ids.isEmpty()) "" else "\nModel names this server lists: " + ids.take(15).joinToString(", ")
            }
            runOnUiThread { out.text = r }
        }.start()
    }

    private fun dump() = AlertDialog.Builder(this).setTitle("Dump screen")
        .setMessage("In 5 seconds TinyAgent saves the screen tree as JSON to Downloads and opens the share sheet. The file contains the text on that screen. Switch to the app you want now.")
        .setPositiveButton("Start") { _, _ -> Phone.instance?.dumpLater(5000) ?: return@setPositiveButton toast("Turn on the accessibility service first"); moveTaskToBack(true) }
        .setNegativeButton("Cancel", null).show()

    private fun head(s: String, into: LinearLayout = col) { into.addView(tv(s, 14f, ACCENT, true).apply { setPadding(0, dp(22), 0, dp(2)) }) }
    private fun note(s: String, into: LinearLayout = col) = tv(s, 13f, GREY).also { if (s.isNotEmpty()) into.put(it) }
    private fun field(key: String, label: String, hint: String, masked: Boolean = false, def: String = "", into: LinearLayout = col) {
        into.put(tv(label, 13f, GREY).apply { setPadding(dp(4), dp(8), 0, 0) })
        f[key] = EditText(this).apply {
            setText(Store.get(this@Options, key, def)); this.hint = hint; textSize = 15f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or if (masked) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = shape(Color.WHITE, 12, LINE); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        into.put(f.getValue(key))
    }
    private fun toggle(key: String, label: String, def: Boolean, into: LinearLayout = col) {
        sw[key] = Switch(this).apply { text = label; textSize = 15f; setTextColor(INK); isChecked = Store.flag(this@Options, key, def) }
        into.put(sw.getValue(key))
    }
}

/** Saved settings. API keys are encrypted with an Android Keystore AES-GCM key. Other screens only call these functions. */
object Store {
    private val secret = setOf("key", "vkey")
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, def: String = ""): String = p(c).getString(k, null)?.let { if (k in secret) dec(it) else it } ?: def
    fun put(c: Context, k: String, v: String) = p(c).edit().putString(k, if (k in secret) enc(v) else v).apply()
    fun flag(c: Context, k: String, def: Boolean) = p(c).getBoolean(k, def)
    fun setFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    /** What is still missing before a task can run, or null when ready. */
    fun problem(c: Context): String? {
        val url = cleanBaseUrl(get(c, "url")); val extra = get(c, "extra").trim()
        return when {
            url.isEmpty() || get(c, "model").isBlank() -> "Add your AI model in Settings"
            get(c, "key").isBlank() && !url.startsWith("http://") -> "Add your API key in Settings"
            extra.isNotEmpty() && runCatching { JSONObject(extra) }.isFailure -> "The extra request body in Settings is not valid JSON"
            else -> null
        }
    }

    fun config(c: Context): Config {
        val g = { k: String -> get(c, k).trim() }
        val sz = g("img").split('x', 'X', '*').mapNotNull { it.trim().toIntOrNull() }
        val (iw, ih) = if (sz.size == 2 && sz.all { it in 64..2048 }) sz[0] to sz[1] else 352 to 768
        val vision = if (g("vurl").isNotEmpty() && g("vmodel").isNotEmpty()) Endpoint(g("vurl"), g("vkey"), g("vmodel"), g("extra"))
            else if (flag(c, "vis", false)) Endpoint(g("url"), g("key"), g("model"), g("extra")) else null // a vision-language model serves both
        return Config(Endpoint(g("url"), g("key"), g("model"), g("extra")), g("pmodel"), vision, iw, ih,
            flag(c, "risky", true), flag(c, "every", false), flag(c, "yn", false),
            num(g("pin")), num(g("pout")), num(g("rate").ifEmpty { "92" }))
    }
    private fun num(s: String) = s.replace(',', '.').trim('$', '₹', ' ').toDoubleOrNull() ?: 0.0

    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("tinyagent", null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("tinyagent", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun enc(s: String): String = if (s.isEmpty()) "" else Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, ksKey()) }
        .let { c -> Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP) } // 12-byte IV + ciphertext
    private fun dec(s: String): String = try {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { "" }
}
