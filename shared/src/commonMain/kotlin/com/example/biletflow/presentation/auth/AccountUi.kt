package com.example.biletflow.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.data.repositories.SessionOrdersRepository
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

const val MIN_PASSWORD_LENGTH = 6

private suspend fun HttpResponse.errorCode(): String? =
    runCatching { (Json.parseToJsonElement(bodyAsText()).jsonObject["error"] as? JsonPrimitive)?.contentOrNull }.getOrNull()

// ---------- 57.1 Change password (POST /auth/change-password) ----------

data class ChangePasswordState(val open: Boolean = false, val current: String = "", val new: String = "", val repeat: String = "",
    val sending: Boolean = false, val done: Boolean = false, val error: String? = null)

/**
 * POST /auth/change-password {current_password,new_password} → Tokens. Every other device is signed out,
 * this one gets new tokens, which replace the stored pair (same session generation, so screens stay open).
 * 400 CurrentPasswordIncorrectError → field error. An unknown result is not retried: the old refresh token
 * may already be revoked, and the general refresh logs out if so.
 */
class ChangePasswordViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(ChangePasswordState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    init { viewModelScope.launch { session.tokens.collect { if (generation != session.generation) { generation = session.generation; mutable.value = ChangePasswordState() } } } }

    fun toggle() { if (!mutable.value.sending) mutable.value = ChangePasswordState(open = !mutable.value.open) }
    fun update(current: String = mutable.value.current, new: String = mutable.value.new, repeat: String = mutable.value.repeat) {
        if (!mutable.value.sending) mutable.value = mutable.value.copy(current = current, new = new, repeat = repeat, error = null, done = false)
    }
    fun submit() {
        val s = mutable.value
        if (s.sending || session.tokens.value == null) return
        val error = when {
            s.current.isEmpty() -> "current_empty"
            s.new.length < MIN_PASSWORD_LENGTH -> "too_short"
            s.new != s.repeat -> "mismatch"
            else -> null
        }
        if (error != null) { mutable.value = s.copy(error = error); return }
        val expected = session.generation
        mutable.value = s.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                val tokens = requests.request { token ->
                    val response = client.post("$baseUrl/auth/change-password") {
                        bearerAuth(token); contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("current_password", s.current); put("new_password", s.new) })
                    }
                    if (response.status.value != 200) throw OrdersApiException(response.status.value, response.errorCode(), "HTTP ${response.status.value}")
                    response.body<AuthTokens>()
                }
                if (expected != session.generation) return@launch
                session.rotateTokens(tokens)
                mutable.value = ChangePasswordState(open = true, done = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (expected != session.generation) return@launch
                val api = e as? OrdersApiException
                mutable.value = mutable.value.copy(sending = false, error = when {
                    api?.errorCode == "CurrentPasswordIncorrectError" -> "wrong_current"
                    api?.statusCode == 422 -> "too_short"
                    api == null || api.statusCode >= 500 -> "unknown"
                    else -> "HTTP ${api.statusCode}: ${api.errorCode ?: api.message}"
                })
            }
        }
    }
}

@Composable
private fun passwordError(code: String?): String? = when (code) {
    null -> null
    "current_empty" -> stringResource(Res.string.password_current_empty)
    "too_short" -> stringResource(Res.string.password_too_short)
    "mismatch" -> stringResource(Res.string.password_mismatch)
    "wrong_current" -> stringResource(Res.string.password_wrong_current)
    "invalid_token" -> stringResource(Res.string.account_link_invalid)
    "no_token" -> stringResource(Res.string.account_link_no_token)
    "unknown" -> stringResource(Res.string.account_unknown_result)
    else -> code
}

@Composable
private fun PasswordField(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

@Composable
fun ChangePasswordSection(vm: ChangePasswordViewModel) {
    val s by vm.state.collectAsState()
    TextButton(onClick = vm::toggle, enabled = !s.sending) { Text(stringResource(Res.string.password_change_title)) }
    if (!s.open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PasswordField(s.current, stringResource(Res.string.password_current), !s.sending) { vm.update(current = it) }
        PasswordField(s.new, stringResource(Res.string.password_new), !s.sending) { vm.update(new = it) }
        PasswordField(s.repeat, stringResource(Res.string.password_repeat), !s.sending) { vm.update(repeat = it) }
        passwordError(s.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (s.done) Text(stringResource(Res.string.password_changed))
        Button(onClick = vm::submit, enabled = !s.sending) {
            if (s.sending) CircularProgressIndicator(Modifier.size(18.dp)) else Text(stringResource(Res.string.password_change_submit))
        }
    }
}

// ---------- 57.2 Email links: verify email and reset password ----------

enum class AccountLinkKind { VerifyEmail, ResetPassword }
data class AccountLink(val kind: AccountLinkKind, val token: String)

/**
 * Backend emails `{FRONTEND_URL}/verify-email?token=...` and `{FRONTEND_URL}/reset-password?token=...`.
 * Incoming links (App Link) must match [webHost]; a link the user pastes by hand is accepted from any https host,
 * because the website host is not configured in the app yet (docs/deep-links.md).
 */
fun parseAccountLink(text: String, webHost: String?): AccountLink? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    if (webHost != null && (webHost.isBlank() || url.host.lowercase() != webHost.trim().lowercase())) return null
    val kind = when (url.encodedPath.trimEnd('/')) {
        "/verify-email" -> AccountLinkKind.VerifyEmail
        "/reset-password" -> AccountLinkKind.ResetPassword
        else -> return null
    }
    val token = url.parameters["token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return AccountLink(kind, token)
}

data class AccountLinkState(val kind: AccountLinkKind? = null, val input: String = "", val password: String = "", val repeat: String = "",
    val sending: Boolean = false, val done: Boolean = false, val error: String? = null) { val open: Boolean get() = kind != null }

/**
 * Public POST /auth/verify-email {token} and POST /auth/reset-password {token,new_password}.
 * 400 InvalidAccountTokenError: bad, used or expired link. A reset signs out every session on the server,
 * so the local session is cleared too and the user signs in again.
 */
class AccountLinkViewModel(private val client: HttpClient, private val session: SessionStore,
    private val onVerified: () -> Unit, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(AccountLinkState())
    val state = mutable.asStateFlow()

    fun open(kind: AccountLinkKind, token: String = "") { mutable.value = AccountLinkState(kind = kind, input = token) }
    fun close() { if (!mutable.value.sending) mutable.value = AccountLinkState() }
    fun update(input: String = mutable.value.input, password: String = mutable.value.password, repeat: String = mutable.value.repeat) {
        if (!mutable.value.sending && !mutable.value.done) mutable.value = mutable.value.copy(input = input, password = password, repeat = repeat, error = null)
    }
    /** The field takes the whole emailed link or only the token. */
    private fun token(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.contains("://")) return parseAccountLink(text, null)?.takeIf { it.kind == mutable.value.kind }?.token
        return text.takeIf { !it.contains(' ') }
    }
    fun submit() {
        val s = mutable.value
        val kind = s.kind ?: return
        if (s.sending || s.done) return
        val token = token(s.input) ?: run { mutable.value = s.copy(error = "no_token"); return }
        if (kind == AccountLinkKind.ResetPassword) {
            if (s.password.length < MIN_PASSWORD_LENGTH) { mutable.value = s.copy(error = "too_short"); return }
            if (s.password != s.repeat) { mutable.value = s.copy(error = "mismatch"); return }
        }
        mutable.value = s.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                val response = client.post("$baseUrl/auth/${if (kind == AccountLinkKind.VerifyEmail) "verify-email" else "reset-password"}") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("token", token); if (kind == AccountLinkKind.ResetPassword) put("new_password", s.password) })
                }
                val code = response.status.value
                if (code == 200) {
                    if (kind == AccountLinkKind.ResetPassword) { if (session.tokens.value != null) session.clear() }
                    else if (session.tokens.value != null) onVerified()
                    mutable.value = mutable.value.copy(sending = false, done = true, password = "", repeat = "")
                } else {
                    val error = response.errorCode()
                    mutable.value = mutable.value.copy(sending = false, error = when {
                        error == "InvalidAccountTokenError" -> "invalid_token"
                        code == 422 -> if (kind == AccountLinkKind.ResetPassword) "too_short" else "no_token"
                        code >= 500 -> "unknown"
                        else -> "HTTP $code: ${error ?: ""}"
                    })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(sending = false, error = "unknown")
            }
        }
    }
}

@Composable
fun AccountLinkPage(vm: AccountLinkViewModel, onLogin: () -> Unit) {
    val s by vm.state.collectAsState()
    val reset = s.kind == AccountLinkKind.ResetPassword
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close, enabled = !s.sending) { Text(stringResource(Res.string.account_back)) }
        Text(stringResource(if (reset) Res.string.reset_password_title else Res.string.verify_email_title), style = MaterialTheme.typography.headlineMedium)
        if (s.done) {
            Text(stringResource(if (reset) Res.string.reset_password_done else Res.string.verify_email_done))
            if (reset) Button(onClick = { vm.close(); onLogin() }) { Text(stringResource(Res.string.auth_login_button)) }
            else Button(onClick = vm::close) { Text("OK") }
            return@Column
        }
        Text(stringResource(Res.string.account_link_hint))
        OutlinedTextField(s.input, { vm.update(input = it) }, label = { Text(stringResource(Res.string.account_link_field)) },
            enabled = !s.sending, modifier = Modifier.fillMaxWidth())
        if (reset) {
            PasswordField(s.password, stringResource(Res.string.password_new), !s.sending) { vm.update(password = it) }
            PasswordField(s.repeat, stringResource(Res.string.password_repeat), !s.sending) { vm.update(repeat = it) }
        }
        passwordError(s.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = vm::submit, enabled = !s.sending) {
            if (s.sending) CircularProgressIndicator(Modifier.size(18.dp))
            else Text(stringResource(if (reset) Res.string.reset_password_submit else Res.string.verify_email_submit))
        }
    }
}
