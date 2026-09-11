package town.matters.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

val sessionErrorCodes = setOf("UNAUTHENTICATED", "TOKEN_INVALID")

class MattersApiException(val code: String) : IOException(when (code) {
    "USER_PASSWORD_INVALID", "USER_NOT_FOUND", "CODE_INVALID", "CODE_EXPIRED" -> "邮箱或密码不正确，请检查后重试。"
    "EMAIL_INVALID", "BAD_USER_INPUT" -> "请检查邮箱和密码的格式。"
    "ACTION_LIMIT_EXCEEDED", "RATE_LIMITED" -> "尝试次数过多，请稍后再试。"
    "UNAUTHENTICATED", "TOKEN_INVALID" -> "登录状态已过期，请重新登录。"
    "FORBIDDEN_BY_STATE", "FORBIDDEN" -> "此账户暂时无法执行该操作，请前往 Matters 查看。"
    "LOGIN_INCOMPLETE" -> "服务器未返回完整登录信息，请重试。"
    else -> "请求未能完成，请稍后重试。"
})

data class Account(val id: String, val name: String, val userName: String, val avatar: String) {
    fun json() = JSONObject().put("id", id).put("displayName", name).put("userName", userName).put("avatar", avatar)
    companion object {
        fun parse(j: JSONObject): Account {
            require(j.getString("id").isNotBlank()) { "Missing account ID" }
            fun text(key: String) = if (j.isNull(key)) "" else j.optString(key)
            return Account(j.getString("id"), text("displayName").ifBlank { "Matters 用户" }, text("userName"), text("avatar"))
        }
    }
}

class AccountSession(val token: String, val account: Account) {
    override fun toString() = "AccountSession([redacted])"
}

interface SessionStore {
    suspend fun load(): AccountSession?
    suspend fun save(session: AccountSession)
    suspend fun clear()
}

data class AccountState(
    val account: Account? = null, val busy: Boolean = true,
    val hasSession: Boolean = false, val verified: Boolean = false,
    val error: String? = null, val notice: String? = null
)

/** Serializes account changes so a stale request cannot restore a logged-out account. */
class AccountController(private val api: MattersApi, private val store: SessionStore) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(AccountState())
    val ui = state.asStateFlow()

    suspend fun restore() = mutex.withLock {
        state.value = state.value.copy(busy = true, error = null, notice = null)
        val saved = try { store.load() } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            api.setSessionToken(null)
            state.value = AccountState(busy = false, error = "无法读取保存的登录状态，请重新登录。")
            return@withLock
        }
        if (saved == null) {
            api.setSessionToken(null)
            state.value = AccountState(busy = false)
            return@withLock
        }
        api.setSessionToken(saved.token)
        state.value = AccountState(account = saved.account, busy = true, hasSession = true)
        try {
            val account = api.viewer(saved.token) ?: throw MattersApiException("UNAUTHENTICATED")
            store.save(AccountSession(saved.token, account))
            state.value = AccountState(account = account, busy = false, hasSession = true, verified = true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is MattersApiException && e.code in sessionErrorCodes) {
                clearLocal("登录状态已过期，请重新登录。")
            } else {
                state.value = state.value.copy(busy = false, verified = false,
                    error = "暂时无法验证登录状态，已保留本机会话。请检查网络后重试。")
            }
        }
    }

    suspend fun login(email: String, password: String) = mutex.withLock {
        if (state.value.hasSession) return@withLock
        state.value = AccountState(busy = true)
        try {
            val session = api.login(email.trim(), password)
            try { store.save(session) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw IOException("无法安全保存登录状态，请重试。") }
            api.setSessionToken(session.token)
            state.value = AccountState(account = session.account, busy = false, hasSession = true,
                verified = true, notice = "登录成功")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            state.value = AccountState(busy = false, error = when (e) {
                is MattersApiException -> e.message
                else -> "登录未完成，请检查网络及设备存储后重试。"
            })
        }
    }

    suspend fun logout() = mutex.withLock {
        val token = api.currentSessionToken()
        if (!clearLocal("已退出此设备上的账户")) return@withLock
        // The official endpoint clears cookies; it does not revoke all other device sessions.
        if (token != null) withTimeoutOrNull(5000) {
            try { api.logout(token) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    suspend fun expireSession(requestToken: String) = mutex.withLock {
        if (api.currentSessionToken() == requestToken) clearLocal("登录状态已过期，请重新登录。")
    }

    private suspend fun clearLocal(notice: String): Boolean {
        return try {
            store.clear()
            api.setSessionToken(null)
            state.value = AccountState(busy = false, notice = notice)
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            api.setSessionToken(null)
            state.value = state.value.copy(busy = false, hasSession = true, verified = false,
                error = "清理设备会话失败，请重试退出或在系统设置中清除应用数据。")
            false
        }
    }
}
