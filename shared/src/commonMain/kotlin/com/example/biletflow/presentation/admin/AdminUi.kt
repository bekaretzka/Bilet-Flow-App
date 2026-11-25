package com.example.biletflow.presentation.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.core.platform.rememberFileSharer
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.data.repositories.SessionOrdersRepository
import com.example.biletflow.data.repositories.SupportApiException
import com.example.biletflow.data.repositories.parseSupportError
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

internal val adminJson = Json { ignoreUnknownKeys = true }

@Serializable data class AdminPage(val items: List<JsonObject>, val total: Int)

/**
 * Every /admin search list answers {items,total} with limit/offset, optional q (a numeric q also matches the id)
 * and ?format=csv for all matching rows. [filters]: key → allowed values, or null for a free numeric field.
 */
enum class AdminList(val path: String, val key: String, val hasQ: Boolean, val filters: Map<String, List<String>?>) {
    Users("/admin/users", "users", true, mapOf("suspended" to listOf("true", "false"))),
}

data class AdminCsv(val bytes: ByteArray, val fileName: String)
/** A definite server answer: [code] is the backend "error" (or an older detail ending in Error). */
data class AdminFailure(val status: Int, val code: String?, val message: String)

data class AdminState(
    val open: Boolean = false, val list: AdminList? = null, val q: String = "", val filters: Map<String, String> = emptyMap(),
    val items: List<JsonObject> = emptyList(), val total: Int = 0, val loading: Boolean = false, val error: String? = null,
    val csvBusy: Boolean = false, val csv: AdminCsv? = null, val csvError: String? = null, val denied: Boolean = false,
    val selectedId: Int? = null, val busy: Boolean = false, val actionError: AdminFailure? = null, val actionUnknown: Boolean = false,
    val actionDone: Boolean = false, val approval: String? = null,
) {
    val selected: JsonObject? get() = selectedId?.let { id -> items.firstOrNull { it.id == id } }
}

internal val JsonObject.id: Int? get() = (this["id"] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** CSV file name from Content-Disposition (filename*= or filename=), else admin-<list>-<date>.csv. */
fun adminCsvFileName(disposition: String?, list: String, date: String): String {
    val parts = disposition.orEmpty().split(';').map { it.trim() }
    val star = parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.substringAfter('=')?.substringAfterLast("''")
    val plain = parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }?.substringAfter('=')
    val name = (star ?: plain)?.trim()?.trim('"')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
    return name ?: "admin-$list-$date.csv"
}

/**
 * Platform admin lists and actions. Responses are never logged. Mutations are not retried after a network failure:
 * the documented GET is read instead. 403 PlatformAdminRequiredError sets [AdminState.denied]; the app then reloads /auth/me.
 */
class AdminViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(AdminState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var sequence = 0
    init { viewModelScope.launch { session.tokens.collect { if (generation != session.generation) { generation = session.generation; close() } } } }

    fun open() { close(); mutable.value = AdminState(open = true) }
    fun close() { sequence++; mutable.value = AdminState() }
    fun back() {
        val s = mutable.value
        when {
            s.busy -> Unit
            s.selectedId != null -> mutable.value = s.copy(selectedId = null, actionError = null, actionUnknown = false, actionDone = false, approval = null)
            s.list != null -> { sequence++; mutable.value = AdminState(open = true) }
            else -> close()
        }
    }
    fun openList(list: AdminList, filters: Map<String, String> = emptyMap(), q: String = "") {
        sequence++; mutable.value = AdminState(open = true, list = list, filters = filters, q = q); reload()
    }
    fun setQuery(value: String) { mutable.value = mutable.value.copy(q = value) }
    fun setFilter(key: String, value: String?) {
        val s = mutable.value
        mutable.value = s.copy(filters = if (value.isNullOrEmpty()) s.filters - key else s.filters + (key to value))
    }
    fun select(id: Int) { mutable.value = mutable.value.copy(selectedId = id, actionError = null, actionUnknown = false, actionDone = false, approval = null) }
    fun reload() = load(append = false)
    fun more() { val s = mutable.value; if (!s.loading && s.items.size < s.total) load(append = true) }

    private fun HttpRequestBuilder.listParameters(s: AdminState) {
        val list = s.list ?: return
        s.q.trim().takeIf { list.hasQ && it.isNotEmpty() }?.let { parameter("q", it) }
        s.filters.forEach { (key, value) -> if (key in list.filters && value.trim().isNotEmpty()) parameter(key, value.trim()) }
    }

    private fun load(append: Boolean) {
        val current = mutable.value; val list = current.list ?: return
        val expected = session.generation; val request = ++sequence
        mutable.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = requests.request { token ->
                    val response = client.get("$baseUrl${list.path}") {
                        bearerAuth(token); listParameters(current)
                        parameter("limit", 50); parameter("offset", if (append) current.items.size else 0)
                    }
                    response.expect(200); response.body<AdminPage>()
                }
                if (expected == session.generation && request == sequence) mutable.value = mutable.value.copy(loading = false, total = page.total,
                    items = (if (append) current.items + page.items else page.items).distinctBy { it.id })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || request != sequence) return@launch
                mutable.value = mutable.value.copy(loading = false, error = describe(error), denied = isDenied(error))
            }
        }
    }

    /** Same request with ?format=csv (every matching row); the file is handed to the share sheet by the page. */
    fun exportCsv() {
        val current = mutable.value; val list = current.list ?: return
        if (current.csvBusy) return
        val expected = session.generation
        mutable.value = current.copy(csvBusy = true, csvError = null)
        viewModelScope.launch {
            try {
                val file = requests.request { token ->
                    val response = client.get("$baseUrl${list.path}") { bearerAuth(token); listParameters(current); parameter("format", "csv") }
                    response.expect(200)
                    val date = kotlinx.datetime.Clock.System.now().toString().take(10)
                    AdminCsv(response.body<ByteArray>(), adminCsvFileName(response.headers[HttpHeaders.ContentDisposition], list.key, date))
                }
                if (expected == session.generation) mutable.value = mutable.value.copy(csvBusy = false, csv = file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation) mutable.value = mutable.value.copy(csvBusy = false, csvError = describe(error), denied = isDenied(error))
            }
        }
    }
    fun csvHandled(failed: Boolean) { mutable.value = mutable.value.copy(csv = null, csvError = if (failed) "share" else null) }

    /** Authorized JSON call; returns the response object. Only the documented success status is accepted. */
    internal suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null): JsonObject = requests.request { token ->
        val response = client.request("$baseUrl$path") {
            this.method = method; bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        response.expect(200)
        adminJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    internal suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement = requests.request { token ->
        val response = client.get("$baseUrl$path") { bearerAuth(token); params.forEach { (k, v) -> parameter(k, v) } }
        response.expect(200)
        adminJson.parseToJsonElement(response.bodyAsText())
    }

    /**
     * One action on the item [id] of the open list. A definite answer replaces the item (when [replaces]) or shows the error;
     * an unknown result (network/5xx) is never retried: [verify] re-reads the documented GET and the item is updated from it.
     */
    internal fun action(id: Int, method: HttpMethod, path: String, body: JsonObject? = null, replaces: Boolean = true,
        verify: (suspend () -> JsonObject?)? = null, onDone: (JsonObject) -> Unit = {}) {
        val current = mutable.value
        if (current.busy) return
        val expected = session.generation
        mutable.value = current.copy(busy = true, actionError = null, actionUnknown = false, actionDone = false)
        viewModelScope.launch {
            try {
                val result = call(method, path, body)
                if (expected != session.generation) return@launch
                if (replaces) replaceItem(id, result)
                mutable.value = mutable.value.copy(busy = false, actionDone = true)
                onDone(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation) return@launch
                val failure = failure(error)
                if (failure != null) { mutable.value = mutable.value.copy(busy = false, actionError = failure, denied = isDenied(error)); return@launch }
                mutable.value = mutable.value.copy(actionUnknown = true)
                try { verify?.invoke()?.let { if (expected == session.generation) replaceItem(id, it) } } catch (readError: Exception) {
                    if (readError is CancellationException) throw readError
                }
                if (expected == session.generation) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    /** Re-reads the open list's endpoint with [params] (e.g. q=<id>) and returns the row with this exact id. */
    internal suspend fun findInList(path: String, id: Int, params: Map<String, String>): JsonObject? {
        val page = adminJson.decodeFromJsonElement(AdminPage.serializer(), get(path, params + ("limit" to "200")))
        return page.items.firstOrNull { it.id == id }
    }
    private fun replaceItem(id: Int, item: JsonObject) {
        if (item.id != id) return
        val s = mutable.value
        mutable.value = s.copy(items = s.items.map { if (it.id == id) item else it })
    }
    internal fun update(transform: (AdminState) -> AdminState) { mutable.value = transform(mutable.value) }
    internal val generationNow: Long get() = session.generation

    private suspend fun HttpResponse.expect(code: Int) { if (status.value != code) throw parseSupportError(status.value, bodyAsText()) }
    internal fun failure(error: Exception): AdminFailure? = when (error) {
        is SupportApiException -> if (error.statusCode >= 500) null else AdminFailure(error.statusCode, error.code, error.message)
        is OrdersApiException -> AdminFailure(error.statusCode, error.errorCode, error.message)
        else -> null
    }
    internal fun isDenied(error: Exception): Boolean {
        val f = failure(error) ?: return false
        return f.status == 403 && (f.code == "PlatformAdminRequiredError" || f.code == null)
    }
    internal fun describe(error: Exception): String = failure(error)?.let { "HTTP ${it.status}: ${it.code ?: it.message}" } ?: "network"
}

@Composable
internal fun adminFailureText(failure: AdminFailure): String = when {
    failure.code == "PlatformAdminRequiredError" || failure.status == 403 && failure.code == null -> stringResource(Res.string.admin_denied)
    failure.status == 422 || failure.code == "VALIDATION_ERROR" -> stringResource(Res.string.admin_invalid)
    failure.code == "OrganizerNotFoundError" -> stringResource(Res.string.admin_no_application)
    failure.code == "UserNotFoundError" -> stringResource(Res.string.admin_user_not_found)
    else -> "${stringResource(Res.string.admin_action_failed)} (${failure.code ?: "HTTP ${failure.status}"})"
}

@Composable
private fun adminListTitle(list: AdminList): String = when (list) {
    AdminList.Users -> stringResource(Res.string.admin_users)
}

@Composable
private fun filterLabel(key: String): String = when (key) {
    "suspended" -> stringResource(Res.string.admin_filter_suspended)
    "status" -> stringResource(Res.string.admin_filter_status)
    "event_id" -> stringResource(Res.string.admin_filter_event)
    else -> key
}

@Composable
fun AdminPage(vm: AdminViewModel) {
    val state by vm.state.collectAsState()
    val sharer = rememberFileSharer()
    LaunchedEffect(state.csv) { state.csv?.let { vm.csvHandled(!sharer.share(it.bytes, it.fileName, "text/csv")) } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = vm::back, enabled = !state.busy) { Text(stringResource(Res.string.create_back)) } }
        val list = state.list
        val selected = state.selected
        when {
            list == null -> item {
                Text(stringResource(Res.string.admin_title), style = MaterialTheme.typography.headlineMedium)
                AdminList.entries.forEach { entry -> OutlinedButton(onClick = { vm.openList(entry) }, modifier = Modifier.fillMaxWidth()) { Text(adminListTitle(entry)) } }
            }
            selected != null -> item {
                AdminDetail(vm, state, list, selected)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.actionError?.let { Text(adminFailureText(it), color = MaterialTheme.colorScheme.error) }
                if (state.actionUnknown) Text(stringResource(Res.string.admin_result_unknown), color = MaterialTheme.colorScheme.error)
                if (state.actionDone) Text(stringResource(Res.string.admin_done))
            }
            else -> {
                item {
                    Text(adminListTitle(list), style = MaterialTheme.typography.headlineMedium)
                    if (list.hasQ) OutlinedTextField(state.q, vm::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.admin_query)) })
                    list.filters.forEach { (key, options) ->
                        if (options == null) OutlinedTextField(state.filters[key].orEmpty(), { value -> vm.setFilter(key, value.filter(Char::isDigit)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(filterLabel(key)) })
                        else {
                            Text(filterLabel(key))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected = state.filters[key] == null, onClick = { vm.setFilter(key, null) }, label = { Text(stringResource(Res.string.admin_any)) })
                                options.forEach { option -> FilterChip(selected = state.filters[key] == option, onClick = { vm.setFilter(key, option) }, label = { Text(option) }) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.insights_apply)) }
                        OutlinedButton(onClick = vm::exportCsv, enabled = !state.csvBusy) { Text(stringResource(Res.string.admin_csv)) }
                    }
                    if (state.loading || state.csvBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.csvError?.let { Text("${stringResource(Res.string.admin_csv_failed)} ($it)", color = MaterialTheme.colorScheme.error) }
                    state.error?.let { Text(stringResource(Res.string.insights_error), color = MaterialTheme.colorScheme.error); Text(it) }
                    Text("${stringResource(Res.string.admin_total)}: ${state.total}")
                    if (!state.loading && state.error == null && state.items.isEmpty()) Text(stringResource(Res.string.history_empty))
                }
                items(state.items, key = { it.id ?: it.hashCode() }) { row ->
                    Card(onClick = { row.id?.let(vm::select) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { AdminRow(list, row) } }
                }
                if (state.items.size < state.total) item {
                    TextButton(onClick = vm::more, enabled = !state.loading) { Text("${stringResource(Res.string.history_more)} (${state.items.size}/${state.total})") }
                }
            }
        }
    }
}

@Composable
private fun AdminRow(list: AdminList, row: JsonObject) {
    when (list) {
        AdminList.Users -> {
            Text("#${row.id} ${row.text("email").orEmpty()}")
            if (row.flag("is_platform_admin")) Text(stringResource(Res.string.profile_admin))
            Text(stringResource(if (row.text("email_verified_at") != null) Res.string.profile_email_verified else Res.string.profile_email_unverified))
            row.text("suspended_at")?.let { Text("${stringResource(Res.string.admin_suspended)}: $it · ${row.text("suspension_reason").orEmpty()}", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AdminDetail(vm: AdminViewModel, state: AdminState, list: AdminList, row: JsonObject) {
    AdminRow(list, row)
    val id = row.id ?: return
    when (list) {
        AdminList.Users -> {
            OutlinedButton(onClick = { vm.approveOrganizer(id) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_approve_organizer)) }
            state.approval?.let { Text("${stringResource(Res.string.admin_organizer_status)}: $it") }
            if (state.actionUnknown && state.approval == null) Text(stringResource(Res.string.admin_approve_unknown))
        }
    }
}

/** 55.2: there is no list of pending organizer applications in the backend; the admin finds the user by email and approves. */
fun AdminViewModel.approveOrganizer(userId: Int) {
    if (state.value.busy) return
    val expected = generationNow
    update { it.copy(busy = true, actionError = null, actionUnknown = false, actionDone = false, approval = null) }
    viewModelScope.launch {
        try {
            // path takes the user id, not the organizer profile id; approving an approved profile is a no-op on the server
            val result = call(HttpMethod.Patch, "/organizers/$userId/approve")
            if (expected != generationNow) return@launch
            val ok = (result["user_id"] as? JsonPrimitive)?.intOrNull == userId
            update { it.copy(busy = false, actionDone = ok, actionUnknown = !ok, approval = result.text("status")) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected != generationNow) return@launch
            val failure = failure(error)
            update { it.copy(busy = false, actionError = failure, actionUnknown = failure == null, denied = isDenied(error)) }
        }
    }
}
