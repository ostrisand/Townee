package town.matters.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object ArticleMarkdown {
    fun html(markdown: String): String {
        val rendered = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
            .render(Parser.builder().build().parse(markdown))
        return Jsoup.clean(rendered, Safelist.basicWithImages().addTags("h1", "h2", "h3", "h4", "h5", "h6", "hr")
            .removeProtocols("img", "src", "http").removeProtocols("a", "href", "ftp"))
    }
}

data class WritingDraft(val title: String = "", val markdown: String = "", val id: String = "",
    val updatedAt: String = "", val phase: String = "unpublished", val articleHash: String = "") {
    fun json() = JSONObject().put("title", title).put("markdown", markdown).put("id", id)
        .put("updatedAt", updatedAt).put("phase", phase).put("articleHash", articleHash).toString()
    companion object {
        fun parse(raw: String): WritingDraft { val j = JSONObject(raw); return WritingDraft(j.optString("title"),
            j.optString("markdown"), j.optString("id"), j.optString("updatedAt"), j.optString("phase", "unpublished"), j.optString("articleHash")) }
    }
}
data class WritingState(val owner: String = "", val draft: WritingDraft = WritingDraft(),
    val busy: Boolean = false, val storageError: Boolean = false, val message: String = "", val error: Boolean = false) {
    val locked get() = storageError || busy || draft.phase in setOf("pending", "checking", "published")
}
data class CloudDraft(val id: String, val updatedAt: String, val phase: String, val hash: String)
private const val draftFields = "id updatedAt publishState article { shortHash }"
private fun cloud(j: JSONObject) = CloudDraft(j.getString("id"), j.getString("updatedAt"),
    j.getString("publishState"), j.optJSONObject("article")?.optString("shortHash").orEmpty())
internal suspend fun MattersApi.saveWriting(draft: WritingDraft, token: String): CloudDraft {
    require(draft.title.isNotBlank() && draft.markdown.isNotBlank()) { "请填写标题和正文" }
    val input = JSONObject().put("title", draft.title.trim()).put("content", ArticleMarkdown.html(draft.markdown))
    if (draft.id.isNotBlank()) input.put("id", draft.id).put("lastUpdatedAt", draft.updatedAt)
    return cloud(query("mutation SaveWriting(\$input: PutDraftInput!) { putDraft(input: \$input) { $draftFields } }",
        JSONObject().put("input", input), token).getJSONObject("putDraft"))
}
internal suspend fun MattersApi.publishWriting(id: String, token: String): CloudDraft = cloud(query(
    "mutation PublishWriting(\$input: PublishArticleInput!) { publishArticle(input: \$input) { $draftFields } }",
    JSONObject().put("input", JSONObject().put("id", id).put("iscnPublish", false)), token).getJSONObject("publishArticle"))
internal suspend fun MattersApi.writingStatus(id: String, token: String): CloudDraft = cloud(query(
    "query WritingStatus(\$id: ID!) { node(input: {id: \$id}) { ... on Draft { $draftFields } } }",
    JSONObject().put("id", id), token).getJSONObject("node"))

/** Storage is keyed by account; no account switch can redirect an in-flight request. */
class WritingController(private val api: MattersApi, private val load: (String) -> String?,
    private val store: (String, String) -> Unit) {
    private val state = MutableStateFlow(WritingState())
    val ui = state.asStateFlow()
    private var generation = 0
    fun open(owner: String) {
        if (state.value.owner == owner) return
        generation++
        val raw = load(owner)
        val restored = try { raw?.let(WritingDraft::parse) ?: WritingDraft() }
        catch (_: Exception) { state.value = WritingState(owner, storageError = true, message = "本机草稿读取失败，请勿清除应用数据", error = true); return }
        state.value = WritingState(owner, restored, message = if (raw == null) "Markdown 草稿自动保存到本机" else "已恢复本机草稿")
    }
    private fun persist(draft: WritingDraft, message: String = "已保存到本机") {
        store(state.value.owner, draft.json())
        state.value = state.value.copy(draft = draft, storageError = false, message = message, error = false)
    }
    fun edit(title: String, markdown: String) {
        if (state.value.locked || title.length > 100 || markdown.length > 100000) return
        try { persist(state.value.draft.copy(title = title, markdown = markdown)) }
        catch (_: Exception) { state.value = state.value.copy(draft = state.value.draft.copy(title = title, markdown = markdown), message = "本机保存失败，请复制正文备份", error = true) }
    }
    fun newDraft() { if (!state.value.busy && state.value.draft.phase !in setOf("pending", "checking")) { generation++; try { persist(WritingDraft()) } catch (_: Exception) { state.value = state.value.copy(message = "本机保存失败", error = true) } } }
    suspend fun send(publish: Boolean, token: String) {
        val epoch = generation
        val initial = state.value
        if (initial.owner.isBlank() || initial.locked) return
        if (initial.draft.title.isBlank() || initial.draft.markdown.isBlank()) {
            state.value = initial.copy(message = "请填写标题和正文", error = true); return
        }
        state.value = initial.copy(busy = true, message = if (publish) "保存并提交发布…" else "正在保存云端草稿…", error = false)
        try {
            val saved = api.saveWriting(initial.draft, token)
            if (generation != epoch) return
            persist(initial.draft.copy(id = saved.id, updatedAt = saved.updatedAt, phase = saved.phase, articleHash = saved.hash), "云端草稿已保存")
            if (publish && saved.phase in setOf("unpublished", "error")) {
                persist(state.value.draft.copy(phase = "checking"), "正在提交发布…")
                val result = api.publishWriting(saved.id, token)
                if (generation != epoch) return
                accept(result)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (generation == epoch) state.value = state.value.copy(error = true,
            message = if (state.value.draft.phase == "checking") "发布结果尚未确认，请查询发布状态，勿重复发布。"
                else "云端保存失败，本机原稿保留。请检查网络、登录状态，或在网页检查草稿是否冲突；首次保存超时可能已创建云端草稿。") }
        finally { if (generation == epoch) state.value = state.value.copy(busy = false) }
    }
    private fun accept(result: CloudDraft) {
        persist(state.value.draft.copy(id = result.id, updatedAt = if(result.phase in setOf("pending", "published")) result.updatedAt else state.value.draft.updatedAt, phase = result.phase, articleHash = result.hash),
            when(result.phase) { "published" -> "文章已发布"; "pending" -> "服务器正在发布，请稍后查询状态"; "error" -> "服务器发布失败，检查后可重试"; else -> "云端仍为草稿，可继续编辑" })
    }
    suspend fun refresh(token: String) {
        val epoch = generation
        val initial = state.value
        if (initial.busy || initial.draft.id.isBlank()) return
        state.value = initial.copy(busy = true)
        try { val result = api.writingStatus(initial.draft.id, token); if (generation == epoch) accept(result) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if(generation == epoch) state.value = state.value.copy(message = "暂时无法确认，请稍后再查或到网页查看草稿", error = true) }
        finally { if(generation == epoch) state.value = state.value.copy(busy = false) }
    }
}
