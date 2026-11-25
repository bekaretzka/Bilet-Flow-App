package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.TextButton
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
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Serializable
data class EventSales(
    @SerialName("event_id") val eventId: Int,
    val currency: String,
    @SerialName("is_simulated") val isSimulated: Boolean,
    @SerialName("gross_sales") val grossSales: Long,
    @SerialName("processing_fees") val processingFees: Long,
    val refunds: Long,
    val discounts: Long = 0,
    @SerialName("estimated_payout") val estimatedPayout: Long,
    @SerialName("paid_orders") val paidOrders: Int,
    @SerialName("ticket_types") val ticketTypes: List<TicketTypeSales>,
)

@Serializable
data class TicketTypeSales(
    @SerialName("ticket_type_id") val ticketTypeId: Int,
    val name: String, val price: Long, val quantity: Int,
    val sold: Int, val reserved: Int, val available: Int,
    val refunded: Int = 0, @SerialName("checked_in") val checkedIn: Int = 0,
)

data class EventSalesState(
    val eventId: Int? = null, val title: String = "", val loading: Boolean = false,
    val sales: EventSales? = null, val error: Boolean = false, val denied: Boolean = false,
)

class EventSalesViewModel(
    private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl,
) : ViewModel() {
    private val mutable = MutableStateFlow(EventSalesState())
    val state = mutable.asStateFlow()
    private var sequence = 0
    private var generation = session.generation
    init {
        viewModelScope.launch { session.tokens.collect {
            if (generation != session.generation) { generation = session.generation; close() }
        } }
    }
    fun open(id: Int, title: String) { sequence++; mutable.value = EventSalesState(eventId = id, title = title); reload() }
    fun close() { sequence++; mutable.value = EventSalesState() }
    fun reload() {
        val current = mutable.value
        val id = current.eventId ?: return
        if (current.loading) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(loading = true, sales = null, error = false, denied = false)
        viewModelScope.launch {
            try {
                val sales = requests.request { token ->
                    val response = client.get("$baseUrl/events/$id/sales") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<EventSales>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Sales request failed")
                    }
                }
                require(sales.eventId == id)
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(loading = false, sales = sales)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(loading = false, error = true,
                    denied = api?.statusCode == 403 || api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError", "EventAccessDeniedError"))
            }
        }
    }
}

@Composable
fun EventSalesPage(vm: EventSalesViewModel) {
    val state by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = vm::close) { Text(stringResource(Res.string.ticket_create_back)) }
            Text(stringResource(Res.string.sales_title), style = MaterialTheme.typography.headlineLarge)
            Text(state.title)
            TextButton(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.my_events_refresh)) }
        }
        when {
            state.loading -> item { CircularProgressIndicator() }
            state.error -> item {
                Text(stringResource(Res.string.sales_error))
                Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
            }
            else -> state.sales?.let { sales ->
                val currency = if (sales.currency == "KZT") "₸" else sales.currency
                if (sales.isSimulated) item { Text(stringResource(Res.string.sales_simulated)) }
                val metrics = listOf(
                    Res.string.sales_paid_orders to sales.paidOrders.toString(),
                    Res.string.sales_gross to "${sales.grossSales} $currency",
                    Res.string.sales_fees to "${sales.processingFees} $currency",
                    Res.string.sales_refunds to "${sales.refunds} $currency",
                    Res.string.sales_discounts to "${sales.discounts} $currency",
                    Res.string.sales_payout to "${sales.estimatedPayout} $currency",
                )
                metrics.forEach { (label, value) -> item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                            Text(value, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                } }
                item { Text(stringResource(Res.string.sales_types), style = MaterialTheme.typography.titleLarge) }
                if (sales.ticketTypes.isEmpty()) item { Text(stringResource(Res.string.sales_types_empty)) }
                items(sales.ticketTypes, key = { it.ticketTypeId }) { type ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(type.name, style = MaterialTheme.typography.titleMedium)
                            Text("${stringResource(Res.string.sales_price)}: ${type.price} $currency")
                            Text("${stringResource(Res.string.sales_quantity)}: ${type.quantity}")
                            Text("${stringResource(Res.string.sales_sold)}: ${type.sold} · ${stringResource(Res.string.sales_reserved)}: ${type.reserved}")
                            Text("${stringResource(Res.string.sales_available)}: ${type.available}")
                            Text("${stringResource(Res.string.sales_refunded)}: ${type.refunded} · ${stringResource(Res.string.sales_checked_in)}: ${type.checkedIn}")
                        }
                    }
                }
            }
        }
    }
}
