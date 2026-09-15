package town.matters.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ReaderModel = viewModel()
            val state by model.ui.collectAsStateWithLifecycle()
            val dark = when (state.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(
                primary = Color(0xFFA0D5AF), onPrimary = Color(0xFF06391F),
                primaryContainer = Color(0xFF245039), onPrimaryContainer = Color(0xFFBBF2C9),
                background = Color(0xFF101510), surface = Color(0xFF101510)
            ) else lightColorScheme(
                primary = Color(0xFF245E43), onPrimary = Color.White,
                primaryContainer = Color(0xFFD6EEDB), onPrimaryContainer = Color(0xFF103822),
                secondaryContainer = Color(0xFFE7EBDD), onSecondaryContainer = Color(0xFF343B2C),
                background = Color(0xFFF8FAF5), surface = Color(0xFFF8FAF5)
            )) {
                CompositionLocalProvider(LocalReaderLanguage provides state.language) { ReaderApp(state, model) }
            }
        }
    }
}

private val mainFeeds = listOf(Channel("following", "关注"), Channel("icymi", "精选"),
    Channel("hottest", "热文"), Channel("moments", "闲聊"))
private fun channelTitle(state: ReaderState) = (mainFeeds + state.channels + Channel("newest", "还有"))
    .find { it.hash == state.selected }?.title ?: "精选"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(state: ReaderState, model: ReaderModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabState = rememberSaveableStateHolder()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val account by model.accountState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = drawer.isOpen || state.article != null || tab != 0) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (state.article != null) model.closeArticle() else tab = 0
    }
    Surface(Modifier.fillMaxSize()) {
        if (state.article != null) {
            ReadingScreen(state, model)
        } else ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp).fillMaxHeight()) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LocalizedText("Matters", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { scope.launch { drawer.close() } }) { LocalizedIcon(Icons.Outlined.Close, "关闭菜单") }
                        }
                    }
                    items(mainFeeds + state.channels + Channel("newest", "还有"), key = { it.hash }) { channel ->
                        NavigationDrawerItem(label = { LocalizedText(channel.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            selected = tab == 0 && state.selected == channel.hash,
                            onClick = { tab = 0; model.loadFeed(channel.hash); scope.launch { drawer.close() } })
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp))
                        NavigationDrawerItem(label = { LocalizedText("本地书架") }, selected = tab == 2,
                            icon = { LocalizedIcon(Icons.Outlined.Bookmarks, null) },
                            onClick = { tab = 2; scope.launch { drawer.close() } })
                        NavigationDrawerItem(label = { LocalizedText("账户与设置") }, selected = tab == 3,
                            icon = { LocalizedIcon(Icons.Outlined.Tune, null) },
                            onClick = { tab = 3; scope.launch { drawer.close() } })
                    }
                }
            }
        }) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(title = { LocalizedText("Matters", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                            navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { LocalizedIcon(Icons.Outlined.Menu, "打开频道菜单") } },
                            actions = {
                                IconButton(onClick = { tab = 1 }) { LocalizedIcon(Icons.Outlined.Search, "搜索") }
                                IconButton(onClick = { tab = 4 }) { LocalizedIcon(Icons.Outlined.AddBox, "写文章") }
                                IconButton(onClick = { openWeb(context, "https://matters.town/me/notifications") }) { LocalizedIcon(Icons.Outlined.Notifications, "在网页查看通知") }
                                IconButton(onClick = { tab = 3 }) {
                                    val avatar = account.account?.avatar.orEmpty()
                                    if (avatar.isBlank()) LocalizedIcon(Icons.Outlined.AccountCircle, "账户与设置")
                                    else AsyncImage(avatar, uiLabel("账户与设置"), Modifier.size(30.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                }
                            })
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.weight(1f)) {
                                LocalizedText(if (tab == 0) channelTitle(state) else listOf("首页", "搜索", "书架", "我的", "写文章")[tab],
                                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                LocalizedIcon(Icons.Outlined.ExpandMore, null)
                            }
                            TextButton(onClick = model::toggleLanguage) { LocalizedText(if (state.language == 0) "繁中" else "简中", original = true) }
                            if (tab == 0) IconButton(onClick = { model.loadFeed() }, enabled = !state.loading) { LocalizedIcon(Icons.Outlined.Refresh, "刷新文章") }
                        }
                    }
                }
            ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                            tabState.SaveableStateProvider(tab) {
                                when (tab) {
                                    0 -> FeedScreen(state, model) { tab = 3 }
                                    1 -> SearchScreen(state, model)
                                    2 -> LibraryScreen(state, model)
                                    3 -> SettingsScreen(state, model)
                                    4 -> WritingScreen(model) { tab = 3 }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun FeedScreen(state: ReaderState, model: ReaderModel, onAccount: () -> Unit) {
    val account by model.accountState.collectAsStateWithLifecycle()
    Column {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            LocalizedText(channelTitle(state), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        if (state.selected == "following" && !account.hasSession) {
            EmptyPanel(Icons.Outlined.PersonOutline, "登录后查看关注", "在这里阅读你关注的作者发布的文章。")
            Button(onClick = onAccount, modifier = Modifier.align(Alignment.CenterHorizontally)) { LocalizedText("登录账户") }
        } else {
            key(state.selected) {
                ArticleList(state.feed, state.loading, state.feedError, state.feedMore,
                    "这里还没有文章", "试试刷新，或到搜索页寻找感兴趣的文字。",
                    onRetry = { model.loadFeed() }, onMore = { model.loadFeed(more = true) }, onArticle = model::read)
            }
        }
    }
}

@Composable
private fun SearchScreen(state: ReaderState, model: ReaderModel) {
    var input by rememberSaveable { mutableStateOf(state.term) }
    val focus = LocalFocusManager.current
    fun submit() { focus.clearFocus(); model.search(input) }
    Column {
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            label = { LocalizedText("搜索 Matters 文章") }, placeholder = { LocalizedText("输入关键词，开始探索") },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { LocalizedIcon(Icons.Outlined.Search, null) },
            trailingIcon = { IconButton(onClick = { submit() }) { LocalizedIcon(Icons.AutoMirrored.Outlined.ArrowForward, "提交搜索") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }))
        if (state.term.isBlank()) {
            EmptyPanel(Icons.Outlined.Search, "每一份好奇，都有回响", "搜索文章标题与内容，发现新的视角。")
        } else {
            LocalizedText("“${state.term}” 的搜索结果", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
            ArticleList(state.results, state.searching, state.searchError, state.searchMore,
                "暂时没有搜索结果", "换一个关键词试试。", onRetry = { model.search(state.term) },
                onMore = { model.search(state.term, true) }, onArticle = model::read)
        }
    }
}

@Composable
private fun LibraryScreen(state: ReaderState, model: ReaderModel) {
    Column {
        LocalizedText("留给下一次安静的阅读", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.headlineSmall)
        LocalizedText("${state.saved.size} 篇本地收藏 · 正文可离线阅读，图片需要网络", Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ArticleList(state.saved, false, null, false, "把喜欢的文字留下来", "打开文章后，点击右上角收藏即可加入书架。",
            onRetry = {}, onMore = {}, onArticle = model::read)
    }
}

@Composable
private fun ArticleList(articles: List<Article>, loading: Boolean, error: String?, more: Boolean,
    emptyTitle: String, emptyMessage: String, onRetry: () -> Unit, onMore: () -> Unit, onArticle: (Article) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (error != null) item { ErrorPanel(error, onRetry) }
        items(articles, key = { it.id }) { ArticleCard(it) { onArticle(it) } }
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (!loading && error == null && articles.isEmpty()) item { EmptyPanel(Icons.Outlined.AutoStories, emptyTitle, emptyMessage) }
        if (!loading && more) item {
            OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { LocalizedText("加载更多") }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        if (article.cover.isNotBlank()) AsyncImage(article.cover, null,
            Modifier.fillMaxWidth().height(170.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.Crop)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthorLine(article)
            LocalizedText(article.title, content = true, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (article.summary.isNotBlank()) LocalizedText(article.summary, content = true, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalizedText("阅读文章", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                LocalizedIcon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AuthorLine(article: Article) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            LocalizedText(article.author.take(1), original = true, style = MaterialTheme.typography.labelMedium)
            if (article.avatar.isNotBlank()) AsyncImage(article.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        LocalizedText(article.author, Modifier.weight(1f), original = true, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge)
        LocalizedText(article.date.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingScreen(state: ReaderState, model: ReaderModel) {
    val article = state.article ?: return
    val context = LocalContext.current
    val saved = state.saved.any { it.id == article.id }
    val blocks = remember(article.html) { readingBlocks(article.html) }
    val scroll = rememberLazyListState()
    var textSettings by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { LocalizedText("阅读", style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
            IconButton(onClick = model::closeArticle) { LocalizedIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = model::toggleLanguage) { LocalizedText(if (state.language == 0) "繁中" else "简中", original = true) }
            IconButton(onClick = { textSettings = true }) { LocalizedIcon(Icons.Outlined.TextFields, "阅读字号") }
            IconButton(onClick = { model.toggleSave(article) }, enabled = saved || article.html.isNotBlank()) {
                LocalizedIcon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder,
                    if (saved) "取消本地收藏" else "收藏到本地书架")
            }
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
                }, "分享文章"))
            }) { LocalizedIcon(Icons.Outlined.Share, "分享文章") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(state = scroll, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { LocalizedText(article.title, content = true, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) }
                item { AuthorLine(article) }
                item { HorizontalDivider() }
                if (state.reading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.readError?.let { message -> item { ErrorPanel(message) { model.read(article) } } }
                if (article.html.isBlank() && !state.reading) item {
                    LocalizedText(article.summary, content = true, style = MaterialTheme.typography.bodyLarge)
                    LocalizedText("正文暂不可用，可以在网页继续阅读。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(blocks) { block ->
                    when (block) {
                        is ReadingBlock.Text -> SelectionContainer {
                            LocalizedText(block.text, content = true, fontSize = (state.fontSize + if (block.heading) 5 else 0).sp,
                                lineHeight = (state.fontSize * 1.85f).sp,
                                fontWeight = if (block.heading) FontWeight.Bold else FontWeight.Normal,
                                modifier = if (block.quote) Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(16.dp) else Modifier)
                        }
                        is ReadingBlock.Image -> AsyncImage(block.url, block.description.ifBlank { "文章图片" },
                            Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 460.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit)
                        is ReadingBlock.Link -> TextButton(onClick = { openWeb(context, block.url) }) {
                            LocalizedIcon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); LocalizedText(block.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider()
                        LocalizedText("文字的相遇，也可以是对话的开始。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { openWeb(context, article.url) }, modifier = Modifier.fillMaxWidth()) {
                            LocalizedText("在 Matters 网页查看原文与讨论")
                        }
                    }
                }
            }
        }
    }
    if (textSettings) ModalBottomSheet(onDismissRequest = { textSettings = false }) {
        Column(Modifier.padding(24.dp)) {
            LocalizedText("让阅读更舒服", style = MaterialTheme.typography.titleLarge)
            LocalizedText("正文字号：${state.fontSize.toInt()} sp", Modifier.padding(top = 16.dp))
            Slider(value = state.fontSize, onValueChange = model::font, valueRange = 16f..26f, steps = 9)
            LocalizedText("字号同时遵循系统字体缩放设置。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsScreen(state: ReaderState, model: ReaderModel) {
    val context = LocalContext.current
    val account by model.accountState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            LocalizedText("你的阅读空间", style = MaterialTheme.typography.headlineSmall)
            LocalizedText("按自己的节奏，读喜欢的文字。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            AccountPanel(account, model::login, model::logout, model::refreshAccount) { openWeb(context, it) }
        }
        item {
            OutlinedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    LocalizedText("默认语言", style = MaterialTheme.typography.titleMedium)
                    listOf("简体中文", "繁體中文").forEachIndexed { index, label ->
                        Surface(onClick = { model.defaultLanguage(index) }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                                RadioButton(selected = state.defaultLanguage == index, onClick = { model.defaultLanguage(index) })
                                LocalizedText(label, original = true)
                            }
                        }
                    }
                    LocalizedText("顶部按钮临时切换繁简；启动时使用这里的默认语言。", style = MaterialTheme.typography.bodySmall)
                    LocalizedText("界面和文章按所选字形显示；文章原文与作者名称保持保存时的内容。", Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    LocalizedText("外观", style = MaterialTheme.typography.titleMedium)
                    listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                        Surface(onClick = { model.theme(index) }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                                RadioButton(selected = state.theme == index, onClick = { model.theme(index) })
                                LocalizedText(label)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    LocalizedText("正文字号：${state.fontSize.toInt()} sp", style = MaterialTheme.typography.titleMedium)
                    Slider(value = state.fontSize, onValueChange = model::font, valueRange = 16f..26f, steps = 9)
                }
            }
        }
        item {
            LocalizedText("与 Matters 连接", style = MaterialTheme.typography.titleMedium)
            LocalizedText("写作、评论与赞赏请在 Matters 网页完成。此客户端的书架仅保存在本机，不与账号同步，退出登录后仍保留。",
                Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { openWeb(context, "https://matters.town/") }) {
                LocalizedIcon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); LocalizedText("打开 Matters")
            }
        }
        item {
            HorizontalDivider()
            OutlinedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    LocalizedText("检查更新", style = MaterialTheme.typography.titleMedium)
                    LocalizedText(if (update == null) updateMessage.ifBlank { "从 Townee GitHub Releases 检查最新版本。" } else "发现新版本 ${update!!.version}",
                        Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (update != null) {
                        if (update!!.notes.isNotBlank()) Text(update!!.notes.take(500), maxLines = 8, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openWeb(context, update!!.apkUrl.ifBlank { update!!.pageUrl }) }) { LocalizedText("下载更新") }
                            TextButton(onClick = { openWeb(context, update!!.pageUrl) }) { LocalizedText("查看发布页") }
                        }
                    }
                    OutlinedButton(onClick = {
                        updateBusy = true; updateMessage = "正在检查…"; update = null
                        scope.launch {
                            val result = UpdateChecker.latest()
                            updateBusy = false
                            result.onSuccess { found -> update = found; updateMessage = if (found == null) "当前已是最新版本" else "" }
                                .onFailure { updateMessage = "检查失败：${it.message ?: "请稍后重试"}" }
                        }
                    }, enabled = !updateBusy) { LocalizedText(if (updateBusy) "检查中…" else "检查更新") }
                }
            }
            LocalizedText("Townee 1.0.0", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.labelLarge)
            LocalizedText("非官方 Android 阅读客户端\n基于公开接口提供内容，文章版权归原作者所有。\n会话通过 Android Keystore 加密保存在本机，不保存密码。无分析与广告 SDK。卸载会清除会话与书架。",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyPanel(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalizedIcon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        LocalizedText(title, style = MaterialTheme.typography.titleMedium)
        LocalizedText(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            LocalizedText("暂时无法加载", fontWeight = FontWeight.SemiBold)
            LocalizedText(message, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { LocalizedText("重试") }
        }
    }
}

internal fun openWeb(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in listOf("https", "http")) return
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
        .onFailure { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}
