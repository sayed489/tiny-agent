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
    /** [think] = false asks a thinking model to answer directly (much faster); servers that can't switch it off just think. */
    fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?> = emptyMap(), think: Boolean = true): Reply
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
     * - HTTP 400s: newer OpenAI models want max_completion_tokens and only the default temperature; any other parameter
     *   the server names as unknown/unsupported (e.g. top_k from Extra body on Azure) is dropped from then on.
     * - Thinking models (hidden reasoning, <think>, far more tokens than the answer): think = false calls send one of the
     *   thinking-off switches in OFF, starting from a hint for known hosts, moving on when one is rejected or ignored.
     *   Thinking calls get 2,048 output tokens, raised once to 4,096.
     */
    private class Quirks(var off: Int) { var tokenKey = "max_tokens"; var sendTemp = true; var thinks = false; var budget = 2048; var offOk = false; val drop = HashSet<String>() }
    internal companion object {
        private val learned = HashMap<String, Quirks>()
        private fun o(k: String, v: Any) = JSONObject().put(k, v)
        val OFF: List<Pair<String, Any>> = listOf(
            "reasoning_effort" to "none", // OpenAI, Azure OpenAI, Fireworks, Groq, Gemini
            "thinking" to o("type", "disabled"), // DeepSeek V4, Z.ai GLM, Kimi, MiniMax, Volcengine, Anthropic-style
            "chat_template_kwargs" to o("enable_thinking", false), // vLLM, SGLang, llama.cpp, LM Studio
            "enable_thinking" to false, // Alibaba DashScope (Qwen), SiliconFlow
            "reasoning" to o("enabled", false), // OpenRouter
            "think" to false, // Ollama
            "reasoning_effort" to "minimal", // OpenAI/Azure gpt-5 (no "none")
            "reasoning_effort" to "low") // o-series and other models that can't switch off
        /** Where to start in OFF for a host, so known providers get their switch on the first try. */
        fun hint(host: String) = when {
            host.contains("deepseek") || host.contains("bigmodel") || host.contains("z.ai") || host.contains("moonshot") || host.contains("volces") -> 1
            host.contains("dashscope") || host.contains("aliyuncs") || host.contains("siliconflow") -> 3
            host.contains("openrouter") -> 4
            host.startsWith("localhost") || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local") -> 2
            else -> 0
        }
    }
    private val q = synchronized(learned) { learned.getOrPut("$url|${ep.model}") { Quirks(hint(runCatching { URL(base).host.lowercase() }.getOrDefault(""))) } }

    /** Parameters this server rejected and that are no longer sent (shown by Test connection). */
    val dropped: Set<String> get() = q.drop
    /** The thinking-off switch that worked here, e.g. reasoning_effort=none, or null. */
    val offSwitch: String? get() = if (q.offOk && q.off < OFF.size) OFF[q.off].let { "${it.first}=${it.second}" } else null

    /** True once this endpoint has shown it is a reasoning ("thinking") model: it works, but slower. */
    val thinks get() = q.thinks

    override fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?>, think: Boolean): Reply {
        var attempt = 0; var adapts = 0
        while (true) {
            val off = !think && q.thinks && q.off < OFF.size
            val body = JSONObject().put("model", ep.model).put(q.tokenKey, if (q.thinks) maxOf(maxTokens, q.budget) else maxTokens)
                .put("messages", JSONArray().also { a -> msgs.forEach { a.put(msgJson(it)) } })
            if (q.sendTemp) body.put("temperature", temperature)
            if (off) OFF[q.off].let { body.put(it.first, it.second) }
            extraBody?.let { e -> e.keys().forEach { body.put(it, e.get(it)) } } // the user's Extra body wins
            extra.forEach { (k, v) -> body.put(k, json(v)) }
            q.drop.forEach { body.remove(it) }
            try {
                val wasThinking = q.thinks
                val (r, cutOff, thought) = post(body.toString().toByteArray(), if (q.thinks) 90_000 else 30_000)
                if (thought) { if (off) { q.off++; q.offOk = false }; q.thinks = true } // that switch didn't stop the thinking: try the next
                else if (off) q.offOk = true
                if (!cutOff || adapts++ >= 3) return r
                if (!think && q.off < OFF.size) continue // retry straight away with a thinking-off switch
                if (wasThinking) q.budget = minOf(q.budget * 2, 4096)
            } catch (e: ApiError) {
                if (e.code == 404 && !triedV1 && runCatching { URL(base).path.isEmpty() }.getOrDefault(false)) { // "http://host:8000" without /v1
                    triedV1 = true; url = "$base/v1/chat/completions"; continue
                }
                if ((e.code == 400 || e.code == 422) && adapts++ < 12) {
                    val m = e.message.orEmpty()
                    if (adapt(m)) continue
                    val bad = rejected(m)
                    if (off && (bad == null || bad == OFF[q.off].first)) { q.off++; continue } // the thinking switch was rejected: next one
                    if (bad != null) { q.drop += bad; continue } // e.g. "Unknown parameter: 'top_k'": stop sending it
                }
                if (!e.retryable || attempt >= waits.size) throw e
                Thread.sleep(waits[attempt++])
            }
        }
    }

    private fun adapt(m: String) = when {
        q.tokenKey == "max_tokens" && m.contains("max_completion_tokens") -> { q.tokenKey = "max_completion_tokens"; true }
        q.sendTemp && m.contains("temperature") -> { q.sendTemp = false; true }
        else -> false
    }

    /** The parameter a 400 complains about, if the error names one we send. Model and messages are never dropped. */
    private fun rejected(m: String): String? {
        val low = m.lowercase()
        if (listOf("unknown", "unrecognized", "unsupported", "not supported", "not permitted", "not allowed", "extra", "invalid").none { low.contains(it) }) return null
        val sent = listOf("top_k", "top_p", "min_p", "repetition_penalty", "frequency_penalty", "presence_penalty", "seed", "stop",
            "structured_outputs") + OFF.map { it.first } + (extraBody?.keys()?.asSequence()?.toList() ?: emptyList())
        fun at(k: String) = listOf("'$k'", "\"$k\"", "`$k`", " $k ", " $k.", " $k,", ": $k").map { m.indexOf(it) }.filter { it >= 0 }.minOrNull()
            ?: if (m.endsWith(" $k")) m.length - k.length else null
        // The parameter named first: "Unknown parameter 'top_k'. Did you mean 'top_p'?" means top_k.
        return sent.distinct().filter { it !in q.drop && it != "model" && it != "messages" }.mapNotNull { k -> at(k)?.let { k to it } }.minByOrNull { it.second }?.first
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

    /** One HTTP call -> (reply, cut off: nothing visible because it ran out of tokens or only reasoned, the model thought). */
    private fun post(bytes: ByteArray, readMs: Int): Triple<Reply, Boolean, Boolean> {
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
            val out = u?.optInt("completion_tokens", -1) ?: -1
            val thought = reasoned || content.substringAfter("<think>", "").substringBefore("</think>").isNotBlank() ||
                (out > 64 && out > 4 * (visible.length / 3.5) + 32) // far more tokens than the visible answer: hidden reasoning
            return Triple(Reply(content, u?.optInt("prompt_tokens", -1) ?: -1, out, System.currentTimeMillis() - t0), cutOff, thought)
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
