// Phone.kt: AccessibilityService. Node conversion, the 5 actions, settle wait, screenshot with marks, overlay, kill switch.
package app.tinyagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.accessibility.*
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as AA
import android.widget.*
import org.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.*

data class Res(val ok: Boolean, val msg: String, val missingApp: String? = null)

interface Device {
    fun windows(): List<Win>; fun size(): Pair<Int, Int>; fun locked(): Boolean
    fun open(target: String): Res; fun tap(n: N): Res; fun tapXY(x: Int, y: Int): Res
    fun type(n: N?, text: String): Res; fun scroll(dir: String, n: N?): Res; fun key(k: String): Res
    fun settle(maxMs: Long = 4000)
    fun shot(boxes: List<IntArray>, outW: Int, outH: Int, crop: IntArray? = null): ByteArray? // JPEG with numbered boxes; null if protected
}

class Phone : AccessibilityService(), Device, Ui {
    companion object { @Volatile var instance: Phone? = null }
    val running get() = agent != null

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var agent: Agent? = null
    @Volatile private var lastEvent = 0L; @Volatile private var swallowUp = false; @Volatile private var imeTop = Int.MAX_VALUE
    private var worker: Thread? = null; private val answers = LinkedBlockingQueue<Boolean>()
    private var pill: LinearLayout? = null; private var pillText: TextView? = null
    private var yesBtn: Button? = null; private var stopBtn: Button? = null
    private val labels = HashMap<String, String>(); private var dims: Pair<Int, Int>? = null
    private var apps: List<Pair<String, ComponentName>>? = null

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) { if (e?.packageName != packageName) lastEvent = SystemClock.uptimeMillis() }
    override fun onInterrupt() = stopAgent()
    override fun onUnbind(intent: Intent?): Boolean { stopAgent(); instance = null; return super.onUnbind(intent) }

    override fun onKeyEvent(e: KeyEvent): Boolean { // kill switch: volume-down while a task runs (down and up both consumed)
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (e.action == KeyEvent.ACTION_DOWN && agent != null) { log("Volume-down: stopping"); stopAgent(); swallowUp = true; return true }
        return (e.action == KeyEvent.ACTION_UP && swallowUp).also { if (it) swallowUp = false }
    }

    fun start(goal: String, cfg: Config): Boolean {
        if (agent != null) return false
        val usage = Usage()
        val a = Agent(this, Llm.from(cfg, usage, ::log), this, cfg, usage, ::csv)
        agent = a; apps = null; dims = null
        showPill()
        worker = Thread({
            try { a.run(goal) } catch (e: Exception) { log("Crash: $e") } finally { agent = null; main.post { stopBtn?.text = "Close"; yesBtn?.visibility = View.GONE } }
        }, "agent").also { it.start() }
        return true
    }

    fun stopAgent() { agent?.stop(); answers.offer(false); worker?.interrupt() }

    // ---- Ui (overlay pill + shared log) ----
    override fun log(s: String) = RunLog.add(s)
    override fun status(s: String) { main.post { pillText?.text = s } }
    override fun ask(q: String): Boolean {
        answers.clear()
        main.post { pillText?.text = q; yesBtn?.visibility = View.VISIBLE }
        val r = try { answers.take() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }
        main.post { yesBtn?.visibility = View.GONE }
        log("Asked: $q -> ${if (r) "continue" else "stop"}")
        return r
    }

    private fun showPill() = main.post {
        val wm = getSystemService(WindowManager::class.java)
        pill?.let { wm.removeView(it) }
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 3; setPadding(24, 8, 8, 8); text = "Starting…" }
        val yes = Button(this).apply { text = "Continue"; visibility = View.GONE; setOnClickListener { answers.offer(true) } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { if (agent != null) stopAgent() else hidePill() } }
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(8, 4, 8, 4)
            background = GradientDrawable().apply { cornerRadius = 60f; setColor(0xE6202124.toInt()) }
            addView(tv, LinearLayout.LayoutParams(0, -2, 1f)); addView(yes); addView(stop)
        }
        val lp = WindowManager.LayoutParams((size().first * 0.94).toInt(), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
        wm.addView(box, lp)
        pill = box; pillText = tv; yesBtn = yes; stopBtn = stop
    }

    private fun hidePill() { pill?.let { getSystemService(WindowManager::class.java).removeView(it) }; pill = null }

    /** Hide/show the pill on the main thread and wait until it is applied (for gestures and screenshots). */
    private fun pillVisible(v: Boolean) {
        val l = CountDownLatch(1)
        main.post { pill?.visibility = if (v) View.VISIBLE else View.INVISIBLE; l.countDown() }
        l.await(1, TimeUnit.SECONDS); if (!v) Thread.sleep(60)
    }

    private fun onPill(x: Int, y: Int): Boolean {
        val p = pill ?: return false
        val o = IntArray(2).also { p.getLocationOnScreen(it) }
        return x in o[0]..(o[0] + p.width) && y in o[1]..(o[1] + p.height)
    }

    // ---- Device: screen reading ----
    override fun windows(): List<Win> {
        for (attempt in 0..3) {
            val out = ArrayList<Win>(); var activeMissing = false; var ime = Int.MAX_VALUE
            for (w in (try { getWindows() } catch (e: Exception) { emptyList() }).sortedByDescending { it.layer }) {
                val isIme = w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                if (isIme) { val b = Rect(); w.getBoundsInScreen(b); ime = b.top }
                val r = w.root
                if (r == null) { if (w.isActive) activeMissing = true; continue }
                val pkg = r.packageName?.toString() ?: ""
                val own = pkg == packageName || w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                val root = if (own || isIme) leaf() else conv(r, 0, intArrayOf(1500))
                out += Win(root, pkg, label(pkg), w.layer, isIme, own, w.isActive)
            }
            imeTop = ime
            if (!activeMissing || attempt == 3) return out else Thread.sleep(300)
        }
        return emptyList()
    }

    private fun leaf() = N(null, null, null, null, "", 0, 0, 0, 0, false, false, false, false, false, false, false, false, false, emptyList())
    private fun conv(a: AccessibilityNodeInfo, depth: Int, budget: IntArray): N {
        budget[0]--
        val b = Rect(); a.getBoundsInScreen(b)
        val acts = a.actionList
        val kids = if (depth > 60 || budget[0] <= 0) emptyList() else (0 until a.childCount).mapNotNull { i -> a.getChild(i)?.let { conv(it, depth + 1, budget) } }
        return N(if (a.isShowingHintText) null else a.text?.toString(), a.contentDescription?.toString(), a.hintText?.toString(),
            a.viewIdResourceName, a.className?.toString() ?: "", b.left, b.top, b.right, b.bottom, a.isVisibleToUser,
            a.isClickable || acts.contains(AA.ACTION_CLICK), a.isEditable, a.isCheckable, @Suppress("DEPRECATION") a.isChecked,
            acts.contains(AA.ACTION_SCROLL_FORWARD) || acts.contains(AA.ACTION_SCROLL_DOWN) || acts.contains(AA.ACTION_SCROLL_RIGHT),
            acts.contains(AA.ACTION_SCROLL_BACKWARD) || acts.contains(AA.ACTION_SCROLL_UP) || acts.contains(AA.ACTION_SCROLL_LEFT),
            a.isPassword, a.isFocused, kids, a)
    }

    private fun label(pkg: String) = labels.getOrPut(pkg) {
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
    }
    @Suppress("DEPRECATION")
    override fun size(): Pair<Int, Int> = dims ?: Point().also {
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
    }.let { (it.x to it.y).also { d -> dims = d } }
    override fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Wait for 400 ms without window events (min 300 ms, max [maxMs]; up to 8 s while a ProgressBar shows). */
    override fun settle(maxMs: Long) {
        val t0 = SystemClock.uptimeMillis(); var limit = maxMs; var lastProg = 0L
        while (true) {
            Thread.sleep(50)
            val now = SystemClock.uptimeMillis(); val el = now - t0
            if (el >= limit) return
            if (el < 300 || now - lastEvent < 400 || now - lastProg < 500) continue
            lastProg = now
            if (!progress(rootInActiveWindow, intArrayOf(400))) return
            limit = maxOf(limit, 8000)
        }
    }

    private fun progress(n: AccessibilityNodeInfo?, budget: IntArray): Boolean {
        if (n == null || budget[0]-- <= 0) return false
        if (n.isVisibleToUser && n.className?.contains("ProgressBar") == true) return true
        return (0 until n.childCount).any { progress(n.getChild(it), budget) }
    }

    // ---- Device: the 5 actions ----
    override fun open(target: String): Res {
        val t = target.trim().trimEnd('.')
        val intent: Intent; val pkg: String?
        if (Screen.isUrl(t)) {
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"))
            pkg = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } else {
            val list = apps ?: packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(packageManager).toString() to ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.also { apps = it }
            val i = Screen.pickApp(t, list.map { it.first })
            if (i < 0) return Res(false, "no app named $t", missingApp = t)
            intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(list[i].second)
            pkg = list[i].second.packageName
        }
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) } catch (e: Exception) { return Res(false, "cannot open $t") }
        val t0 = SystemClock.uptimeMillis()
        while (pkg != null && pkg != "android" && SystemClock.uptimeMillis() - t0 < 8000 && rootInActiveWindow?.packageName?.toString() != pkg) Thread.sleep(150)
        return Res(true, "opened $t")
    }

    override fun tap(n: N): Res {
        val a = n.ref as? AccessibilityNodeInfo
        if (a != null) {
            if (a.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Res(true, "tapped")
            var p = a.parent; var d = 0
            while (p != null && d++ < 8) { if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Res(true, "tapped"); p = p.parent }
        }
        return tapXY((n.l + n.r) / 2, (n.t + n.b) / 2)
    }

    override fun tapXY(x: Int, y: Int): Res {
        if (y >= imeTop) { key("back"); settle(1500) }
        return if (gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 50)) Res(true, "tapped at $x,$y") else Res(false, "tap gesture failed")
    }

    override fun type(n: N?, text: String): Res {
        if (n?.password == true) return Res(false, "password field: the user must type it")
        var a = n?.ref as? AccessibilityNodeInfo
        if (n != null && a != null && !a.isEditable) { tap(n); settle(2000); a = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        if (a == null) a = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: onlyInput()
        if (a == null || !a.isEditable) return Res(false, "no input to type into")
        if (a.isPassword) return Res(false, "password field: the user must type it")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (a.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && hasText(a, text)) return Res(true, "typed; key enter submits")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("tinyagent", text))
        a.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val len = a.text?.length ?: 0
        if (len > 0) a.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len) })
        if (a.performAction(AccessibilityNodeInfo.ACTION_PASTE) && hasText(a, text)) return Res(true, "typed; key enter submits")
        return Res(false, "could not type here")
    }

    private fun hasText(a: AccessibilityNodeInfo, s: String): Boolean { Thread.sleep(80); a.refresh(); return a.text?.toString()?.contains(s) == true }
    private fun onlyInput() = find { it.edit }.singleOrNull()?.ref as? AccessibilityNodeInfo

    /** Visible nodes matching [pred] in the windows the digest lists. */
    private fun find(pred: (N) -> Boolean): List<N> {
        val out = ArrayList<N>()
        fun walk(n: N) { if (n.visible && pred(n)) out += n; n.kids.forEach { walk(it) } }
        Screen.shown(windows(), size().first, size().second).forEach { walk(it.root) }
        return out
    }

    override fun scroll(dir: String, n: N?): Res {
        val horiz = dir == "left" || dir == "right"; val fwd = dir == "down" || dir == "right"
        val oriented = (n?.let { listOf(it) } ?: find { it.scrollFwd || it.scrollBack }).filter { isHoriz(it) == horiz }
        val node = oriented.filter { if (fwd) it.scrollFwd else it.scrollBack }.maxByOrNull { (it.r - it.l).toLong() * (it.b - it.t) }
        if (node == null && oriented.isNotEmpty()) return Res(false, "can't scroll $dir any further")
        val a = node?.ref as? AccessibilityNodeInfo
        val dirAct = when (dir) { "down" -> AA.ACTION_SCROLL_DOWN; "up" -> AA.ACTION_SCROLL_UP; "left" -> AA.ACTION_SCROLL_LEFT; else -> AA.ACTION_SCROLL_RIGHT }
        if (a != null && (a.performAction(dirAct.id) ||
                a.performAction(if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) return Res(true, "scrolled $dir")
        val (sw, sh) = size()
        val r = if (node != null) intArrayOf(node.l, node.t, node.r, node.b) else intArrayOf(0, sh / 5, sw, sh * 4 / 5)
        val cx = (r[0] + r[2]) / 2f; val cy = (r[1] + r[3]) / 2f; val dx = (r[2] - r[0]) * 0.3f; val dy = (r[3] - r[1]) * 0.3f
        val (sx, sy) = when (dir) { "down" -> 0f to dy; "up" -> 0f to -dy; "right" -> dx to 0f; else -> -dx to 0f }
        val p = Path().apply { moveTo(cx + sx, cy + sy); lineTo(cx - sx, cy - sy) } // finger moves opposite to the content
        return if (gesture(p, 300)) Res(true, "swiped $dir") else Res(false, "can't scroll $dir")
    }

    private fun isHoriz(n: N): Boolean {
        val a = n.ref as? AccessibilityNodeInfo
        if (n.cls.contains("Horizontal") || n.cls.contains("ViewPager")) return true
        if (a != null && (a.actionList.contains(AA.ACTION_SCROLL_LEFT) || a.actionList.contains(AA.ACTION_SCROLL_RIGHT))) return true
        return a?.collectionInfo?.let { it.rowCount <= 1 && it.columnCount > 1 } == true
    }

    override fun key(k: String): Res {
        val g = when (k) { "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; else -> -1 }
        if (g >= 0) return if (performGlobalAction(g)) Res(true, "pressed $k") else Res(false, "key $k failed")
        if (k != "enter") return Res(false, "unknown key $k")
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return Res(false, "no focused input for enter")
        return if (f.performAction(AA.ACTION_IME_ENTER.id)) Res(true, "pressed enter") else Res(false, "enter not supported here")
    }

    private fun gesture(p: Path, ms: Long): Boolean {
        val b = android.graphics.RectF(); p.computeBounds(b, true)
        val hide = onPill(b.left.toInt(), b.top.toInt()) || onPill(b.right.toInt(), b.bottom.toInt())
        if (hide) pillVisible(false)
        val l = CountDownLatch(1); var ok = false
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; l.countDown() }
                override fun onCancelled(g: GestureDescription?) { l.countDown() }
            }, null)
        if (sent) l.await(ms + 2000, TimeUnit.MILLISECONDS)
        if (hide) pillVisible(true)
        return sent && ok
    }

    // ---- Device: screenshot with numbered marks ----
    override fun shot(boxes: List<IntArray>, outW: Int, outH: Int, crop: IntArray?): ByteArray? {
        pillVisible(false)
        try {
            for (attempt in 0..1) {
                val l = CountDownLatch(1); var bmp: Bitmap? = null; var err = -1
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val hb = r.hardwareBuffer
                        bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, true); hb.close(); l.countDown()
                    }
                    override fun onFailure(code: Int) { err = code; l.countDown() }
                })
                l.await(3, TimeUnit.SECONDS)
                bmp?.let { return render(it, boxes, outW, outH, crop) }
                if (err == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt == 0) { Thread.sleep(500); continue }
                return null // protected window (FLAG_SECURE) or other failure
            }
            return null
        } finally { pillVisible(true) }
    }

    private fun render(src: Bitmap, boxes: List<IntArray>, outW: Int, outH: Int, crop: IntArray?): ByteArray {
        val c = crop ?: intArrayOf(0, 0, src.width, src.height)
        val s = minOf(outW.toFloat() / (c[2] - c[0]), outH.toFloat() / (c[3] - c[1]))
        val out = Bitmap.createBitmap(((c[2] - c[0]) * s).toInt().coerceAtLeast(1), ((c[3] - c[1]) * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(out).apply { scale(s, s); translate(-c[0].toFloat(), -c[1].toFloat()) }
        cv.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); cv.setMatrix(null)
        val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.RED }
        val bg = Paint().apply { color = Color.RED }
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f; isFakeBoldText = true }
        boxes.forEachIndexed { i, b -> // box i is labelled i+1, in output-image coordinates
            val l = (b[0] - c[0]) * s; val t = (b[1] - c[1]) * s
            cv.drawRect(l, t, (b[2] - c[0]) * s, (b[3] - c[1]) * s, line)
            cv.drawRect(l, t, l + fg.measureText("${i + 1}") + 6, t + 16, bg); cv.drawText("${i + 1}", l + 3, t + 13, fg)
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    // ---- Run log CSV and screen dumps ----
    private fun csv(row: String) = File(filesDir, "runs.csv").run {
        if (!exists()) writeText("goal,model,actions,llm_calls,max_prompt_tokens,vision_calls,replans,seconds,status\n")
        appendText(row + "\n")
    }

    /** After [delayMs], write the current window tree as JSON to Downloads and open the share sheet. */
    fun dumpLater(delayMs: Long) = Thread {
        Thread.sleep(delayMs)
        val (w, h) = size()
        val arr = JSONArray()
        windows().filter { !it.isOwn }.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("appLabel", it.appLabel).put("layer", it.layer)
            .put("isIme", it.isIme).put("isOwn", it.isOwn).put("isActive", it.isActive).put("root", nodeJson(it.root))) }
        val json = JSONObject().put("w", w).put("h", h).put("windows", arr).toString()
        val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "tinyagent-dump-${System.currentTimeMillis()}.json"); put(MediaStore.Downloads.MIME_TYPE, "application/json") }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return@Thread log("Dump failed")
        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        log("Screen dump saved to Downloads (contains screen text)")
        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share screen dump").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.start()

    private fun nodeJson(n: N): JSONObject = JSONObject().put("text", if (n.password) null else n.text).put("desc", n.desc).put("hint", n.hint).put("viewId", n.viewId)
        .put("cls", n.cls).put("bounds", JSONArray(listOf(n.l, n.t, n.r, n.b))).put("visible", n.visible).put("click", n.click)
        .put("edit", n.edit).put("checkable", n.checkable).put("checked", n.checked).put("scrollFwd", n.scrollFwd)
        .put("scrollBack", n.scrollBack).put("password", n.password).put("focused", n.focused)
        .put("kids", JSONArray().also { a -> n.kids.forEach { a.put(nodeJson(it)) } })
}
