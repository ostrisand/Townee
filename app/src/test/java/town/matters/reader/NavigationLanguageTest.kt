package town.matters.reader

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationLanguageTest {
    @Test fun convertsBothChineseScriptsWithoutChangingNumbersAndLatinText() {
        assertEquals("繁体中文 Matters 2026", ChineseDisplay.convert("繁體中文 Matters 2026", 0))
        assertEquals("簡體中文 Matters 2026", ChineseDisplay.convert("简体中文 Matters 2026", 1))
        assertEquals("登入帳號", ChineseDisplay.convert("登录账户", 1))
        assertEquals("https://matters.town/a/test-123", ChineseDisplay.convert("https://matters.town/a/test-123", 1))
    }

    @Test fun displayConversionDoesNotAlterSavedSourceText() {
        val article = Article("a", "hash", "简体标题", "摘要", "作者", "", "", "", "<p>原文</p>")
        assertEquals("簡體標題", ChineseDisplay.convert(article.title, 1, interfaceText = false))
        assertEquals("简体标题", article.json().getString("title"))
    }

    @Test fun campaignsRemainInServerMenuOrder() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"channels":[{"__typename":"WritingChallenge","shortHash":"c","navbarTitle":"七日書"},{"__typename":"TopicChannel","shortHash":"t","navbarTitle":"生活事"}]}}"""))
            assertEquals(listOf("c", "t"), MattersApi(server.url("/graphql").toString()).channels().map { it.hash })
        }
    }

    @Test fun featuredFeedUsesSameRecommendationAsWebsite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"recommendation":{"feed":{"edges":[],"pageInfo":{"endCursor":"next","hasNextPage":true}}}}}}"""))
            val page = MattersApi(server.url("/graphql").toString()).feed("icymi", "previous")
            assertTrue(page.hasMore)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.getString("query").contains("icymi(input:"))
            assertEquals("previous", body.getJSONObject("variables").getString("after"))
        }
    }

    @Test fun campaignConnectionUsesSeparateAliasToAvoidSchemaConflict() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"channel":{"campaignArticles":{"edges":[],"pageInfo":{"endCursor":null,"hasNextPage":false}}}}}"""))
            val page = MattersApi(server.url("/graphql").toString()).feed("campaign-hash", null)
            assertTrue(page.articles.isEmpty())
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.getString("query").contains("campaignArticles: articles"))
        }
    }

    @Test fun momentContentRetainsCorrectLinkAndType() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"recommendation":{"feed":{"pageInfo":{"endCursor":null,"hasNextPage":false},"edges":[{"node":{"id":"m1","shortHash":"moment1","content":"<p>闲聊正文</p>","createdAt":"2026-09-10","assets":[],"author":{"displayName":"作者","avatar":null}}}]}}}}}"""))
            val moment = MattersApi(server.url("/graphql").toString()).feed("moments", null).articles.single()
            assertEquals("moment", moment.kind)
            assertEquals("https://matters.town/m/moment1", moment.url)
            assertEquals("<p>闲聊正文</p>", moment.html)
            assertEquals(moment, Article.parse(moment.json()))
        }
    }
}
