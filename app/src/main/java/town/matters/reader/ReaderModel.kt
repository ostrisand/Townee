package town.matters.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

data class ReaderState(
    val channels: List<Channel> = emptyList(), val selected: String = "icymi",
    val feed: List<Article> = emptyList(), val term: String = "", val results: List<Article> = emptyList(),
    val loading: Boolean = false, val searching: Boolean = false, val reading: Boolean = false,
    val feedError: String? = null, val searchError: String? = null, val readError: String? = null,
    val feedMore: Boolean = false, val searchMore: Boolean = false,
    val article: Article? = null, val saved: List<Article> = emptyList(),
    val theme: Int = 0, val fontSize: Float = 18f,
    val language: Int = 0, val defaultLanguage: Int = 0
)

class ReaderModel(app: Application) : AndroidViewModel(app) {
    private val api = MattersApi()
    private val accountController = AccountController(api, EncryptedSessionStore(app))
    val accountState = accountController.ui
    private val prefs = app.getSharedPreferences("reader", 0)
    private val writingPrefs = app.getSharedPreferences("writing", 0)
    val writing = WritingController(api, { writingPrefs.getString(it, null) }, { key, value ->
        check(writingPrefs.edit().putString(key, value).commit()) { "保存失败" }
    })
    fun sendWriting(publish: Boolean) {
        val owner = accountState.value.account?.id ?: return
        val token = api.currentSessionToken() ?: return
        if (!accountState.value.verified || writing.ui.value.owner != owner) return
        viewModelScope.launch { writing.send(publish, token) }
    }
    fun refreshWriting() {
        val owner = accountState.value.account?.id ?: return
        val token = api.currentSessionToken() ?: return
        if (!accountState.value.verified || writing.ui.value.owner != owner) return
        viewModelScope.launch { writing.refresh(token) }
    }
    private val state = MutableStateFlow(ReaderState(
        saved = runCatching {
            val list = JSONArray(prefs.getString("saved", "[]"))
            (0 until list.length()).map { Article.parse(list.getJSONObject(it)) }
        }.getOrDefault(emptyList()),
        theme = prefs.getInt("theme", 0), fontSize = prefs.getFloat("font", 18f),
        language = prefs.getInt("default_language", 0), defaultLanguage = prefs.getInt("default_language", 0)
    ))
    val ui = state.asStateFlow()
    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var readJob: Job? = null
    private var feedCursor: String? = null
    private var searchCursor: String? = null
    init {
        api.onSessionInvalid = { token ->
            viewModelScope.launch {
                accountController.expireSession(token)
                if (api.currentSessionToken() == null) refreshAfterAccountChange()
            }
        }
        loadFeed()
        viewModelScope.launch {
            accountController.restore()
            if (state.value.selected == "following") loadFeed()
        }
    }

    fun login(email: String, password: String) {
        if (accountState.value.busy) return
        viewModelScope.launch {
            accountController.login(email, password)
            if (accountState.value.verified) refreshAfterAccountChange()
        }
    }

    fun refreshAccount() {
        if (!accountState.value.busy) viewModelScope.launch { accountController.restore() }
    }

    fun logout() {
        if (accountState.value.busy) return
        viewModelScope.launch {
            accountController.logout()
            refreshAfterAccountChange()
        }
    }

    private fun refreshAfterAccountChange() {
        readJob?.cancel()
        searchJob?.cancel()
        feedCursor = null
        searchCursor = null
        state.update { it.copy(article = null, reading = false, readError = null, feed = emptyList(),
            results = emptyList(), term = "", searching = false, searchError = null, searchMore = false, feedMore = false) }
        loadFeed()
    }

    fun loadFeed(channel: String? = null, more: Boolean = false) {
        if (more && (state.value.loading || !state.value.feedMore)) return
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            val changed = channel != null && channel != state.value.selected
            if (changed) feedCursor = null
            state.update { it.copy(loading = true, feedError = null, selected = channel ?: it.selected,
                feed = if (changed) emptyList() else it.feed, feedMore = if (changed) false else it.feedMore) }
            try {
                val channels = state.value.channels.ifEmpty { api.channels() }
                val selected = channel ?: state.value.selected.ifBlank { channels.firstOrNull()?.hash.orEmpty() }
                state.update { it.copy(channels = channels, selected = selected) }
                val page = if (selected.isBlank()) Page(emptyList(), null, false)
                    else api.feed(selected, if (more) feedCursor else null)
                feedCursor = page.cursor
                state.update { it.copy(feed = ((if (more) it.feed else emptyList()) + page.articles).distinctBy(Article::id),
                    feedMore = page.hasMore && page.cursor != null, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, feedError = e.message ?: "连接失败，请重试") } }
        }
    }
    fun search(term: String, more: Boolean = false) {
        if (more && (state.value.searching || !state.value.searchMore)) return
        searchJob?.cancel()
        val key = term.trim()
        if (key.isBlank()) {
            state.update { it.copy(term = "", results = emptyList(), searching = false, searchError = null, searchMore = false) }
            return
        }
        searchJob = viewModelScope.launch {
            state.update { it.copy(term = key, searching = true, searchError = null,
                results = if (more) it.results else emptyList(), searchMore = if (more) it.searchMore else false) }
            try {
                val page = api.search(key, if (more) searchCursor else null)
                searchCursor = page.cursor
                state.update { it.copy(results = ((if (more) it.results else emptyList()) + page.articles).distinctBy(Article::id),
                    searching = false, searchMore = page.hasMore && page.cursor != null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(searching = false, searchError = e.message ?: "搜索失败") } }
        }
    }
    fun read(article: Article) {
        readJob?.cancel()
        val cached = state.value.saved.find { it.id == article.id } ?: article
        state.update { it.copy(article = cached, readError = null, reading = cached.html.isBlank() && cached.kind != "moment") }
        if (cached.html.isNotBlank() || cached.kind == "moment") return
        readJob = viewModelScope.launch {
            try {
                val full = api.article(article.hash)
                state.update { it.copy(article = full, reading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(reading = false, readError = e.message ?: "无法加载正文") } }
        }
    }
    fun closeArticle() { readJob?.cancel(); state.update { it.copy(article = null, reading = false, readError = null) } }
    fun toggleSave(article: Article) {
        val exists = state.value.saved.any { it.id == article.id }
        if (!exists && article.html.isBlank()) return
        val saved = if (exists) state.value.saved.filterNot { it.id == article.id } else listOf(article) + state.value.saved
        state.update { it.copy(saved = saved) }
        prefs.edit().putString("saved", JSONArray(saved.map { it.json() }).toString()).apply()
    }
    fun theme(value: Int) { state.update { it.copy(theme = value) }; prefs.edit().putInt("theme", value).apply() }
    fun font(value: Float) { state.update { it.copy(fontSize = value) }; prefs.edit().putFloat("font", value).apply() }
    fun toggleLanguage() { state.update { it.copy(language = 1 - it.language) } }
    fun defaultLanguage(value: Int) {
        val language = value.coerceIn(0, 1)
        state.update { it.copy(language = language, defaultLanguage = language) }
        prefs.edit().putInt("default_language", language).apply()
    }
}
