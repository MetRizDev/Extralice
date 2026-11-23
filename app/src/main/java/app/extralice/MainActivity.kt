package app.extralice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*

val Orange = Color(0xFFFF6A00)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Orange, onPrimary = Color.White,
                    secondaryContainer = Color(0xFFFFE0CC),
                    background = Color.White, surface = Color.White
                )
            ) { Surface(color = Color.White) { App() } }
        }
    }
}

class Ctl(private val ctx: Context, private val scope: CoroutineScope) {
    val store = Store(ctx)
    var oauth by mutableStateOf(store.oauth)
    var devices by mutableStateOf(listOf<Dev>())
    var selected by mutableStateOf<Dev?>(null)
    val found = mutableStateMapOf<String, Pair<String, Int>>()
    var manualIp by mutableStateOf(store.lastIp)
    var station by mutableStateOf<Station?>(null)
    var status by mutableStateOf("Не подключено")
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var volume by mutableFloatStateOf(0.5f)
    var history by mutableStateOf(store.history)
    val chat = mutableStateListOf<Pair<String, String>>()
    var thinking by mutableStateOf(false)
    private var speakJob: Job? = null

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun setToken(t: String) { store.oauth = t; oauth = t; loadDevices() }
    fun logout() { disconnect(); store.oauth = ""; oauth = ""; devices = listOf(); selected = null }

    fun loadDevices() = scope.launch {
        busy = true
        try {
            devices = withContext(Dispatchers.IO) { Yandex.devices(oauth) }
            selected = devices.find { it.id == store.lastDeviceId } ?: devices.firstOrNull()
            if (devices.isEmpty()) status = "В аккаунте нет колонок"
        } catch (e: Exception) { status = "Ошибка: ${e.message}" } finally { busy = false }
    }

    fun connect() {
        val d = selected ?: return
        val hp = found[d.id] ?: manualIp.takeIf { it.isNotBlank() }?.let { it.trim() to 1961 }
        if (hp == null) { status = "Станция не найдена в сети — введите её IP вручную"; return }
        scope.launch {
            busy = true
            try {
                val tok = withContext(Dispatchers.IO) { Yandex.glagolToken(oauth, d) }
                station?.close()
                val st = Station(hp.first, hp.second, tok) { ev ->
                    when {
                        ev == "open" -> { connected = true; status = "Подключено: ${d.name}" }
                        ev == "closed" -> { connected = false; status = "Соединение закрыто" }
                        ev.startsWith("fail") -> { connected = false; status = "Нет связи с колонкой (${ev.removePrefix("fail:")})" }
                    }
                }
                station = st
                store.lastDeviceId = d.id
                if (manualIp.isNotBlank()) store.lastIp = manualIp
                status = "Подключаюсь…"
                st.connect()
            } catch (e: Exception) { status = "Ошибка: ${e.message}" } finally { busy = false }
        }
    }

    fun disconnect() { speakJob?.cancel(); station?.close(); station = null; connected = false; status = "Не подключено" }

    fun speak(text: String, question: String? = null) {
        val st = station
        if (st == null || !connected) { toast("Сначала подключитесь к станции"); return }
        val t = plain(text)
        if (t.isBlank()) return
        history = (listOf(text) + history.filter { it != text }).take(20); store.history = history
        speakJob?.cancel()
        speakJob = scope.launch {
            try {
                if (!question.isNullOrBlank()) { st.sendText(question); st.waitIdle(4000) }
                st.say(t)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast("Ошибка: ${e.message}") }
        }
    }

    fun stopSpeaking() { speakJob?.cancel(); station?.cmd("stop") }

    fun askGemini(q: String, speak: Boolean) {
        if (store.geminiKey.isBlank()) { toast("Сначала введите ключ Gemini во вкладке «Настройки»"); return }
        chat.add("user" to q)
        thinking = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    Gemini.ask(store.geminiKey, store.geminiModel, store.systemPrompt, chat.takeLast(12))
                }
                chat.add("model" to r)
                thinking = false
                // Отвечает Gemini, Алиса лишь произносит его текст
                if (speak && connected) speak(r)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                chat.removeLastOrNull(); toast(e.message ?: "Ошибка")
            } finally { thinking = false }
        }
    }
}

@Composable
fun Logo(s: Dp = 40.dp) = Canvas(Modifier.size(s)) {
    val w = size.minDimension
    drawCircle(Orange, radius = w * 0.36f, style = Stroke(width = w * 0.16f))
    drawCircle(Orange, radius = w * 0.10f)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { Ctl(ctx.applicationContext, scope) }
    var tab by remember { mutableIntStateOf(0) }
    var login by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val d = Discovery(ctx) { id, host, port -> c.found[id.ifBlank { host }] = host to port }
        d.start()
        if (c.oauth.isNotBlank()) c.loadDevices()
        onDispose { d.stop() }
    }

    if (login) {
        LoginScreen(onToken = { login = false; c.setToken(it) }, onCancel = { login = false })
        return
    }

    Scaffold(bottomBar = {
        NavigationBar(containerColor = Color.White) {
            val names = listOf("Станция", "Сказать", "Gemini", "Настройки")
            val icons = listOf("📻", "💬", "✨", "⚙️")
            names.forEachIndexed { i, n ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(icons[i]) }, label = { Text(n, maxLines = 1) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Extralice", style = MaterialTheme.typography.titleLarge, color = Orange)
                    Text(c.status, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (tab) {
                0 -> StationTab(c) { login = true }
                1 -> SpeakTab(c)
                2 -> GeminiTab(c)
                else -> SettingsTab(c)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onToken: (String) -> Unit, onCancel: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("← Назад") }
            Text("Вход в Яндекс")
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                fun check(u: String?): Boolean {
                    val m = Regex("access_token=([^&]+)").find(u ?: "") ?: return false
                    if (!done) { done = true; onToken(m.groupValues[1]) }
                    return true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = check(r.url.toString())
                    override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { check(u) }
                }
                loadUrl("https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d")
            }
        })
    }
}

@Composable
fun StationTab(c: Ctl, onLogin: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Желательно минимум Станция Миди — на более простых колонках большинство функций не будет работать.",
            style = MaterialTheme.typography.bodySmall)
        if (c.oauth.isBlank()) {
            Text("Войдите в Яндекс-аккаунт, к которому привязана колонка. Телефон и колонка должны быть в одной Wi-Fi сети.")
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Войти в Яндекс") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.loadDevices() }, enabled = !c.busy) { Text("Обновить список колонок") }
            }
            c.devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = c.selected == d, onClick = { c.selected = d })
                    Column {
                        Text(d.name)
                        val ip = c.found[d.id]?.first
                        Text(if (ip != null) "Найдена в сети: $ip" else "В сети пока не найдена",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(value = c.manualIp, onValueChange = { c.manualIp = it },
                label = { Text("IP колонки (если не нашлась сама)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.connect() }, enabled = !c.busy && c.selected != null) { Text("Подключиться") }
                if (c.connected) OutlinedButton(onClick = { c.disconnect() }) { Text("Отключить") }
            }
        }

        if (c.connected) {
            HorizontalDivider()
            Text("Громкость", style = MaterialTheme.typography.titleSmall)
            Slider(value = c.volume, onValueChange = { c.volume = it },
                onValueChangeFinished = { c.station?.volume(c.volume.toDouble()) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("⏮" to "prev", "▶" to "play", "⏸" to "stop", "⏭" to "next").forEach { (l, cmd) ->
                    OutlinedButton(onClick = { c.station?.cmd(cmd) }) { Text(l) }
                }
            }
            Text("Быстрые команды Алисе", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Погода" to "какая погода", "Время" to "который час",
                "Анекдот" to "расскажи анекдот", "Факт" to "расскажи интересный факт",
                "Музыка" to "включи музыку", "Сказка" to "расскажи сказку"
            ).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (l, p) ->
                        OutlinedButton(onClick = { c.station?.sendText(p) }, modifier = Modifier.weight(1f)) { Text(l) }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeakTab(c: Ctl) {
    var text by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var afterQ by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Что должна сказать Алиса") },
            modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = afterQ, onCheckedChange = { afterQ = it })
            Spacer(Modifier.width(8.dp))
            Text("Сказать после вопроса")
        }
        if (afterQ) OutlinedTextField(value = question, onValueChange = { question = it },
            label = { Text("Вопрос Алисе (она ответит, потом скажет ваш текст)") },
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.speak(text, if (afterQ) question else null) }) { Text("Сказать") }
            OutlinedButton(onClick = { clip.getText()?.text?.let { text = it } }) { Text("Из буфера") }
            OutlinedButton(onClick = { c.stopSpeaking() }) { Text("Стоп") }
        }
        if (c.history.isNotEmpty()) {
            Text("История", style = MaterialTheme.typography.titleSmall)
            c.history.forEach { h ->
                TextButton(onClick = { text = h }, modifier = Modifier.fillMaxWidth()) {
                    Text(h, maxLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun GeminiTab(c: Ctl) {
    var q by remember { mutableStateOf("") }
    var speak by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.chat.isEmpty()) Text("Задайте вопрос — ответит Gemini, а Алиса его озвучит.",
                style = MaterialTheme.typography.bodySmall)
            c.chat.forEach { (role, t) ->
                Text(if (role == "user") "Вы: $t" else "Gemini: $t")
            }
            if (c.thinking) Text("Gemini думает…")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = speak, onCheckedChange = { speak = it })
            Spacer(Modifier.width(8.dp))
            Text("Озвучивать на станции")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { c.chat.clear() }) { Text("Очистить") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Вопрос") },
                modifier = Modifier.weight(1f))
            Button(onClick = { if (q.isNotBlank()) { c.askGemini(q, speak); q = "" } }, enabled = !c.thinking) { Text("➤") }
        }
        OutlinedButton(onClick = { c.stopSpeaking() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить озвучку") }
    }
}

@Composable
fun SettingsTab(c: Ctl) {
    var key by remember { mutableStateOf(c.store.geminiKey) }
    var model by remember { mutableStateOf(c.store.geminiModel) }
    var sys by remember { mutableStateOf(c.store.systemPrompt) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("API-ключ Gemini можно получить бесплатно на aistudio.google.com/apikey",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API-ключ Gemini") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Модель") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sys, onValueChange = { sys = it }, label = { Text("Инструкция для Gemini") },
            modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(onClick = {
            c.store.geminiKey = key.trim(); c.store.geminiModel = model.trim(); c.store.systemPrompt = sys
            c.toast("Сохранено")
        }) { Text("Сохранить") }
        if (c.oauth.isNotBlank()) OutlinedButton(onClick = { c.logout() }) { Text("Выйти из Яндекса") }
    }
}
