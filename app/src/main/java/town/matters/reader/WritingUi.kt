package town.matters.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@Composable
fun WritingScreen(model: ReaderModel, onAccount: () -> Unit) {
    val account by model.accountState.collectAsStateWithLifecycle()
    val state by model.writing.ui.collectAsStateWithLifecycle()
    val owner = account.account?.id.orEmpty()
    val context = LocalContext.current
    var preview by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    LaunchedEffect(owner) { model.writing.open(owner) }
    if (owner.isBlank() || state.owner != owner) {
        Column(Modifier.padding(24.dp)) {
            LocalizedText("登录后开始写作", style = MaterialTheme.typography.headlineSmall)
            LocalizedText("每个账户保存一份本机 Markdown 草稿。")
            Button(onClick = onAccount) { LocalizedText("前往登录") }
        }
        return
    }
    var text by remember(owner) { mutableStateOf(TextFieldValue(state.draft.markdown)) }
    LaunchedEffect(state.draft.markdown) { if (text.text != state.draft.markdown) text = TextFieldValue(state.draft.markdown) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { preview = !preview }) { LocalizedText(if (preview) "Markdown 编辑" else "预览") }
            TextButton(onClick = { clear = true }, enabled = !state.busy && state.draft.phase !in setOf("pending", "checking")) { LocalizedText("新建") }
            TextButton(onClick = { openWeb(context, "https://matters.town/me/drafts") }) { LocalizedText("网页草稿") }
        }
        if (!preview) {
            OutlinedTextField(state.draft.title, { model.writing.edit(it, text.text) },
                label = { LocalizedText("文章标题") }, singleLine = true, enabled = !state.locked,
                supportingText = { Text("${state.draft.title.length}/100") }, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("标题", "粗体", "斜体", "引用", "列表", "代码", "链接")) { label ->
                    TextButton(enabled = !state.locked, onClick = {
                        val pair = when(label) { "标题" -> "\n## " to "\n"; "粗体" -> "**" to "**"; "斜体" -> "*" to "*"
                            "引用" -> "\n> " to "\n"; "列表" -> "\n- " to "\n"; "代码" -> "\n```\n" to "\n```\n"; else -> "[" to "](https://example.com)" }
                        val start = text.selection.min; val end = text.selection.max
                        val selection = text.text.substring(start, end).ifEmpty { "文字" }
                        val next = text.text.replaceRange(start, end, pair.first + selection + pair.second)
                        if (next.length <= 100000) { text = TextFieldValue(next, TextRange(start + pair.first.length, start + pair.first.length + selection.length)); model.writing.edit(state.draft.title, next) }
                    }) { LocalizedText(label) }
                }
            }
            OutlinedTextField(text, { if(it.text.length <= 100000) { text = it; model.writing.edit(state.draft.title, it.text) } },
                enabled = !state.locked, label = { Text("Markdown") },
                placeholder = { LocalizedText("在这里写下你的文章…") }, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            var html by remember { mutableStateOf("") }
            LaunchedEffect(state.draft.title, state.draft.markdown) {
                html = withContext(Dispatchers.Default) { "<h1>" + org.jsoup.nodes.Entities.escape(state.draft.title) + "</h1>" + ArticleMarkdown.html(state.draft.markdown) }
            }
            MarkdownPreview(html, Modifier.fillMaxWidth().weight(1f))
        }
        LocalizedText(state.message, Modifier.padding(vertical = 6.dp),
            color = if(state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        if (!account.verified) TextButton(onClick = onAccount) { LocalizedText("验证登录后可保存云端和发布") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.draft.phase in setOf("pending", "checking")) {
                Button(onClick = model::refreshWriting, enabled = !state.busy && account.verified) { LocalizedText("查询发布状态") }
            } else if (state.draft.phase == "published") {
                if (state.draft.articleHash.isNotBlank()) Button(onClick = { openWeb(context, "https://matters.town/a/${state.draft.articleHash}") }) { LocalizedText("查看已发布文章") }
            } else {
                OutlinedButton(onClick = { model.sendWriting(false) }, enabled = !state.busy && account.verified) { LocalizedText("保存云端") }
                Button(onClick = { confirm = true }, enabled = !state.busy && account.verified && state.draft.title.isNotBlank() && state.draft.markdown.isNotBlank()) { LocalizedText("发布") }
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { LocalizedText("确认公开发布？") },
        text = { Column { Text(state.draft.title); Text(account.account?.name.orEmpty()); LocalizedText("将以此账户公开发布到 Matters，内容可能无法彻底撤回。Markdown 会转换为排版正文，采用网站默认许可。") } },
        confirmButton = { TextButton(onClick = { confirm = false; model.sendWriting(true) }) { LocalizedText("确认发布") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { LocalizedText("取消") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { LocalizedText("开始新草稿？") },
        text = { LocalizedText("将清空当前本机原稿。已保存的云端草稿不会删除；未同步的内容请先复制备份。") },
        confirmButton = { TextButton(onClick = { clear = false; model.writing.newDraft() }) { LocalizedText("新建") } },
        dismissButton = { TextButton(onClick = { clear = false }) { LocalizedText("取消") } })
}

@Composable
private fun MarkdownPreview(html: String, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context -> WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
    } }, update = { view -> if (view.tag != html) {
        view.tag = html
        view.loadDataWithBaseURL(null, "<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;line-height:1.7;padding:12px;overflow-wrap:anywhere}pre{white-space:pre-wrap;background:#eee;padding:10px}blockquote{border-left:3px solid #aaa;margin-left:0;padding-left:12px}img{max-width:100%}</style></head><body>$html</body></html>", "text/html", "UTF-8", null)
    } }, onRelease = { it.destroy() })
}
