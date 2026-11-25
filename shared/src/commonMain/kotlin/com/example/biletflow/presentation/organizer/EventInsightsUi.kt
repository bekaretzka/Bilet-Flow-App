package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.Card
import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.data.repositories.SessionOrdersRepository
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Serializable data class AnalyticsCapacity(val capacity: Long? = null, val sold: Long, val held: Long, val remaining: Long? = null,
    @SerialName("percent_sold") val percentSold: Double? = null)
@Serializable data class AnalyticsMoney(val gross: Long, val discounts: Long, val refunds: Long,
    @SerialName("processing_fees") val processingFees: Long, val net: Long, @SerialName("paid_orders") val paidOrders: Long)
@Serializable data class AnalyticsCheckIn(val tickets: Long, @SerialName("checked_in") val checkedIn: Long, val absent: Long, val percent: Double? = null)
@Serializable data class AnalyticsDay(val date: String, val orders: Long, val tickets: Long, val revenue: Long)
@Serializable data class AnalyticsTicketType(@SerialName("ticket_type_id") val ticketTypeId: Int, val name: String, val price: Long,
    val quantity: Long, val sold: Long, val revenue: Long, val held: Long, @SerialName("checked_in") val checkedIn: Long)
@Serializable data class AnalyticsCampaign(@SerialName("campaign_id") val campaignId: Int, val code: String, val name: String,
    @SerialName("is_active") val isActive: Boolean, @SerialName("link_opens") val linkOpens: Long, val orders: Long, val tickets: Long,
    val gross: Long, val discount: Long, val net: Long)
@Serializable data class EventAnalytics(@SerialName("event_id") val eventId: Int, val currency: String,
    @SerialName("is_simulated") val isSimulated: Boolean, val capacity: AnalyticsCapacity, val money: AnalyticsMoney,
    @SerialName("check_in") val checkIn: AnalyticsCheckIn, @SerialName("sales_over_time") val salesOverTime: List<AnalyticsDay>,
    @SerialName("ticket_types") val ticketTypes: List<AnalyticsTicketType>, val campaigns: List<AnalyticsCampaign>)
@Serializable data class HistoryItem(val id: Int, @SerialName("created_at") val createdAt: String, val action: String, val type: String,
    @SerialName("entity_type") val entityType: String, @SerialName("entity_id") val entityId: Int? = null, val description: String,
    @SerialName("actor_user_id") val actorUserId: Int? = null, @SerialName("actor_email") val actorEmail: String? = null)
@Serializable data class HistoryPage(val items: List<HistoryItem>, val total: Int)

val historyTypes = listOf("event", "ticket_type", "order", "ticket", "campaign", "staff", "paid_sales")

data class EventInsightsState(
    val eventId: Int? = null, val title: String = "", val history: Boolean = false,
    val filters: Map<String, String> = emptyMap(), val loading: Boolean = false, val error: String? = null, val denied: Boolean = false,
    val analytics: EventAnalytics? = null, val items: List<HistoryItem> = emptyList(), val total: Int = 0,
)

/** Organizer analytics and history. Filters are applied only on explicit refresh; stale responses are dropped. */
class EventInsightsViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(EventInsightsState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var sequence = 0
    init { viewModelScope.launch { session.tokens.collect { if (generation != session.generation) { generation = session.generation; close() } } } }
    fun close() { sequence++; mutable.value = EventInsightsState() }
    fun openAnalytics(id: Int, title: String) { close(); mutable.value = EventInsightsState(eventId = id, title = title); reload() }
    fun openHistory(id: Int, title: String) { close(); mutable.value = EventInsightsState(eventId = id, title = title, history = true); reload() }
    fun updateFilter(key: String, value: String) { mutable.value = mutable.value.copy(filters = mutable.value.filters + (key to value)) }
    fun reload() = load(append = false)
    fun more() { val s = mutable.value; if (s.history && s.items.size < s.total) load(append = true) }
    private fun load(append: Boolean) {
        val current = mutable.value; val id = current.eventId ?: return
        if (current.loading && append) return
        val expected = session.generation; val request = ++sequence
        val filters = current.filters.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        mutable.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                if (current.history) {
                    val page = requests.request { token ->
                        val response = client.get("$baseUrl/events/$id/history") {
                            bearerAuth(token)
                            listOf("type", "action", "date_from", "date_to").forEach { key -> filters[key]?.let { parameter(key, it) } }
                            parameter("limit", 50); parameter("offset", if (append) current.items.size else 0)
                        }
                        if (response.status.value != 200) throw insightsError(response.status.value, response.bodyAsText())
                        response.body<HistoryPage>()
                    }
                    if (expected == session.generation && request == sequence) mutable.value = mutable.value.copy(loading = false, total = page.total,
                        items = if (append) (current.items + page.items).distinctBy { it.id } else page.items)
                } else {
                    val data = requests.request { token ->
                        val response = client.get("$baseUrl/events/$id/analytics") {
                            bearerAuth(token)
                            listOf("date_from", "date_to", "ticket_type_id").forEach { key -> filters[key]?.let { parameter(key, it) } }
                        }
                        if (response.status.value != 200) throw insightsError(response.status.value, response.bodyAsText())
                        response.body<EventAnalytics>()
                    }
                    if (expected == session.generation && request == sequence) mutable.value = mutable.value.copy(loading = false, analytics = data)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || request != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(loading = false, error = api?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network",
                    denied = api?.statusCode == 403)
            }
        }
    }
}

internal fun insightsError(status: Int, text: String): OrdersApiException {
    val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    return OrdersApiException(status, (body?.get("error") as? JsonPrimitive)?.contentOrNull,
        (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: (body?.get("detail") as? JsonPrimitive)?.contentOrNull ?: "HTTP $status")
}

private fun percent(value: Double?) = value?.let { "$it%" } ?: "—"

@Composable
fun EventInsightsPage(vm: EventInsightsViewModel) {
    val state by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = vm::close) { Text(stringResource(Res.string.create_back)) }
            Text(stringResource(if (state.history) Res.string.history_title else Res.string.analytics_title), style = MaterialTheme.typography.headlineMedium)
            Text(state.title)
            val keys = if (state.history) listOf("type", "action", "date_from", "date_to") else listOf("date_from", "date_to", "ticket_type_id")
            keys.forEach { key ->
                OutlinedTextField(state.filters[key].orEmpty(), { vm.updateFilter(key, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(when (key) {
                        "type" -> Res.string.history_type; "action" -> Res.string.history_action
                        "ticket_type_id" -> Res.string.analytics_ticket_type; "date_from" -> Res.string.insights_date_from
                        else -> Res.string.insights_date_to })) })
            }
            if (state.history) Text("${stringResource(Res.string.history_types_help)}: ${historyTypes.joinToString()}")
            Button(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.insights_apply)) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(Res.string.insights_error), color = MaterialTheme.colorScheme.error); Text(it) }
        }
        if (state.history) {
            if (!state.loading && state.error == null && state.items.isEmpty()) item { Text(stringResource(Res.string.history_empty)) }
            items(state.items, key = { it.id }) { entry -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(entry.description)
                Text("${entry.createdAt} · ${entry.action}", style = MaterialTheme.typography.bodySmall)
                Text(entry.actorEmail ?: stringResource(Res.string.history_automatic), style = MaterialTheme.typography.bodySmall)
            } } }
            if (state.items.size < state.total) item {
                TextButton(onClick = vm::more, enabled = !state.loading) { Text("${stringResource(Res.string.history_more)} (${state.items.size}/${state.total})") }
            }
        } else state.analytics?.let { a ->
            val cur = if (a.currency == "KZT") "₸" else a.currency
            item {
                if (a.isSimulated) com.example.biletflow.core.designsystem.DemoBanner(stringResource(Res.string.sales_simulated))
                Text(stringResource(Res.string.analytics_capacity), style = MaterialTheme.typography.titleMedium)
                Text("${stringResource(Res.string.create_capacity)}: ${a.capacity.capacity ?: "—"} · ${stringResource(Res.string.sales_sold)}: ${a.capacity.sold} · ${stringResource(Res.string.sales_reserved)}: ${a.capacity.held}")
                Text("${stringResource(Res.string.sales_available)}: ${a.capacity.remaining ?: "—"} · ${percent(a.capacity.percentSold)}")
                Text(stringResource(Res.string.analytics_money), style = MaterialTheme.typography.titleMedium)
                Text("${stringResource(Res.string.sales_gross)}: ${a.money.gross} $cur · ${stringResource(Res.string.sales_discounts)}: ${a.money.discounts} $cur")
                Text("${stringResource(Res.string.sales_refunds)}: ${a.money.refunds} $cur · ${stringResource(Res.string.sales_fees)}: ${a.money.processingFees} $cur")
                Text("${stringResource(Res.string.campaigns_stat_net)}: ${a.money.net} $cur · ${stringResource(Res.string.sales_paid_orders)}: ${a.money.paidOrders}")
                Text(stringResource(Res.string.analytics_check_in), style = MaterialTheme.typography.titleMedium)
                Text("${stringResource(Res.string.campaigns_stat_tickets)}: ${a.checkIn.tickets} · ${stringResource(Res.string.sales_checked_in)}: ${a.checkIn.checkedIn} · ${stringResource(Res.string.analytics_absent)}: ${a.checkIn.absent} · ${percent(a.checkIn.percent)}")
                Text(stringResource(Res.string.analytics_over_time), style = MaterialTheme.typography.titleMedium)
                if (a.salesOverTime.isEmpty()) Text(stringResource(Res.string.history_empty))
            }
            val maxRevenue = (a.salesOverTime.maxOfOrNull { it.revenue } ?: 0L).coerceAtLeast(1L)
            items(a.salesOverTime, key = { it.date }) { day ->
                Column {
                    Text("${day.date}: ${day.revenue} $cur · ${day.orders} / ${day.tickets}", style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.fillMaxWidth((day.revenue.toFloat() / maxRevenue).coerceIn(0.02f, 1f)).height(8.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
            item { Text(stringResource(Res.string.sales_types), style = MaterialTheme.typography.titleMedium) }
            items(a.ticketTypes, key = { "t${it.ticketTypeId}" }) { t ->
                Text("${t.name} (${t.price} $cur): ${stringResource(Res.string.sales_sold)} ${t.sold}/${t.quantity}, ${stringResource(Res.string.sales_reserved)} ${t.held}, ${stringResource(Res.string.sales_checked_in)} ${t.checkedIn}, ${t.revenue} $cur")
            }
            item { Text(stringResource(Res.string.campaigns_title), style = MaterialTheme.typography.titleMedium) }
            items(a.campaigns, key = { "c${it.campaignId}" }) { c ->
                Text("${c.code} · ${c.name} · ${stringResource(if (c.isActive) Res.string.campaigns_active else Res.string.campaigns_inactive)} · ${stringResource(Res.string.campaigns_link_opens)} ${c.linkOpens} · ${c.orders}/${c.tickets} · ${c.gross}−${c.discount}=${c.net} $cur")
            }
        }
    }
}
