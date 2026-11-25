package com.example.biletflow.presentation.admin

import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.OutlinedButton
import com.example.biletflow.core.designsystem.Button
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
    Events("/admin/events", "events", true, mapOf("status" to listOf("draft", "published", "cancelled"), "suspended" to listOf("true", "false"))),
    Reports("/admin/event-reports", "event-reports", false, mapOf("status" to listOf("open", "dismissed", "actioned"), "event_id" to null)),
    Orders("/admin/orders", "orders", true, mapOf("status" to listOf("pending", "confirmed", "cancelled", "refunded", "expired"), "event_id" to null)),
    Payments("/admin/payments", "payments", true, mapOf("status" to listOf("succeeded", "failed"), "purpose" to listOf("order", "activation_fee"), "event_id" to null)),
    Refunds("/admin/refunds", "refunds", true, mapOf("event_id" to null)),
    Campaigns("/admin/campaigns", "campaigns", true, mapOf("event_id" to null, "active" to listOf("true", "false"))),
    Organizers("/admin/organizers", "organizers", true, mapOf("status" to listOf("pending", "approved", "rejected"))),
    AuditLog("/admin/audit-log", "audit-log", false, mapOf("type" to null, "action" to null, "actor_user_id" to null, "event_id" to null,
        "entity_type" to null, "entity_id" to null, "date_from" to null, "date_to" to null)),
}

data class AdminCsv(val bytes: ByteArray, val fileName: String)
/** A definite server answer: [code] is the backend "error" (or an older detail ending in Error). */
data class AdminFailure(val status: Int, val code: String?, val message: String)

data class AdminState(
    val open: Boolean = false, val list: AdminList? = null, val q: String = "", val filters: Map<String, String> = emptyMap(),
    val items: List<JsonObject> = emptyList(), val total: Int = 0, val loading: Boolean = false, val error: String? = null,
    val csvBusy: Boolean = false, val csv: AdminCsv? = null, val csvError: String? = null, val denied: Boolean = false,
    val selectedId: Int? = null, val busy: Boolean = false, val actionError: AdminFailure? = null, val actionUnknown: Boolean = false,
    val actionDone: Boolean = false, val approval: String? = null, val reason: String = "", val reasonInvalid: Boolean = false,
    val myId: Int? = null,
    /** 55.7 screens that are not {items,total} lists: "paid_sales" or "settings". */
    val section: String? = null, val paidStatus: String? = null, val activations: List<JsonObject> = emptyList(),
    val feeKzt: Long? = null, val feeDraft: String = "", val feeInvalid: Boolean = false,
) {
    val selected: JsonObject? get() = selectedId?.let { id -> items.firstOrNull { it.id == id } }
}

internal val JsonObject.id: Int? get() = (this["id"] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** Free filter text; a date-only date_from/date_to (YYYY-MM-DD) covers the whole day. */
internal fun filterValue(key: String, value: String): String = when {
    key == "date_from" && value.length == 10 -> "${value}T00:00:00"
    key == "date_to" && value.length == 10 -> "${value}T23:59:59"
    else -> value
}

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
            s.list != null || s.section != null -> { sequence++; mutable.value = AdminState(open = true) }
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
    fun select(id: Int) { mutable.value = mutable.value.copy(selectedId = id, actionError = null, actionUnknown = false, actionDone = false, approval = null,
        reason = "", reasonInvalid = false, myId = session.user.value?.id) }
    fun reload() = load(append = false)
    fun more() { val s = mutable.value; if (!s.loading && s.items.size < s.total) load(append = true) }

    private fun HttpRequestBuilder.listParameters(s: AdminState) {
        val list = s.list ?: return
        s.q.trim().takeIf { list.hasQ && it.isNotEmpty() }?.let { parameter("q", it) }
        s.filters.forEach { (key, value) -> if (key in list.filters && value.trim().isNotEmpty()) parameter(key, filterValue(key, value.trim())) }
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
    failure.code == "CannotSuspendSelfError" -> stringResource(Res.string.admin_cannot_suspend_self)
    failure.code == "InvalidOrganizerStatusError" -> stringResource(Res.string.admin_organizer_status_invalid)
    else -> "${stringResource(Res.string.admin_action_failed)} (${failure.code ?: "HTTP ${failure.status}"})"
}

@Composable
private fun adminListTitle(list: AdminList): String = when (list) {
    AdminList.Users -> stringResource(Res.string.admin_users)
    AdminList.Events -> stringResource(Res.string.admin_events)
    AdminList.Reports -> stringResource(Res.string.admin_reports)
    AdminList.Orders -> stringResource(Res.string.admin_orders)
    AdminList.Payments -> stringResource(Res.string.admin_payments)
    AdminList.Refunds -> stringResource(Res.string.admin_refunds)
    AdminList.Campaigns -> stringResource(Res.string.campaigns_title)
    AdminList.Organizers -> stringResource(Res.string.admin_organizers)
    AdminList.AuditLog -> stringResource(Res.string.admin_audit_log)
}

@Composable
private fun filterLabel(key: String): String = when (key) {
    "suspended" -> stringResource(Res.string.admin_filter_suspended)
    "status" -> stringResource(Res.string.admin_filter_status)
    "event_id" -> stringResource(Res.string.admin_filter_event)
    "purpose" -> stringResource(Res.string.admin_filter_purpose)
    "active" -> stringResource(Res.string.admin_filter_active)
    "type" -> stringResource(Res.string.admin_filter_type)
    "action" -> stringResource(Res.string.admin_filter_action)
    "actor_user_id" -> stringResource(Res.string.admin_filter_actor)
    "date_from" -> stringResource(Res.string.admin_filter_date_from)
    "date_to" -> stringResource(Res.string.admin_filter_date_to)
    else -> key
}

@Composable
fun AdminPage(vm: AdminViewModel, onEventHistory: (Int, String) -> Unit, onSupportInbox: () -> Unit) {
    val state by vm.state.collectAsState()
    val sharer = rememberFileSharer()
    LaunchedEffect(state.csv) { state.csv?.let { vm.csvHandled(!sharer.share(it.bytes, it.fileName, "text/csv")) } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = vm::back, enabled = !state.busy) { Text(stringResource(Res.string.create_back)) } }
        val list = state.list
        val selected = state.selected
        when {
            state.section == "paid_sales" -> item { PaidSalesSection(vm, state) }
            state.section == "settings" -> item { SettingsSection(vm, state) }
            list == null -> item {
                Text(stringResource(Res.string.admin_title), style = MaterialTheme.typography.headlineMedium)
                AdminList.entries.forEach { entry -> OutlinedButton(onClick = { vm.openList(entry) }, modifier = Modifier.fillMaxWidth()) { Text(adminListTitle(entry)) } }
                OutlinedButton(onClick = onSupportInbox, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.support_admin_inbox)) }
                OutlinedButton(onClick = { vm.openSection("paid_sales") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.admin_paid_sales)) }
                OutlinedButton(onClick = { vm.openSection("settings") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.admin_settings)) }
            }
            selected != null -> item {
                AdminDetail(vm, state, list, selected, onEventHistory)
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
                        if (options == null) OutlinedTextField(state.filters[key].orEmpty(), { value -> vm.setFilter(key, if (key.endsWith("_id")) value.filter(Char::isDigit) else value) },
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
        AdminList.Events -> {
            Text("#${row.id} ${row.text("title").orEmpty()}")
            Text("${row.text("status").orEmpty()} · ${row.text("start_time").orEmpty()} · ${stringResource(Res.string.admin_organizer)} #${row.text("organizer_id").orEmpty()}")
            row.text("venue_name")?.let { Text(it) }
            row.text("suspended_at")?.let { Text("${stringResource(Res.string.admin_suspended)}: $it · ${row.text("suspension_reason").orEmpty()}", color = MaterialTheme.colorScheme.error) }
        }
        AdminList.Reports -> {
            Text("#${row.id} · ${stringResource(Res.string.support_event)} #${row.text("event_id").orEmpty()} · ${row.text("status").orEmpty()}")
            Text("${row.text("reason").orEmpty()} · ${row.text("created_at").orEmpty()}")
            row.text("details")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            row.text("reviewed_at")?.let { Text("${stringResource(Res.string.admin_reviewed)}: $it · #${row.text("reviewed_by_user_id").orEmpty()} · ${row.text("review_note").orEmpty()}", style = MaterialTheme.typography.bodySmall) }
        }
        AdminList.Organizers -> {
            Text("#${row.id} · ${row.text("email").orEmpty()} · ${row.text("status").orEmpty()}")
            Text("${stringResource(Res.string.admin_user)} #${row.text("user_id").orEmpty()} · ${row.text("display_name") ?: row.text("legal_name") ?: "—"}")
            row.text("contact_phone")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            row.text("identity_verified_at")?.let { Text("${stringResource(Res.string.admin_identity_verified)}: $it", style = MaterialTheme.typography.bodySmall) }
            row.text("rejection_reason")?.let { Text(stringResource(Res.string.organizer_rejection_reason, it), color = MaterialTheme.colorScheme.error) }
        }
        AdminList.AuditLog -> {
            Text("#${row.id} · ${row.text("action").orEmpty()} · ${row.text("created_at").orEmpty()}")
            Text(row.text("description").orEmpty())
            Text(listOfNotNull(
                "${row.text("entity_type").orEmpty()} #${row.text("entity_id") ?: "—"}",
                row.text("event_id")?.let { "${stringResource(Res.string.support_event)} #$it" },
                (row.text("actor_email") ?: row.text("actor_user_id")?.let { "#$it" })?.let { "${stringResource(Res.string.admin_actor)}: $it" },
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        else -> FinanceRow(list, row)
    }
}

@Composable
private fun AdminDetail(vm: AdminViewModel, state: AdminState, list: AdminList, row: JsonObject, onEventHistory: (Int, String) -> Unit) {
    AdminRow(list, row)
    val id = row.id ?: return
    when (list) {
        AdminList.Users -> {
            OutlinedButton(onClick = { vm.approveOrganizer(id) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_approve_organizer)) }
            state.approval?.let { Text("${stringResource(Res.string.admin_organizer_status)}: $it") }
            if (state.actionUnknown && state.approval == null) Text(stringResource(Res.string.admin_approve_unknown))
            // no suspension offered for my own account; the server still answers 400 CannotSuspendSelfError
            if (id != state.myId) SuspendControls(vm, state, id, row.text("suspended_at") != null)
        }
        // Details come from the list row: public GET /events/{id} answers 404 for drafts and suspended events.
        AdminList.Events -> {
            listOf("description", "category", "venue_address", "end_time", "visibility", "capacity", "registration_opens_at", "registration_closes_at", "refund_policy", "lifecycle")
                .forEach { key -> row.text(key)?.let { Text("$key: $it", style = MaterialTheme.typography.bodySmall) } }
            OutlinedButton(onClick = { onEventHistory(id, row.text("title").orEmpty()) }) { Text(stringResource(Res.string.history_title)) }
            SuspendControls(vm, state, id, row.text("suspended_at") != null)
        }
        AdminList.Reports -> {
            val eventId = (row["event_id"] as? JsonPrimitive)?.intOrNull
            // suspending the event is a separate action on the event card
            if (eventId != null) OutlinedButton(onClick = { vm.openList(AdminList.Events, q = "$eventId") }) { Text(stringResource(Res.string.admin_open_event)) }
            OutlinedTextField(state.reason, vm::setReason, enabled = !state.busy, isError = state.reasonInvalid, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.admin_note)) }, supportingText = { Text("${state.reason.trim().length}/2000") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dismissed", "actioned").forEach { status -> Button(onClick = { vm.reviewReport(id, eventId, status) }, enabled = !state.busy) {
                    Text(stringResource(if (status == "dismissed") Res.string.admin_report_dismiss else Res.string.admin_report_action)) } }
            }
        }
        AdminList.Organizers -> {
            val userId = (row["user_id"] as? JsonPrimitive)?.intOrNull ?: return
            val status = row.text("status")
            if (status != "approved") OutlinedButton(onClick = { vm.approveOrganizer(userId, organizerRowId = id) }, enabled = !state.busy) {
                Text(stringResource(Res.string.admin_approve_organizer)) }
            if (status == "pending") {
                OutlinedTextField(state.reason, vm::setReason, enabled = !state.busy, isError = state.reasonInvalid, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.admin_reason)) }, supportingText = { Text("${state.reason.trim().length}/500") })
                if (state.reasonInvalid) Text(stringResource(Res.string.admin_reason_invalid), color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.rejectOrganizer(id, userId) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_reject_organizer)) }
            }
            // an approved organizer is suspended as a user instead of rejected
            if (status == "approved") OutlinedButton(onClick = { vm.openList(AdminList.Users, q = "$userId") }) { Text(stringResource(Res.string.admin_open_user)) }
        }
        AdminList.AuditLog -> {
            val eventId = (row["event_id"] as? JsonPrimitive)?.intOrNull
            if (eventId != null) OutlinedButton(onClick = { vm.openList(AdminList.Events, q = "$eventId") }) { Text(stringResource(Res.string.admin_open_event)) }
        }
        else -> Unit
    }
}

/** Organizer answers carry only the profile fields; they are merged into the AdminOrganizer row (email stays). */
private fun AdminViewModel.mergeOrganizerRow(rowId: Int, result: JsonObject) {
    if (result.id != rowId) return
    update { s -> s.copy(items = s.items.map { if (it.id == rowId) JsonObject(it + result) else it }) }
}

/**
 * POST /admin/organizers/{user_id}/reject {reason 3-500} → Organizer; pending applications only (409 InvalidOrganizerStatusError).
 * Unknown result: the organizers list is re-read with q=<user id> and the row is taken from there.
 */
fun AdminViewModel.rejectOrganizer(rowId: Int, userId: Int) {
    val s = state.value
    if (!validReason(s.reason)) { update { it.copy(reasonInvalid = true) }; return }
    action(rowId, HttpMethod.Post, "/admin/organizers/$userId/reject", body = buildJsonObject { put("reason", s.reason.trim()) }, replaces = false,
        verify = { findInList(AdminList.Organizers.path, rowId, mapOf("q" to "$userId")) },
        onDone = { result -> mergeOrganizerRow(rowId, result); update { it.copy(reason = "") } })
}

/** 55.2/57.4: PATCH /organizers/{user_id}/approve works on pending and rejected applications. */
fun AdminViewModel.approveOrganizer(userId: Int, organizerRowId: Int? = null) {
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
            if (ok && organizerRowId != null) mergeOrganizerRow(organizerRowId, result)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected != generationNow) return@launch
            val failure = failure(error)
            update { it.copy(busy = false, actionError = failure, actionUnknown = failure == null, denied = isDenied(error)) }
        }
    }
}

/** Reason for a suspension: backend SuspendRequest, 3-500 characters. */
internal fun validReason(value: String): Boolean = value.trim().length in 3..500

/**
 * POST {base}/{id}/suspend {reason} or /unsuspend for the open list's row; the answer (AdminUser or Event) replaces the row.
 * Unknown result: the list is re-read with q=<id> (a numeric q matches the id) and the row is taken from there.
 */
fun AdminViewModel.setSuspended(id: Int, suspend: Boolean) {
    val s = state.value; val list = s.list ?: return
    if (suspend && !validReason(s.reason)) { update { it.copy(reasonInvalid = true) }; return }
    val base = list.path
    action(id, HttpMethod.Post, "$base/$id/${if (suspend) "suspend" else "unsuspend"}",
        body = if (suspend) buildJsonObject { put("reason", s.reason.trim()) } else null,
        verify = { findInList(base, id, mapOf("q" to "$id")) }, onDone = { update { it.copy(reason = "") } })
}
fun AdminViewModel.setReason(value: String) { update { it.copy(reason = value, reasonInvalid = false) } }

@Composable
internal fun SuspendControls(vm: AdminViewModel, state: AdminState, id: Int, suspended: Boolean) {
    if (suspended) Button(onClick = { vm.setSuspended(id, false) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_unsuspend)) }
    else {
        OutlinedTextField(state.reason, vm::setReason, enabled = !state.busy, isError = state.reasonInvalid, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.admin_reason)) }, supportingText = { Text("${state.reason.trim().length}/500") })
        if (state.reasonInvalid) Text(stringResource(Res.string.admin_reason_invalid), color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.setSuspended(id, true) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_suspend)) }
    }
}

/** POST /admin/event-reports/{id}/review {status, note?}. Unknown result: re-read reports filtered by event_id and find this id. */
fun AdminViewModel.reviewReport(id: Int, eventId: Int?, status: String) {
    val note = state.value.reason.trim()
    if (note.length > 2000) { update { it.copy(reasonInvalid = true) }; return }
    action(id, HttpMethod.Post, "/admin/event-reports/$id/review",
        body = buildJsonObject { put("status", status); if (note.isNotEmpty()) put("note", note) },
        verify = { findInList("/admin/event-reports", id, eventId?.let { mapOf("event_id" to "$it") } ?: emptyMap()) },
        onDone = { update { it.copy(reason = "") } })
}
