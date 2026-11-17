package io.aistudio.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFB8C5FF)) else lightColorScheme(primary = Color(0xFF4256B4))) {
                Surface(modifier = Modifier.fillMaxSize()) { Studio(settings, this) }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Studio(settings: Settings, context: Context) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(settings.base) }
    var key by remember { mutableStateOf(settings.readSecret("api_key")) }
    var model by remember { mutableStateOf(settings.model) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var messageBusy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var gatewayState by remember { mutableStateOf(LocalEngine.status()) }
    var active by remember { mutableStateOf<GeminiApi?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    fun save(): Boolean = try {
        base = GeminiApi.normalize(base)
        settings.base = base; settings.model = model.trim(); settings.writeSecret("api_key", key)
        true
    } catch (e: Exception) { notice = e.message ?: "保存失败"; false }
    fun loadModels() {
        if (loading || messageBusy || !save()) return
        loading = true; notice = ""
        val endpoint = base; val secret = key
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { GeminiApi(endpoint, secret).listModels() }
                models = result
                if (model !in result && result.isNotEmpty()) { model = result.first(); settings.model = model }
                notice = if (result.isEmpty()) "服务没有返回可用模型" else "已连接 · " + result.size + " 个模型"
            } catch (e: Exception) { notice = e.message ?: "连接失败" }
            finally { loading = false }
        }
    }
    fun send() {
        val prompt = query.trim()
        if (prompt.isEmpty() || messageBusy || loading) return
        if (model.isBlank()) { notice = "请先在连接页选择模型"; tab = 1; return }
        if (!save()) return
        val api = GeminiApi(base, key)
        active = api; notice = ""; draft = ""; messageBusy = true
        messages.add(ChatMessage("user", prompt)); query = ""
        val history = messages.toList()
        val chosen = model
        requestJob = scope.launch {
            val answer = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    api.stream(chosen, history) { part ->
                        answer.append(part)
                        val snapshot = answer.toString()
                        scope.launch { if (active === api) draft = snapshot }
                    }
                }
                if (answer.isNotEmpty()) messages.add(ChatMessage("model", answer.toString()))
                else { messages.removeAt(messages.lastIndex); query = prompt; notice = "没有收到文本响应，请检查模型或上游状态" }
            } catch (e: Exception) {
                if (messages.lastOrNull()?.role == "user") messages.removeAt(messages.lastIndex)
                query = prompt
                if (e !is kotlinx.coroutines.CancellationException) notice = e.message ?: "请求失败"
            } finally { active = null; draft = ""; messageBusy = false }
        }
    }
    LaunchedEffect(Unit) { while (true) { gatewayState = LocalEngine.status(); delay(1000) } }
    LaunchedEffect(messages.size, draft) {
        if (messages.isNotEmpty() || draft.isNotBlank()) listState.scrollToItem((messages.size + if (draft.isNotEmpty()) 1 else 0) - 1)
    }
    Scaffold(
        modifier = Modifier.systemBarsPadding().imePadding(),
        topBar = { TopAppBar(title = { Column { Text("Studio Mobile"); Text(if (model.isBlank()) "选择 Gemini 模型" else model, style = MaterialTheme.typography.labelSmall) } },
            actions = { TextButton(enabled = !messageBusy, onClick = { messages.clear(); draft = ""; notice = ""; tab = 0 }) { Text("新对话") } }) },
        bottomBar = { NavigationBar {
            listOf("对话" to "✦", "连接" to "⚙", "服务" to "◎").forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(item.second) }, label = { Text(item.first) })
            }
        } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            if (notice.isNotBlank()) {
                Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            }
            when (tab) {
                0 -> {
                    if (messages.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✦", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                        Text("今天想探索什么？", style = MaterialTheme.typography.headlineSmall)
                        Text("连接 Gemini 兼容 API，开始你的对话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { tab = 1 }) { Text("配置模型与 API") }
                    } else LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(messages) { msg -> MessageBubble(msg.role, msg.text) }
                        if (draft.isNotEmpty()) item { MessageBubble("model", draft) }
                    }
                    if (messageBusy && draft.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("输入消息…") }, shape = RoundedCornerShape(24.dp), maxLines = 5, enabled = !messageBusy)
                        Spacer(Modifier.width(8.dp))
                        if (messageBusy) Button(onClick = { val api = active; requestJob?.cancel(); scope.launch(Dispatchers.IO) { api?.cancel() } }) { Text("停止") }
                        else Button(onClick = { send() }, enabled = query.isNotBlank() && !loading) { Text("发送") }
                    }
                }
                1 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("API 连接", style = MaterialTheme.typography.headlineSmall)
                    Text("支持原项目的 Gemini v1beta 接口及官方 Gemini API。远程地址须使用 HTTPS。")
                    OutlinedTextField(base, { base = it }, label = { Text("API Base URL") }, singleLine = true, enabled = !loading && !messageBusy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading && !messageBusy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true, enabled = !loading && !messageBusy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loadModels() }, enabled = !loading && !messageBusy) { Text(if (loading) "连接中…" else "获取模型") }
                        OutlinedButton(onClick = { if (save()) notice = "配置已保存" }, enabled = !loading && !messageBusy) { Text("保存") }
                    }
                    models.forEach { id -> FilterChip(selected = model == id, onClick = { model = id; settings.model = id }, label = { Text(id) }, enabled = !messageBusy && !loading) }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("API 服务", style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (BuildConfig.EMBEDDED) gatewayState else "连接外部 aistudio-api", style = MaterialTheme.typography.titleLarge)
                        Text(if (BuildConfig.EMBEDDED) "实验性内置后端 · 127.0.0.1:8080" else "本版本不包含 Python 后端。请连接已启动的原项目后端，或使用官方 API。")
                        if (BuildConfig.EMBEDDED) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !messageBusy && !loading && (gatewayState == "已停止" || gatewayState.startsWith("启动失败")), onClick = {
                                    runCatching { LocalEngine.start(context); base = "http://127.0.0.1:8080"; key = LocalEngine.credentials(context).first; save() }.onFailure { notice = "启动失败" }
                                }) { Text("启动服务") }
                                OutlinedButton(onClick = { LocalEngine.stop(context) }) { Text("停止") }
                            }
                            OutlinedButton(onClick = {
                                val password = LocalEngine.credentials(context).second
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("本地管理密码", password))
                                notice = "已复制本机管理密码，请勿分享"
                            }) { Text("复制管理密码") }
                        }
                        OutlinedButton(onClick = {
                            runCatching { val target = if (BuildConfig.EMBEDDED) "http://127.0.0.1:8080" else GeminiApi.normalize(base)
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }.onFailure { notice = "无法打开管理面板" }
                        }) { Text("打开原项目管理面板") }
                    } }
                    Text("密钥存于 Android Keystore 保护的本地配置。聊天历史仅在当前会话保留。遵守上游服务条款和配额。")
                }
            }
        }
    }
}
@Composable
fun MessageBubble(role: String, body: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp).background(if (role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(14.dp)) {
            Text(if (role == "user") "你" else "Gemini", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp)); Text(body)
        }
    }
}
