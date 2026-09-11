package town.matters.reader

import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AccountPanel(state: AccountState, onLogin: (String, String) -> Unit,
    onLogout: () -> Unit, onRefresh: () -> Unit, onWeb: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LocalizedText("Matters 账户", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.hasSession) {
                val account = state.account
                if (account != null) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                        LocalizedText(account.name.take(1), original = true, style = MaterialTheme.typography.titleLarge)
                        if (account.avatar.isNotBlank()) AsyncImage(account.avatar, null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        LocalizedText(account.name, original = true, style = MaterialTheme.typography.titleMedium)
                        if (account.userName.isNotBlank()) LocalizedText("@${account.userName}", original = true,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LocalizedText(if (state.verified) "已登录" else if (state.busy) "正在验证登录状态…" else "本机会话待验证",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (account != null && account.userName.isNotBlank()) TextButton(onClick = {
                    onWeb("https://matters.town/@${Uri.encode(account.userName)}")
                }) { LocalizedText("查看公开主页") }
                OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    LocalizedText(if (state.verified) "刷新账户资料" else "重新验证登录")
                }
                TextButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    LocalizedIcon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); LocalizedText("退出此设备")
                }
            } else {
                LoginForm(state.busy, onLogin)
                LocalizedText("使用已设置的 Matters 邮箱和密码。密码只发送到 Matters 官方接口，不在本机保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onWeb("https://matters.town/") }) { LocalizedText("注册、找回密码或其他登录方式：前往官网") }
                LocalizedText("网页登录与 APP 登录相互独立。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { message ->
                LocalizedText(message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            }
            state.notice?.let { message ->
                LocalizedText(message, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LoginForm(busy: Boolean, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must never be placed in saved-instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val validEmail = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    fun submit() {
        if (busy) return
        submitted = true
        if (!validEmail || password.isEmpty()) return
        focus.clearFocus()
        val credential = password
        password = ""
        submitted = false
        onLogin(email.trim(), credential)
    }
    OutlinedTextField(value = email, onValueChange = { email = it }, label = { LocalizedText("邮箱") },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
        leadingIcon = { LocalizedIcon(Icons.Outlined.Email, null) },
        isError = submitted && !validEmail,
        supportingText = { if (submitted && !validEmail) LocalizedText("请输入有效的邮箱地址") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { LocalizedText("密码") },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
        leadingIcon = { LocalizedIcon(Icons.Outlined.Lock, null) },
        trailingIcon = { IconButton(onClick = { visible = !visible }) {
            LocalizedIcon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (visible) "隐藏密码" else "显示密码")
        } }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        isError = submitted && password.isEmpty(),
        supportingText = { if (submitted && password.isEmpty()) LocalizedText("请输入密码") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }))
    Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        LocalizedText(if (busy) "正在连接…" else "登录 Matters")
    }
}
