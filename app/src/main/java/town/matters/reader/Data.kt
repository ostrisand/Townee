package town.matters.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class Article(
    val id: String, val hash: String, val title: String, val summary: String,
    val author: String, val avatar: String, val cover: String, val date: String,
    val html: String = "", val kind: String = "article"
) {
    val url get() = "https://matters.town/${if (kind == "moment") "m" else "a"}/$hash"
    fun json() = JSONObject().put("id", id).put("shortHash", hash).put("title", title)
        .put("summary", summary).put("author", JSONObject().put("displayName", author).put("avatar", avatar))
        .put("cover", cover).put("createdAt", date).put("content", html).put("kind", kind)
    companion object {
        fun parse(j: JSONObject) = Article(j.getString("id"), j.getString("shortHash"),
            j.getString("title"), j.optString("summary"), j.getJSONObject("author").optString("displayName"),
            j.getJSONObject("author").text("avatar"), j.text("cover"), j.optString("createdAt"), j.text("content"), j.optString("kind", "article"))
    }
}
private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
data class Channel(val hash: String, val title: String)
data class Page(val articles: List<Article>, val cursor: String?, val hasMore: Boolean)

class MattersApi(
    private val endpoint: String = "https://server.matters.town/graphql",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
) {
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val sessionToken = AtomicReference<String?>(null)
    @Volatile var onSessionInvalid: (String) -> Unit = {}
    fun setSessionToken(token: String?) { sessionToken.set(token) }
    fun currentSessionToken(): String? = sessionToken.get()
    private val accountFields = "id userName displayName avatar"
    private val fields = "id shortHash title summary cover createdAt author { displayName avatar }"
    private val connection = "pageInfo { endCursor hasNextPage } edges { node { $fields } }"

    private suspend fun query(document: String, variables: JSONObject = JSONObject(),
        token: String? = sessionToken.get()): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("query", document).put("variables", variables).toString()
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json").header("User-Agent", "TowneeAndroid/1.0.1")
            .apply { if (!token.isNullOrBlank()) header("x-access-token", token) }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        execute(request).use { response ->
            if (response.code == 401) {
                if (token != null) onSessionInvalid(token)
                throw MattersApiException("UNAUTHENTICATED")
            }
            if (!response.isSuccessful) throw IOException("服务暂不可用（HTTP ${response.code}）")
            val result = JSONObject(response.body?.string() ?: throw IOException("服务器返回空响应"))
            result.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
                val codes = (0 until errors.length()).map {
                    errors.optJSONObject(it)?.optJSONObject("extensions")?.optString("code").orEmpty()
                }
                val code = codes.firstOrNull { it in sessionErrorCodes } ?: codes.first()
                if (token != null && code in sessionErrorCodes) onSessionInvalid(token)
                throw MattersApiException(code)
            }
            result.optJSONObject("data") ?: throw IOException("服务器未返回数据")
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    suspend fun login(email: String, password: String): AccountSession {
        val document = """mutation Login(${'$'}input: EmailLoginInput!) {
            emailLogin(input: ${'$'}input) { auth token user { $accountFields } }
        }"""
        val result = query(document, JSONObject().put("input", JSONObject()
            .put("email", email).put("passwordOrCode", password)), token = null).getJSONObject("emailLogin")
        val token = result.text("token")
        val account = result.optJSONObject("user")
        if (!result.optBoolean("auth") || token.isBlank() || account == null) {
            throw MattersApiException("LOGIN_INCOMPLETE")
        }
        return AccountSession(token, Account.parse(account))
    }

    suspend fun viewer(token: String): Account? = query("{ viewer { $accountFields } }", token = token)
        .optJSONObject("viewer")?.takeIf { it.optString("id").isNotBlank() }?.let(Account::parse)

    suspend fun logout(token: String) {
        query("mutation Logout { userLogout }", token = token)
    }

    suspend fun channels(): List<Channel> {
        val items = query("{ channels { __typename shortHash navbarTitle } }").getJSONArray("channels")
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.getString("__typename") in setOf("TopicChannel", "CurationChannel", "WritingChallenge") }
            .map { Channel(it.getString("shortHash"), it.getString("navbarTitle")) }
    }
    suspend fun feed(hash: String, after: String?): Page {
        if (hash in setOf("icymi", "hottest", "newest")) return recommendedFeed(hash, after)
        if (hash == "following") return followingFeed(after)
        if (hash == "moments") return momentsFeed(after)
        val document = """query Feed(${'$'}hash: String!, ${'$'}after: String) {
            channel(input: {shortHash: ${'$'}hash}) {
                ... on TopicChannel { articles(input: {first: 20, after: ${'$'}after}) { $connection } }
                ... on CurationChannel { articles(input: {first: 20, after: ${'$'}after}) { $connection } }
                ... on WritingChallenge { campaignArticles: articles(input: {first: 20, after: ${'$'}after}) { $connection } }
            }
        }"""
        val channel = query(document, JSONObject().put("hash", hash).put("after", after ?: JSONObject.NULL))
            .optJSONObject("channel") ?: throw IOException("此频道暂不可用")
        return parsePage(channel.optJSONObject("articles") ?: channel.getJSONObject("campaignArticles"))
    }

    private suspend fun recommendedFeed(kind: String, after: String?): Page {
        require(kind in setOf("icymi", "hottest", "newest"))
        val extra = if (kind == "newest") ", excludeChannelArticles: true" else ""
        val document = """query Feed(${'$'}after: String) { viewer { recommendation {
            feed: $kind(input: {first: 20, after: ${'$'}after $extra}) { $connection }
        } } }"""
        return parsePage(query(document, JSONObject().put("after", after ?: JSONObject.NULL))
            .getJSONObject("viewer").getJSONObject("recommendation").getJSONObject("feed"))
    }

    private suspend fun followingFeed(after: String?): Page {
        if (sessionToken.get() == null) return Page(emptyList(), null, false)
        val document = """query Following(${'$'}after: String) { viewer { recommendation {
            feed: following(input: {first: 20, after: ${'$'}after, filter: {type: article}}) {
                pageInfo { endCursor hasNextPage }
                edges { node {
                    ... on UserPublishArticleActivity { node { $fields } }
                    ... on UserAddArticleTagActivity { node { $fields } }
                    ... on ArticleRecommendationActivity { nodes { $fields } }
                } }
            }
        } } }"""
        val data = query(document, JSONObject().put("after", after ?: JSONObject.NULL))
            .getJSONObject("viewer").getJSONObject("recommendation").getJSONObject("feed")
        val original = data.optJSONArray("edges") ?: JSONArray()
        val edges = JSONArray()
        for (i in 0 until original.length()) {
            val item = original.getJSONObject(i).getJSONObject("node")
            item.optJSONObject("node")?.let { edges.put(JSONObject().put("node", it)) }
            item.optJSONArray("nodes")?.let { nodes ->
                for (n in 0 until nodes.length()) edges.put(JSONObject().put("node", nodes.getJSONObject(n)))
            }
        }
        data.put("edges", edges)
        return parsePage(data)
    }

    private suspend fun momentsFeed(after: String?): Page {
        val document = """query Moments(${'$'}after: String) { viewer { recommendation {
            feed: hottestMoments(input: {first: 20, after: ${'$'}after}) {
                pageInfo { endCursor hasNextPage }
                edges { node { id shortHash content createdAt assets { path } author { displayName avatar } } }
            }
        } } }"""
        val data = query(document, JSONObject().put("after", after ?: JSONObject.NULL))
            .getJSONObject("viewer").getJSONObject("recommendation").getJSONObject("feed")
        val edges = data.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val node = edges.getJSONObject(i).getJSONObject("node")
            val html = node.text("content")
            val assets = node.optJSONArray("assets") ?: JSONArray()
            val images = (0 until assets.length()).map { assets.getJSONObject(it).getString("path") }
                .filter { it.startsWith("https://") }
            node.put("title", "闲聊").put("summary", Jsoup.parse(html).text()).put("kind", "moment")
                .put("cover", images.firstOrNull() ?: "")
                .put("content", html + images.joinToString("") { org.jsoup.nodes.Element("img").attr("src", it).outerHtml() })
        }
        return parsePage(data)
    }
    suspend fun search(term: String, after: String?): Page {
        val document = """query Search(${'$'}key: String!, ${'$'}after: String) {
            search(input: {key: ${'$'}key, type: Article, first: 20, after: ${'$'}after, record: false}) {
                pageInfo { endCursor hasNextPage }
                edges { node { ... on Article { $fields } } }
            }
        }"""
        return parsePage(query(document, JSONObject().put("key", term).put("after", after ?: JSONObject.NULL)).getJSONObject("search"))
    }
    suspend fun article(hash: String): Article {
        val document = """query Read(${'$'}hash: String!) {
            article(input: {shortHash: ${'$'}hash}) { $fields content }
        }"""
        return Article.parse(query(document, JSONObject().put("hash", hash)).optJSONObject("article")
            ?: throw IOException("文章不存在或暂时无法访问"))
    }
    private fun parsePage(j: JSONObject): Page {
        val edges = j.optJSONArray("edges") ?: JSONArray()
        val items = (0 until edges.length()).mapNotNull {
            edges.getJSONObject(it).optJSONObject("node")?.takeIf { node -> node.has("shortHash") }?.let(Article::parse)
        }
        val info = j.getJSONObject("pageInfo")
        return Page(items, info.text("endCursor").ifBlank { null }, info.getBoolean("hasNextPage"))
    }
}

sealed interface ReadingBlock {
    data class Text(val text: String, val heading: Boolean = false, val quote: Boolean = false) : ReadingBlock
    data class Image(val url: String, val description: String) : ReadingBlock
    data class Link(val url: String, val label: String) : ReadingBlock
}

/** Render content as native blocks; never execute remote HTML or JavaScript. */
fun readingBlocks(html: String): List<ReadingBlock> {
    val doc = Jsoup.parse(html, "https://matters.town")
    doc.select("script,style,noscript").remove()
    val result = mutableListOf<ReadingBlock>()
    fun walk(element: org.jsoup.nodes.Element) {
        when {
            element.tagName() == "img" -> {
                val url = element.absUrl("src")
                if (url.startsWith("https://")) result += ReadingBlock.Image(url, element.attr("alt"))
            }
            element.tagName() in setOf("p", "h1", "h2", "h3", "h4", "blockquote", "pre", "li", "figcaption") &&
                element.select("p,li").none { it !== element } -> {
                if (element.text().isNotBlank()) result += ReadingBlock.Text(element.wholeText(),
                    element.tagName().startsWith("h"), element.tagName() == "blockquote")
                element.select("img").forEach { walk(it) }
                element.select("a[href]").forEach {
                    val url = it.absUrl("href")
                    if (url.startsWith("https://") || url.startsWith("http://")) result += ReadingBlock.Link(url, it.text().ifBlank { "打开链接" })
                }
            }
            element.children().isEmpty() -> if (element.text().isNotBlank()) result += ReadingBlock.Text(element.text())
            else -> element.childNodes().forEach {
                when (it) {
                    is org.jsoup.nodes.Element -> walk(it)
                    is org.jsoup.nodes.TextNode -> if (it.text().isNotBlank()) result += ReadingBlock.Text(it.text())
                }
            }
        }
    }
    walk(doc.body())
    return result
}
