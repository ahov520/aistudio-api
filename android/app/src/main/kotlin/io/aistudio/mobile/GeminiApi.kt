package io.aistudio.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ChatMessage(val role: String, val text: String)
class GeminiApi(base: String, private val key: String) {
    private val root = normalize(base)
    @Volatile private var active: HttpURLConnection? = null
    companion object {
        fun normalize(raw: String): String {
            val base = raw.trim().trimEnd('/').removeSuffix("/v1beta")
            val u = URI(base)
            require(u.host != null && u.userInfo == null && u.query == null && u.fragment == null) { "API 地址无效" }
            require(u.scheme == "https" || (u.scheme == "http" && u.host in listOf("127.0.0.1", "localhost"))) { "仅支持 HTTPS 或本机 HTTP" }
            return base
        }
    }
    fun cancel() { active?.disconnect() }
    private fun open(path: String): HttpURLConnection {
        val c = URL(root + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.setRequestProperty("Accept", "application/json")
        if (key.isNotBlank()) c.setRequestProperty("x-goog-api-key", key)
        active = c
        return c
    }
    private fun check(c: HttpURLConnection) {
        val status = c.responseCode
        if (status !in 200..299) {
            val error = c.errorStream?.bufferedReader()?.use { it.readText().take(300) } ?: ""
            val description = runCatching { JSONObject(error).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("HTTP " + status + ": " + (description ?: "请求失败").replace(key.takeIf { it.isNotEmpty() } ?: "\u0000", "…"))
        }
    }
    fun listModels(): List<String> {
        val names = linkedSetOf<String>()
        var page = ""
        do {
            val c = open("/v1beta/models" + if (page.isEmpty()) "" else "?pageToken=" + java.net.URLEncoder.encode(page, "UTF-8"))
            try {
                check(c)
                val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val models = result.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
                    m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
                val next = result.optString("nextPageToken")
                if (next == page) break
                page = next
            } finally { c.disconnect(); active = null }
        } while (page.isNotEmpty())
        return names.toList()
    }
    fun stream(model: String, messages: List<ChatMessage>, onText: (String) -> Unit) {
        val id = model.removePrefix("models/")
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "请填写有效模型名称" }
        val body = JSONObject().put("contents", JSONArray().apply {
            messages.forEach { m -> put(JSONObject().put("role", m.role).put("parts", JSONArray().put(JSONObject().put("text", m.text)))) }
        })
        val c = open("/v1beta/models/" + id + ":streamGenerateContent?alt=sse")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(c)
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.substring(5).trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach
                    val obj = JSONObject(data)
                    if (obj.has("error")) throw IllegalStateException("上游返回错误")
                    val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
                    val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (!p.optBoolean("thought")) p.optString("text").takeIf { it.isNotEmpty() }?.let(onText)
                    }
                }
            }
        } finally { c.disconnect(); active = null }
    }
}
