// Main.kt: the activity (Views built in code): onboarding, settings, goal, run/stop, live log, dump screen. Keystore key storage.
package app.tinyagent

import android.app.*
import android.content.*
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.security.keystore.*
import android.text.InputType
import android.util.Base64
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

class Main : Activity() {
    private lateinit var prefs: SharedPreferences; private lateinit var col: LinearLayout
    private lateinit var svc: TextView; private lateinit var logView: TextView
    private val fields = LinkedHashMap<String, EditText>(); private val toggles = LinkedHashMap<String, CheckBox>()
    private val secret = setOf("key", "vkey") // stored encrypted

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 24, 40, 40) }
        svc = text("Service: off", 20f, true)
        row(btn("Enable service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            btn("Battery") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        text("1. Android 13+: App info > ⋮ > Allow restricted settings.\n2. Enable the TinyAgent accessibility service.\n" +
            "3. Exempt the app from battery optimization, because some phone makers kill accessibility services.\n" +
            "Kill switch: press volume-down while a task runs.", 14f)
        text("Model (any OpenAI-compatible endpoint, cloud or your own server)", 16f, true)
        field("url", "Base URL, e.g. https://api.openai.com/v1 or http://192.168.1.20:8080/v1")
        field("key", "API key (blank for local servers)", masked = true)
        field("model", "Model, e.g. qwen3.5-0.8b")
        field("pmodel", "Plan model (optional, defaults to model)")
        field("extra", "Extra body JSON, e.g. {\"chat_template_kwargs\":{\"enable_thinking\":false}}")
        text("Vision (optional)", 16f, true)
        field("vurl", "Vision base URL"); field("vkey", "Vision API key", masked = true); field("vmodel", "Vision model")
        field("img", "Image size WxH (352x768 Qwen, 320x704 LFM)")
        toggle("risky", "Confirm risky steps", true); toggle("every", "Confirm every step", false)
        toggle("yn", "Constrain CHECK to Y/N (vLLM structured_outputs)", false)
        text("Goal", 16f, true)
        field("goal", "e.g. set an alarm for 7:30")
        row(btn("Run") { run() }, btn("Stop") { Phone.instance?.stopAgent() })
        row(btn("Copy log") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", RunLog.text())); toast("Log copied")
        }, btn("Dump screen") { dump() })
        logView = text("", 11f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        val root = ScrollView(this).apply { addView(col) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); v.setPadding(i.left, i.top, i.right, i.bottom); ins
        }
        setContentView(root)
        load()
        if (!prefs.getBoolean("privacy", false)) AlertDialog.Builder(this).setTitle("Privacy")
            .setMessage("To decide what to do, TinyAgent sends the text on your screen (and, with vision on, screenshots) to the API provider you configure. Passwords are never typed or sent.")
            .setCancelable(false).setPositiveButton("I understand") { _, _ -> prefs.edit().putBoolean("privacy", true).apply() }.show()
    }

    override fun onResume() {
        super.onResume()
        svc.text = "Service: " + if (Phone.instance != null) "on" else "off"
        RunLog.listener = { runOnUiThread { logView.text = RunLog.text() } }
        logView.text = RunLog.text()
    }

    override fun onPause() { super.onPause(); RunLog.listener = null; save() }

    private fun run() {
        save()
        val v = { k: String -> fields.getValue(k).text.toString().trim() }
        val phone = Phone.instance ?: return toast("Enable the TinyAgent accessibility service first")
        if (phone.running) return toast("A task is already running")
        if (v("url").isEmpty() || v("model").isEmpty()) return toast("Set Base URL and model first")
        if (v("goal").isEmpty()) return toast("Type a goal")
        val extra = v("extra")
        if (extra.isNotEmpty() && runCatching { JSONObject(extra) }.isFailure) return toast("Extra body is not a JSON object")
        val sz = v("img").split('x', 'X', '*').mapNotNull { it.trim().toIntOrNull() }
        val (iw, ih) = if (sz.size == 2 && sz.all { it in 64..2048 }) sz[0] to sz[1] else 352 to 768
        val vision = if (v("vurl").isNotEmpty() && v("vmodel").isNotEmpty()) Endpoint(v("vurl"), v("vkey"), v("vmodel"), extra) else null
        val cfg = Config(Endpoint(v("url"), v("key"), v("model"), extra), v("pmodel"), vision, iw, ih,
            toggles.getValue("risky").isChecked, toggles.getValue("every").isChecked, toggles.getValue("yn").isChecked)
        if (!phone.start(v("goal"), cfg)) toast("Could not start")
    }

    private fun dump() = AlertDialog.Builder(this).setTitle("Dump screen")
        .setMessage("In 5 seconds TinyAgent saves the current screen tree as JSON to Downloads and opens the share sheet. The file contains the text on that screen. Switch to the app you want now.")
        .setPositiveButton("Start") { _, _ ->
            val p = Phone.instance ?: return@setPositiveButton toast("Enable the service first")
            p.dumpLater(5000); moveTaskToBack(true)
        }.setNegativeButton("Cancel", null).show()

    // ---- settings storage (API keys encrypted with an Android Keystore AES-GCM key) ----
    private fun load() {
        fields.forEach { (k, f) -> val s = prefs.getString(k, "") ?: ""; f.setText(if (k in secret) dec(s) else s) }
        if (fields.getValue("img").text.isEmpty()) fields.getValue("img").setText("352x768")
        toggles.forEach { (k, c) -> c.isChecked = prefs.getBoolean(k, c.isChecked) }
    }

    private fun save() {
        val e = prefs.edit()
        fields.forEach { (k, f) -> val s = f.text.toString().trim(); e.putString(k, if (k in secret) enc(s) else s) }
        toggles.forEach { (k, c) -> e.putBoolean(k, c.isChecked) }
        e.apply()
    }

    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("tinyagent", null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("tinyagent", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun enc(s: String): String {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, ksKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP) // 12-byte IV + ciphertext
    }
    private fun dec(s: String): String = try {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { "" }

    // ---- tiny view helpers ----
    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, 16, 0, 8); col.addView(this)
    }

    private fun field(key: String, hint: String, masked: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or if (masked) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        col.addView(this); fields[key] = this
    }
    private fun toggle(key: String, label: String, def: Boolean) = CheckBox(this).apply { text = label; isChecked = def; col.addView(this); toggles[key] = this }
    private fun btn(label: String, f: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { f() } }
    private fun row(vararg v: View) = col.addView(LinearLayout(this).apply { v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } })
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
