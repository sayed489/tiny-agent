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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64

/** Where to send requests. `extraBody` is a JSON object merged into every request body. */
data class Endpoint(val baseUrl: String, val key: String, val model: String, val extraBody: String = "") {
    override fun toString() = "Endpoint($baseUrl, model=$model)" // never print the key
}

data class Msg(val role: String, val text: String, val jpeg: ByteArray? = null)
data class Reply(val text: String, val promptTokens: Int, val completionTokens: Int, val ms: Long)
class ApiError(msg: String, val retryable: Boolean, val code: Int = 0) : Exception(msg)

interface ChatApi {
    fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?> = emptyMap()): Reply

    companion object {
        /** The single switch point: swap the implementation here to detach from OpenAI-compatible HTTP. */
        fun create(ep: Endpoint): ChatApi = OpenAiCompat(ep)
    }
}

/** OpenAI-compatible POST {baseUrl}/chat/completions. Keep-alive is reused across calls for speed. */
class OpenAiCompat(private val ep: Endpoint, private val waits: LongArray = longArrayOf(1000, 3000)) : ChatApi {
    private val url = ep.baseUrl.trim().trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
    private val extraBody: JSONObject? = ep.extraBody.trim().takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

    // Learned from HTTP 400s: newer OpenAI models want max_completion_tokens and only the default temperature.
    private var tokenKey = "max_tokens"; private var sendTemp = true

    override fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?>): Reply {
        var attempt = 0
        while (true) {
            val body = JSONObject().put("model", ep.model).put(tokenKey, maxTokens)
                .put("messages", JSONArray().also { a -> msgs.forEach { a.put(msgJson(it)) } })
            if (sendTemp) body.put("temperature", temperature)
            extraBody?.let { e -> e.keys().forEach { body.put(it, e.get(it)) } }
            extra.forEach { (k, v) -> body.put(k, json(v)) }
            try {
                return post(body.toString().toByteArray())
            } catch (e: ApiError) {
                if (e.code == 400 && adapt(e.message.orEmpty())) continue
                if (!e.retryable || attempt >= waits.size) throw e
                Thread.sleep(waits[attempt++])
            }
        }
    }

    private fun adapt(m: String) = when {
        tokenKey == "max_tokens" && m.contains("max_completion_tokens") -> { tokenKey = "max_completion_tokens"; true }
        sendTemp && m.contains("temperature") -> { sendTemp = false; true }
        else -> false
    }

    /** The provider's own error message when the body is OpenAI-style JSON, else the raw text. */
    private fun errText(t: String) = try { JSONObject(t).getJSONObject("error").getString("message") } catch (e: JSONException) { t }.trim().take(200)

    private fun post(bytes: ByteArray): Reply {
        val t0 = System.currentTimeMillis()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (ep.key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${ep.key}")
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (code >= 400) throw ApiError("HTTP $code ${errText(text)}", code == 429 || code >= 500, code)
            val j = JSONObject(text)
            val m = j.getJSONArray("choices").getJSONObject(0).optJSONObject("message")
            val content = if (m == null || m.isNull("content")) "" else m.getString("content")
            val u = j.optJSONObject("usage")
            return Reply(content, u?.optInt("prompt_tokens", -1) ?: -1, u?.optInt("completion_tokens", -1) ?: -1,
                System.currentTimeMillis() - t0)
        } catch (e: SocketTimeoutException) {
            throw ApiError("timeout", true)
        } catch (e: JSONException) {
            throw ApiError("bad JSON reply", false)
        } catch (e: IOException) {
            throw ApiError("network: ${e.message}", true)
        }
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
