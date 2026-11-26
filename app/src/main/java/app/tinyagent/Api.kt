// =====================================================================================================
//  Api.kt  —  DETACHABLE MODEL TRANSPORT  (the only file that talks to a model server)
//
//  Everything else in the app depends on the small `ChatApi` interface below, never on HTTP or JSON.
//  * Different provider (OpenAI, OpenRouter, Groq, vLLM, llama.cpp server, Ollama, LM Studio, ...):
//    no code change. Set Base URL / key / model in Settings. Local examples:
//      llama.cpp  http://192.168.1.20:8080/v1     Ollama  http://192.168.1.20:11434/v1
//      LM Studio  http://192.168.1.20:1234/v1      vLLM    http://192.168.1.20:8000/v1
//  * Different transport (another wire format, a mock, a proxy): write a class that implements
//    `ChatApi` and return it from `ChatApi.create`. Nothing else needs to change.
//  Pure JVM (java.net + org.json): no android.* imports, so it is unit-testable off-device.
// =====================================================================================================
package app.tinyagent

import org.json.*
import java.io.IOException
import java.net.*
import java.util.Base64

/** Where to send requests. `extraBody` is a JSON object merged into every request body. */
data class Endpoint(val baseUrl: String, val key: String, val model: String, val extraBody: String = "") {
    override fun toString() = "Endpoint($baseUrl, model=$model)" // never print the key
}

data class Msg(val role: String, val text: String, val jpeg: ByteArray? = null)
data class Reply(val text: String, val promptTokens: Int, val completionTokens: Int, val ms: Long)
class ApiError(msg: String, val retryable: Boolean, val code: Int = 0) : Exception(msg)

/** Accepts what people paste: a URL, "host/v1" without https://, the full .../chat/completions URL, or a code line like base_url="https://host/v1",. */
fun cleanBaseUrl(raw: String): String {
    var s = raw.trim()
    val i = listOf(s.indexOf("http://"), s.indexOf("https://")).filter { it >= 0 }.minOrNull() ?: -1
    s = if (i >= 0) s.substring(i).takeWhile { !it.isWhitespace() && it !in "\"'`,;)" } else s.trim('"', '\'', '`', ',', ';', ')', ' ').let {
        if (it.isEmpty()) it else if (it.startsWith("localhost") || it[0].isDigit()) "http://$it" else "https://$it" }
    return s.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
}
/** An API key as pasted: no quotes, spaces or "Bearer " prefix. */
fun cleanKey(raw: String) = raw.trim().trim('"', '\'', '`').trim().removePrefix("Bearer ").trim()

interface ChatApi {
    fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?> = emptyMap()): Reply
    /** Model ids this endpoint offers (GET /models), or empty when unknown. Used to help when the model name is wrong. */
    fun models(): List<String> = emptyList()

    companion object {
        /** The single switch point: swap the implementation here to detach from OpenAI-compatible HTTP. */
        fun create(ep: Endpoint): ChatApi = OpenAiCompat(ep)
    }
}

/** OpenAI-compatible POST {baseUrl}/chat/completions. Keep-alive is reused across calls for speed. */
class OpenAiCompat(private val ep: Endpoint, private val waits: LongArray = longArrayOf(1000, 3000)) : ChatApi {
    private val base = cleanBaseUrl(ep.baseUrl); private val key = cleanKey(ep.key)
    private var url = "$base/chat/completions"; private var triedV1 = false
    private val extraBody: JSONObject? = ep.extraBody.trim().takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

    /**
     * What we learned about this endpoint from its replies, shared by every task until the app restarts.
     * - HTTP 400s: newer OpenAI models want max_completion_tokens and only the default temperature.
     * - Empty replies cut off by the token limit: a reasoning model spent the tiny budget thinking. From then on we
     *   send a larger budget and ask for low reasoning effort ("low", then "none", then nothing if both are rejected).
     */
    private class Quirks { var tokenKey = "max_tokens"; var sendTemp = true; var thinks = false; var budget = 2048; var effort = 0 }
    private companion object { val learned = HashMap<String, Quirks>(); val EFFORTS = listOf("low", "none") }
    private val q = synchronized(learned) { learned.getOrPut("$url|${ep.model}") { Quirks() } }

    /** True once this endpoint has shown it is a reasoning ("thinking") model: it works, but slower. */
    val thinks get() = q.thinks

    override fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?>): Reply {
        var attempt = 0; var adapts = 0
        while (true) {
            val body = JSONObject().put("model", ep.model).put(q.tokenKey, if (q.thinks) maxOf(maxTokens, q.budget) else maxTokens)
                .put("messages", JSONArray().also { a -> msgs.forEach { a.put(msgJson(it)) } })
            if (q.sendTemp) body.put("temperature", temperature)
            if (q.thinks && q.effort < EFFORTS.size) body.put("reasoning_effort", EFFORTS[q.effort])
            extraBody?.let { e -> e.keys().forEach { body.put(it, e.get(it)) } }
            extra.forEach { (k, v) -> body.put(k, json(v)) }
            try {
                val (r, cutOff) = post(body.toString().toByteArray(), if (q.thinks) 90_000 else 30_000)
                if (!cutOff || adapts++ >= 3) return r
                if (q.thinks) q.budget = minOf(q.budget * 2, 8192) else q.thinks = true // empty and cut off: think less, allow more
            } catch (e: ApiError) {
                if (e.code == 404 && !triedV1 && runCatching { URL(base).path.isEmpty() }.getOrDefault(false)) { // "http://host:8000" without /v1
                    triedV1 = true; url = "$base/v1/chat/completions"; continue
                }
                if (e.code == 400 && adapts++ < 4 && adapt(e.message.orEmpty())) continue
                if (!e.retryable || attempt >= waits.size) throw e
                Thread.sleep(waits[attempt++])
            }
        }
    }

    private fun adapt(m: String) = when {
        q.tokenKey == "max_tokens" && m.contains("max_completion_tokens") -> { q.tokenKey = "max_completion_tokens"; true }
        q.sendTemp && m.contains("temperature") -> { q.sendTemp = false; true }
        q.thinks && q.effort < EFFORTS.size && m.contains("reasoning") -> { q.effort++; true }
        else -> false
    }

    override fun models(): List<String> = try {
        val c = URL("$base/models").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
        val a = JSONObject(c.inputStream.use { it.readBytes().decodeToString() }).getJSONArray("data")
        (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    } catch (e: Exception) { emptyList() }

    /** The provider's own error message when the body is OpenAI-style JSON, else the raw text. */
    private fun errText(t: String) = try { JSONObject(t).getJSONObject("error").getString("message") } catch (e: JSONException) { t }.trim().take(200)

    /** One HTTP call. The flag is true when the visible reply is empty because the model ran out of tokens or only reasoned. */
    private fun post(bytes: ByteArray, readMs: Int): Pair<Reply, Boolean> {
        val t0 = System.currentTimeMillis()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = readMs; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (code >= 400) throw ApiError("HTTP $code ${errText(text)}" + if (code == 404) " (check the Base URL: most servers end with /v1)" else "", code == 429 || code >= 500, code)
            val j = JSONObject(text)
            val ch = j.getJSONArray("choices").getJSONObject(0)
            val m = ch.optJSONObject("message")
            val content = if (m == null || m.isNull("content")) "" else m.getString("content")
            val u = j.optJSONObject("usage")
            val visible = content.substringAfterLast("</think>").let { if (it.contains("<think>")) it.substringBefore("<think>") else it }
            val reasoned = (u?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", 0) ?: 0) > 0 ||
                listOf("reasoning_content", "reasoning").any { k -> m != null && !m.isNull(k) && m.optString(k).isNotEmpty() }
            val cutOff = visible.isBlank() && (ch.optString("finish_reason") == "length" || reasoned || content.contains("<think>"))
            return Reply(content, u?.optInt("prompt_tokens", -1) ?: -1, u?.optInt("completion_tokens", -1) ?: -1,
                System.currentTimeMillis() - t0) to cutOff
        } catch (e: SocketTimeoutException) { throw ApiError("timeout", true)
        } catch (e: JSONException) { throw ApiError("bad JSON reply", false)
        } catch (e: IOException) { throw ApiError("network: ${e.message}", true) }
    }

    private fun msgJson(m: Msg): JSONObject {
        val o = JSONObject().put("role", m.role)
        if (m.jpeg == null) return o.put("content", m.text)
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(m.jpeg)
        return o.put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", m.text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))))
    }

    private fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), json(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
        else -> v
    }
}
