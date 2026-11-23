package app.extralice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("extralice", Context.MODE_PRIVATE)
    private fun s(k: String, d: String = "") = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    var oauth: String get() = s("oauth"); set(v) = put("oauth", v)
    var geminiKey: String get() = s("gkey"); set(v) = put("gkey", v)
    var geminiModel: String get() = s("gmodel", "gemini-2.5-flash"); set(v) = put("gmodel", v)
    var systemPrompt: String
        get() = s("sys", "Отвечай по-русски, кратко (до 3-4 предложений), простым разговорным языком, без markdown, списков и эмодзи.")
        set(v) = put("sys", v)
    var lastDeviceId: String get() = s("dev"); set(v) = put("dev", v)
    var lastIp: String get() = s("ip"); set(v) = put("ip", v)
    var history: List<String>
        get() = JSONArray(s("hist", "[]")).let { a -> List(a.length()) { a.getString(it) } }
        set(v) = put("hist", JSONArray(v).toString())
}

data class Dev(val id: String, val platform: String, val name: String)

object Yandex {
    private val http = OkHttpClient()
    private fun get(url: String, token: String): JSONObject {
        val r = Request.Builder().url(url).header("Authorization", "OAuth $token").build()
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Яндекс: HTTP ${resp.code}")
            return JSONObject(body)
        }
    }
    fun devices(token: String): List<Dev> {
        val a = get("https://quasar.yandex.net/glagol/device_list", token).getJSONArray("devices")
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Dev(o.getString("id"), o.getString("platform"), o.optString("name", o.getString("id")))
        }
    }
    fun glagolToken(token: String, d: Dev): String =
        get("https://quasar.yandex.net/glagol/token?device_id=${d.id}&platform=${d.platform}", token)
            .getString("token")
}

object Gemini {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    fun ask(key: String, model: String, system: String, turns: List<Pair<String, String>>): String {
        val contents = JSONArray()
        turns.forEach { (role, text) ->
            contents.put(JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject().put("contents", contents)
        if (system.isNotBlank()) body.put("systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val m = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP ${r.code}")
                throw RuntimeException("Gemini: $m")
            }
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return List(parts.length()) { parts.getJSONObject(it).optString("text") }.joinToString("").trim()
        }
    }
}

fun plain(t: String) = t.replace(Regex("[*#`_>]"), "").replace(Regex("\\s+"), " ").trim()

fun chunks(t: String, max: Int = 180): List<String> {
    val sents = t.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
    val out = mutableListOf<String>()
    var cur = ""
    for (s in sents) {
        if (cur.isNotEmpty() && cur.length + s.length + 1 > max) { out += cur; cur = "" }
        var rest = s
        while (rest.length > max) {
            val i = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            out += rest.substring(0, i).trim()
            rest = rest.substring(i).trim()
        }
        cur = if (cur.isEmpty()) rest else "$cur $rest"
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

class Station(
    ip: String, port: Int, private val token: String,
    private val onEvent: (String) -> Unit
) {
    @Volatile var alice = "IDLE"
    private val host = if (ip.contains(':')) "[$ip]" else ip
    private val url = "wss://$host:$port"
    private var ws: WebSocket? = null

    companion object {
        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        // Станция использует самоподписанный сертификат, поэтому проверка отключена только для неё.
        private val client: OkHttpClient by lazy {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    fun connect() {
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                cmd("ping"); onEvent("open")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    JSONObject(text).optJSONObject("state")?.let { alice = it.optString("aliceState", alice) }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEvent("closed")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onEvent("fail:" + (t.message ?: ""))
        })
    }

    fun close() { ws?.close(1000, null); ws = null }

    private fun send(payload: JSONObject) {
        val m = JSONObject().put("conversationToken", token).put("id", UUID.randomUUID().toString())
            .put("payload", payload).put("sentTime", System.currentTimeMillis())
        ws?.send(m.toString())
    }
    fun sendText(t: String) = send(JSONObject().put("command", "sendText").put("text", t))
    fun volume(v: Double) = send(JSONObject().put("command", "setVolume").put("volume", v))
    fun cmd(c: String) = send(JSONObject().put("command", c))

    suspend fun waitIdle(estimateMs: Long) {
        delay(500)
        var w = 0
        while (alice != "SPEAKING" && w < 30) { delay(100); w++ }
        if (alice != "SPEAKING") { delay(estimateMs); return }
        var s = 0
        while (alice == "SPEAKING" && s < 300) { delay(200); s++ }
    }

    /** Алиса просто произносит готовый текст кусками. */
    suspend fun say(text: String) {
        for (c in chunks(text)) {
            sendText("Повтори за мной $c")
            waitIdle(c.length * 70L + 1500)
        }
    }
}

class Discovery(ctx: Context, private val onFound: (String, String, Int) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("extralice")
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        runCatching { lock.setReferenceCounted(false); lock.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, c: Int) {}
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(this@Discovery) { queue.add(s) }
                next()
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_yandexio._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun next() {
        if (busy) return
        val s = queue.removeFirstOrNull() ?: return
        busy = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) = done()
            override fun onServiceResolved(i: NsdServiceInfo) {
                val id = i.attributes["deviceId"]?.toString(Charsets.UTF_8) ?: ""
                val host = i.host?.hostAddress ?: ""
                val port = i.port
                main.post { onFound(id, host, port) }
                done()
            }
        })
    }

    private fun done() { synchronized(this) { busy = false }; next() }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { if (lock.isHeld) lock.release() }
    }
}
