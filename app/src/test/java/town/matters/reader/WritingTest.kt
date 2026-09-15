package town.matters.reader

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WritingTest {
    private fun response(field: String, phase: String = "unpublished") = MockResponse().setBody(
        """{"data":{"$field":{"id":"draft-1","updatedAt":"2026-09-15T00:00:00Z","publishState":"$phase","article":null}}}""")
    @Test fun markdownRendersStructureAndEscapesScripts() {
        val html = ArticleMarkdown.html("# 标题\n\n**重点**\n\n- 项目\n\n```\ncode\n```\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))")
        assertTrue(html.contains("<h1>标题</h1>")); assertTrue(html.contains("<strong>重点</strong>"))
        assertTrue(html.contains("<ul>")); assertTrue(html.contains("<pre>"))
        assertFalse(html.contains("<script>")); assertFalse(html.contains("href=\"javascript:"))
    }
    @Test fun draftsRoundTripOriginalUnicodeAndMarkdown() {
        val d = WritingDraft("繁體 title", "# 原稿\n**粗体**\n\"quoted\"", "id", "time", "checking")
        assertEquals(d, WritingDraft.parse(d.json()))
    }
    @Test fun cloudSaveUsesHtmlVariablesAndConflictTimestamp() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(response("putDraft"))
            val api = MattersApi(s.url("/graphql").toString())
            api.saveWriting(WritingDraft("标题", "**正文**", "draft-1", "previous"), "session-A")
            val request = s.takeRequest(); assertEquals("session-A", request.getHeader("x-access-token"))
            val input = JSONObject(request.body.readUtf8()).getJSONObject("variables").getJSONObject("input")
            assertTrue(input.getString("content").contains("<strong>正文</strong>"))
            assertEquals("previous", input.getString("lastUpdatedAt")); assertEquals("draft-1", input.getString("id"))
        }
    }
    @Test fun saveOnlyNeverPublishesAndRestoresPerAccount() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(response("putDraft")); val memory = mutableMapOf<String,String>()
            val c = WritingController(MattersApi(s.url("/").toString()), memory::get) { k,v -> memory[k]=v }
            c.open("a"); c.edit("标题", "正文"); c.send(false, "token")
            assertEquals(1, s.requestCount); assertEquals("draft-1", c.ui.value.draft.id)
            c.open("b"); assertEquals("", c.ui.value.draft.title)
            c.open("a"); assertEquals("正文", c.ui.value.draft.markdown)
        }
    }
    @Test fun pendingPublicationCannotBeSubmittedTwice() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(response("putDraft")); s.enqueue(response("publishArticle", "pending"))
            val c = WritingController(MattersApi(s.url("/").toString()), {null}) {_,_ -> }
            c.open("a"); c.edit("标题", "正文"); c.send(true, "token"); c.send(true, "token")
            assertEquals(2, s.requestCount); assertEquals("pending", c.ui.value.draft.phase)
            s.takeRequest(); val input=JSONObject(s.takeRequest().body.readUtf8()).getJSONObject("variables").getJSONObject("input")
            assertFalse(input.has("publishAt")); assertFalse(input.getBoolean("iscnPublish"))
        }
    }
    @Test fun ambiguousPublishFailureLocksUntilStatusQuery() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(response("putDraft")); s.enqueue(MockResponse().setResponseCode(503)); s.enqueue(response("node", "published"))
            val c = WritingController(MattersApi(s.url("/").toString()), {null}) {_,_ -> }
            c.open("a"); c.edit("标题", "正文"); c.send(true, "token")
            assertEquals("checking", c.ui.value.draft.phase); assertTrue(c.ui.value.error)
            c.send(true, "token"); assertEquals(2, s.requestCount)
            c.refresh("token"); assertEquals("published", c.ui.value.draft.phase)
        }
    }
    @Test fun cloudConflictKeepsOriginalAndDoesNotPublish() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"errors":[{"extensions":{"code":"DRAFT_VERSION_CONFLICT"}}]}"""))
            val c = WritingController(MattersApi(s.url("/").toString()), {null}) {_,_ -> }
            c.open("a"); c.edit("标题", "原稿"); c.send(true, "token")
            assertEquals("原稿", c.ui.value.draft.markdown); assertTrue(c.ui.value.error); assertEquals(1, s.requestCount)
        }
    }
    @Test fun storageFailurePreventsCloudPublication() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(response("putDraft"))
            val c = WritingController(MattersApi(s.url("/").toString()), {null}) {_,_ -> throw IllegalStateException() }
            c.open("a"); c.edit("标题", "原稿"); c.send(true, "token")
            assertEquals(1,s.requestCount); assertTrue(c.ui.value.error); assertEquals("原稿",c.ui.value.draft.markdown)
        }
    }
}
