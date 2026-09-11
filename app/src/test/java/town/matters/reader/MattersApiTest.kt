package town.matters.reader

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MattersApiTest {
    private val article = """{"id":"a1","shortHash":"abc123","title":"一篇文章","summary":"摘要","cover":null,"createdAt":"2026-09-10","author":{"displayName":"作者","avatar":null}}"""

    @Test fun searchUsesVariablesAndPreservesPagination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"search":{"edges":[{"node":$article}],"pageInfo":{"endCursor":"next","hasNextPage":true}}}}"""))
            val page = MattersApi(server.url("/graphql").toString()).search("文字\"与世界", null)
            assertEquals("一篇文章", page.articles.single().title)
            assertEquals("", page.articles.single().cover)
            assertEquals("next", page.cursor)
            assertTrue(page.hasMore)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("文字\"与世界", body.getJSONObject("variables").getString("key"))
            assertFalse(body.getString("query").contains("文字"))
        }
    }

    @Test fun graphqlErrorsAreNotShownAsAnEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Forbidden"}],"data":null}"""))
            val result = runCatching { MattersApi(server.url("/graphql").toString()).channels() }
            assertTrue(result.isFailure)
        }
    }

    @Test fun channelQueryOnlyUsesSupportedArticleChannels() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"channels":[{"__typename":"TopicChannel","shortHash":"t1","navbarTitle":"生活"},{"__typename":"Tag","shortHash":"x1","navbarTitle":"标签"}]}}"""))
            assertEquals(listOf(Channel("t1", "生活")), MattersApi(server.url("/graphql").toString()).channels())
        }
    }

    @Test fun offlineArticleRoundTripPreservesBody() {
        val original = Article.parse(JSONObject(article)).copy(html = "<p>离线正文</p>")
        assertEquals(original, Article.parse(original.json()))
    }

    @Test fun readerDropsScriptsAndUnsafeImagesButKeepsSafeLinks() {
        val blocks = readingBlocks("""<h2>标题</h2><p>正文<a href="/a/123">延伸阅读</a></p><script>alert('x')</script><img src="file:///secret"/><img src="https://example.org/image.jpg" alt="图片"/>""")
        assertTrue(blocks.contains(ReadingBlock.Text("标题", heading = true)))
        assertTrue(blocks.contains(ReadingBlock.Link("https://matters.town/a/123", "延伸阅读")))
        assertEquals(1, blocks.filterIsInstance<ReadingBlock.Image>().size)
        assertFalse(blocks.toString().contains("alert"))
        assertFalse(blocks.toString().contains("secret"))
    }
}
