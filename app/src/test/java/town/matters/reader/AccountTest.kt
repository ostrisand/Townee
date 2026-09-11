package town.matters.reader

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class AccountTest {
    private val user = """{"id":"user-1","displayName":"测试用户","userName":"reader","avatar":null}"""
    private fun success(token: String = "test-session") = MockResponse().setBody(
        """{"data":{"emailLogin":{"auth":true,"token":"$token","user":$user}}}""")
    private class MemoryStore : SessionStore {
        var session: AccountSession? = null
        override suspend fun load() = session
        override suspend fun save(session: AccountSession) { this.session = session }
        override suspend fun clear() { session = null }
    }

    @Test fun loginStoresSessionAndAttachesOfficialHeaderOnlyToApi() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore()
            val controller = AccountController(api, store)
            controller.restore()
            server.enqueue(success())
            controller.login("  reader@example.invalid ", "a\"b\\c")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("x-access-token"))
            val body = JSONObject(request.body.readUtf8())
            assertFalse(body.getString("query").contains("a\"b\\c"))
            val input = body.getJSONObject("variables").getJSONObject("input")
            assertEquals("reader@example.invalid", input.getString("email"))
            assertEquals("a\"b\\c", input.getString("passwordOrCode"))
            assertTrue(controller.ui.value.verified)
            assertEquals("测试用户", controller.ui.value.account?.name)
            assertEquals("test-session", store.session?.token)
            assertFalse(store.session.toString().contains("test-session"))
            server.enqueue(MockResponse().setBody("""{"data":{"channels":[]}}"""))
            api.channels()
            assertEquals("test-session", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("x-access-token"))
        }
    }

    @Test fun failedLoginDoesNotInstallCredentialsOrExposeServerMessage() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore()
            val controller = AccountController(api, store)
            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"private server text","extensions":{"code":"USER_PASSWORD_INVALID"}}]}"""))
            controller.login("reader@example.invalid", "invalid")
            assertFalse(controller.ui.value.hasSession)
            assertNull(store.session)
            assertNull(api.currentSessionToken())
            assertEquals("邮箱或密码不正确，请检查后重试。", controller.ui.value.error)
        }
    }

    @Test fun incompleteSuccessCannotBecomeLoggedIn() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            server.enqueue(MockResponse().setBody("""{"data":{"emailLogin":{"auth":true,"token":null,"user":$user}}}"""))
            assertTrue(runCatching { api.login("reader@example.invalid", "test") }.isFailure)
        }
    }

    @Test fun restoreValidatesPersistedSession() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore().apply { session = AccountSession("saved-token", Account.parse(JSONObject(user))) }
            val controller = AccountController(api, store)
            server.enqueue(MockResponse().setBody("""{"data":{"viewer":$user}}"""))
            controller.restore()
            assertTrue(controller.ui.value.verified)
            assertEquals("saved-token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("x-access-token"))
        }
    }

    @Test fun expiredSessionClearsPersistenceAndRequestHeader() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore().apply { session = AccountSession("expired", Account.parse(JSONObject(user))) }
            val controller = AccountController(api, store)
            server.enqueue(MockResponse().setBody("""{"data":{"viewer":null}}"""))
            controller.restore()
            assertFalse(controller.ui.value.hasSession)
            assertNull(api.currentSessionToken())
            assertNull(store.session)
        }
    }

    @Test fun anonymousViewerObjectIsNotAnAuthenticatedAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"id":"","userName":null,"displayName":null,"avatar":null}}}"""))
            assertNull(MattersApi(server.url("/graphql").toString()).viewer("expired"))
        }
    }

    @Test fun offlineRestoreKeepsSessionForRetry() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore().apply { session = AccountSession("saved", Account.parse(JSONObject(user))) }
            val controller = AccountController(api, store)
            server.enqueue(MockResponse().setResponseCode(503))
            controller.restore()
            assertTrue(controller.ui.value.hasSession)
            assertFalse(controller.ui.value.verified)
            assertFalse(controller.ui.value.busy)
            assertNotNull(store.session)
        }
    }

    @Test fun logoutClearsLocalSessionEvenWhenServerIsUnavailable() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val store = MemoryStore()
            val controller = AccountController(api, store)
            server.enqueue(success())
            controller.login("reader@example.invalid", "test")
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setResponseCode(503))
            controller.logout()
            assertEquals("test-session", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("x-access-token"))
            assertNull(store.session)
            assertNull(api.currentSessionToken())
            assertFalse(controller.ui.value.hasSession)
            server.enqueue(MockResponse().setBody("""{"data":{"channels":[]}}"""))
            api.channels()
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("x-access-token"))
        }
    }

    @Test fun lateExpiryFromOldRequestCannotClearNewLogin() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            val controller = AccountController(api, MemoryStore())
            server.enqueue(success("new-token"))
            controller.login("reader@example.invalid", "test")
            controller.expireSession("old-token")
            assertTrue(controller.ui.value.verified)
            assertEquals("new-token", api.currentSessionToken())
        }
    }

    @Test fun unauthorizedContentRequestSignalsExpiredSession() = runBlocking {
        MockWebServer().use { server ->
            val api = MattersApi(server.url("/graphql").toString())
            var expired: String? = null
            api.onSessionInvalid = { expired = it }
            api.setSessionToken("old-token")
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { api.channels() }.isFailure)
            assertEquals("old-token", expired)
        }
    }

    @Test fun loginDoesNotFollowRedirectsWithPassword() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/unexpected")))
                assertTrue(runCatching { MattersApi(server.url("/graphql").toString()).login("reader@example.invalid", "test") }.isFailure)
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun encryptedSessionUsesDifferentIvAndDetectsTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = SessionCipher { key }
        val plaintext = "test-session-secret".toByteArray()
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("test-session-secret"))
        assertArrayEquals(plaintext, cipher.decrypt(first))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(first) }.isFailure)
    }
}
