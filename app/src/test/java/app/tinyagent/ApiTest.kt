package app.tinyagent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/** Exercises the real HttpURLConnection client against a minimal local HTTP/1.1 server. */
class ApiTest {
    private class Hit(val path: String, val auth: String?, val body: JSONObject)
    private val hits = java.util.Collections.synchronizedList(ArrayList<Hit>())
    private val responses = java.util.concurrent.LinkedBlockingDeque<Pair<Int, String>>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val base = "http://127.0.0.1:${server.localPort}/v1"

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                s.use {
                    val inp = it.getInputStream()
                    val head = readHead(inp).split("\r\n")
                    val headers = head.drop(1).associate { h -> h.substringBefore(':').lowercase() to h.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var o = 0; while (o < b.size) o += inp.read(b, o, b.size - o) }
                    hits += Hit(head[0].split(' ')[1], headers["authorization"], JSONObject(body.decodeToString()))
                    val (code, text) = responses.pollFirst() ?: (200 to ok("fallback"))
                    val bytes = text.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun readHead(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (!b.toString().endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) break; b.write(c) }
        return b.toString().trimEnd()
    }

    private fun ok(content: String?, prompt: Int = 123, completion: Int = 4) = JSONObject()
        .put("choices", listOf(mapOf("message" to mapOf("role" to "assistant", "content" to (content ?: JSONObject.NULL)))))
        .put("usage", mapOf("prompt_tokens" to prompt, "completion_tokens" to completion)).toString()

    @After fun stop() = server.close()

    @Test fun sendsOpenAiRequestAndParsesReply() {
        responses.add(200 to ok("tap 3"))
        val api = OpenAiCompat(Endpoint("$base/", "sk-test", "qwen3.5-0.8b", """{"chat_template_kwargs":{"enable_thinking":false},"top_p":0.9}"""))
        val r = api.chat(listOf(Msg("system", "SYS"), Msg("user", "USER")), 0.1, 24,
            mapOf("structured_outputs" to mapOf("choice" to listOf("Y", "N"))))
        assertEquals("tap 3", r.text); assertEquals(123, r.promptTokens); assertEquals(4, r.completionTokens)
        val h = hits.single()
        assertEquals("/v1/chat/completions", h.path)
        assertEquals("Bearer sk-test", h.auth)
        assertEquals("qwen3.5-0.8b", h.body.getString("model"))
        assertEquals(24, h.body.getInt("max_tokens"))
        assertEquals(0.1, h.body.getDouble("temperature"), 1e-9)
        assertEquals(false, h.body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertEquals(0.9, h.body.getDouble("top_p"), 1e-9)
        assertEquals("Y", h.body.getJSONObject("structured_outputs").getJSONArray("choice").getString(0))
        val msgs = h.body.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role")); assertEquals("SYS", msgs.getJSONObject(0).getString("content"))
        assertTrue(!h.body.has("stop"))
    }

    @Test fun localServerWithoutKeySendsNoAuthHeader() {
        responses.add(200 to ok("Y"))
        OpenAiCompat(Endpoint("$base/chat/completions", "", "local")).chat(listOf(Msg("user", "hi")), 0.1, 2)
        assertNull(hits.single().auth)
        assertEquals("/v1/chat/completions", hits.single().path)
    }

    @Test fun imageSentAsDataUrl() {
        responses.add(200 to ok("tap 1"))
        OpenAiCompat(Endpoint(base, "k", "vl")).chat(listOf(Msg("user", "Step: x", byteArrayOf(1, 2, 3))), 0.1, 12)
        val content = hits.single().body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun retriesTwiceOn5xxAnd429() {
        responses.addAll(listOf(500 to "oops", 429 to "slow down", 200 to ok("key back")))
        val r = OpenAiCompat(Endpoint(base, "k", "m"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24)
        assertEquals("key back", r.text)
        assertEquals(3, hits.size)
    }

    @Test fun givesUpAfterTwoRetries() {
        responses.addAll(listOf(503 to "a", 503 to "b", 503 to "c", 200 to ok("late")))
        try { OpenAiCompat(Endpoint(base, "k", "m"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24); fail() }
        catch (e: ApiError) { assertTrue(e.retryable); assertTrue(e.message!!.contains("503")) }
        assertEquals(3, hits.size)
    }

    @Test fun clientErrorIsNotRetriedAndKeyIsNotLeaked() {
        responses.add(401 to "bad key")
        try { OpenAiCompat(Endpoint(base, "sk-secret", "m"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24); fail() }
        catch (e: ApiError) { assertTrue(!e.retryable); assertTrue(!e.message!!.contains("sk-secret")) }
        assertEquals(1, hits.size)
        assertTrue(!Endpoint(base, "sk-secret", "m").toString().contains("sk-secret"))
    }

    @Test fun adaptsToNewerOpenAiModels() {
        responses.addAll(listOf(
            400 to """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""",
            400 to """{"error":{"message":"Unsupported value: 'temperature' does not support 0.1 with this model."}}""",
            200 to ok("OK"), 200 to ok("again")))
        val api = OpenAiCompat(Endpoint(base, "k", "gpt-5-mini"), longArrayOf(1, 1))
        assertEquals("OK", api.chat(listOf(Msg("user", "u")), 0.1, 24).text)
        assertEquals(3, hits.size)
        hits.last().body.let { assertTrue(it.getInt("max_completion_tokens") == 24 && !it.has("max_tokens") && !it.has("temperature")) }
        assertEquals("again", api.chat(listOf(Msg("user", "u")), 0.1, 24).text) // learned: no failed round trips this time
        assertEquals(4, hits.size)
    }

    @Test fun otherBadRequestsFailOnceWithTheProvidersMessage() {
        responses.add(401 to """{"error":{"message":"Incorrect API key provided: sk-abc***."}}""")
        try { OpenAiCompat(Endpoint(base, "sk-abc", "m"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24); fail() }
        catch (e: ApiError) { assertEquals("HTTP 401 Incorrect API key provided: sk-abc***.", e.message); assertEquals(401, e.code) }
        responses.add(400 to """{"error":{"message":"The model `nope` does not exist"}}""")
        try { OpenAiCompat(Endpoint(base, "k", "nope"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24); fail() }
        catch (e: ApiError) { assertTrue(e.message!!.contains("does not exist")) }
        assertEquals(2, hits.size)
    }

    private fun emptyCutOff() = JSONObject()
        .put("choices", listOf(mapOf("message" to mapOf("content" to ""), "finish_reason" to "length")))
        .put("usage", mapOf("prompt_tokens" to 100, "completion_tokens" to 24, "completion_tokens_details" to mapOf("reasoning_tokens" to 24))).toString()

    @Test fun reasoningModelGetsBiggerBudgetAndLowEffort() {
        responses.addAll(listOf(
            200 to emptyCutOff(), // spent all 24 tokens thinking, nothing visible
            400 to """{"error":{"message":"Unsupported value: 'reasoning_effort' does not support 'low' with this model."}}""",
            200 to ok("tap 2"), 200 to ok("again"), 200 to ok("shared")))
        val api = OpenAiCompat(Endpoint(base, "k", "thinker"), longArrayOf(1, 1))
        assertEquals("tap 2", api.chat(listOf(Msg("user", "u")), 0.1, 24).text)
        assertTrue(api.thinks)
        assertEquals(24, hits[0].body.getInt("max_tokens")); assertTrue(!hits[0].body.has("reasoning_effort"))
        assertEquals(2048, hits[1].body.getInt("max_tokens")); assertEquals("low", hits[1].body.getString("reasoning_effort"))
        assertEquals(2048, hits[2].body.getInt("max_tokens")); assertEquals("none", hits[2].body.getString("reasoning_effort"))
        assertEquals("again", api.chat(listOf(Msg("user", "u")), 0.1, 2).text) // learned: one round trip
        assertEquals("none", hits[3].body.getString("reasoning_effort"))
        assertEquals("shared", OpenAiCompat(Endpoint(base, "k", "thinker")).chat(listOf(Msg("user", "u")), 0.1, 2).text) // next task too
        assertEquals(2048, hits[4].body.getInt("max_tokens"))
        assertEquals(5, hits.size)
    }

    @Test fun thinkTagsCutOffCountAsReasoning() {
        responses.addAll(listOf(200 to ok("<think>The user wants me to open"), 200 to ok("<think>ok</think>open YouTube")))
        val api = OpenAiCompat(Endpoint(base, "", "qwen3-local"))
        assertEquals("<think>ok</think>open YouTube", api.chat(listOf(Msg("user", "u")), 0.1, 24).text)
        assertEquals(2048, hits[1].body.getInt("max_tokens"))
    }

    @Test fun plainEmptyReplyIsNotReasoning() {
        responses.add(200 to ok(""))
        val api = OpenAiCompat(Endpoint(base, "k", "plain"))
        assertEquals("", api.chat(listOf(Msg("user", "u")), 0.1, 24).text)
        assertTrue(!api.thinks); assertEquals(1, hits.size)
    }

    @Test fun nullContentAndMissingUsage() {
        responses.add(200 to JSONObject().put("choices", listOf(mapOf("message" to mapOf("content" to JSONObject.NULL)))).toString())
        val r = OpenAiCompat(Endpoint(base, "k", "m")).chat(listOf(Msg("user", "u")), 0.1, 24)
        assertEquals("", r.text); assertEquals(-1, r.promptTokens)
    }

    @Test fun connectionRefusedIsRetryable() {
        server.close()
        try { OpenAiCompat(Endpoint(base, "k", "m"), longArrayOf(1, 1)).chat(listOf(Msg("user", "u")), 0.1, 24); fail() }
        catch (e: ApiError) { assertTrue(e.retryable) }
    }
}
