package com.example.biletflow.presentation.organizer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import com.example.biletflow.data.repositories.OrganizerOrder
import com.example.biletflow.presentation.events.formatEventDate
import org.jetbrains.compose.resources.stringResource

@Composable
private fun orderStatus(status: String): String = when (status) {
    "pending" -> stringResource(Res.string.order_result_pending)
    "confirmed" -> stringResource(Res.string.order_result_confirmed)
    "cancelled" -> stringResource(Res.string.order_result_cancelled)
    "refunded" -> stringResource(Res.string.organizer_order_refunded)
    "expired" -> stringResource(Res.string.payment_expired)
    else -> status
}

@Composable
private fun OrderSummary(order: OrganizerOrder, details: Boolean = false) {
    Text("#${order.id}", style = MaterialTheme.typography.titleLarge)
    Text(order.buyerName); Text(order.buyerEmail)
    Text(orderStatus(order.status))
    Text("${order.totalAmount} ₸", style = MaterialTheme.typography.titleMedium)
    Text(formatEventDate(order.createdAt))
    if(details) {
    Text("${stringResource(Res.string.organizer_order_tickets)}: ${order.tickets}")
    Text("${stringResource(Res.string.organizer_order_discount)}: ${order.discountAmount} ₸")
    order.promoCode?.let { Text("${stringResource(Res.string.organizer_order_promo)}: $it") }
    Text("${stringResource(Res.string.organizer_order_fee)}: ${order.processingFee} ₸")
    order.paymentReference?.let { Text("${stringResource(Res.string.organizer_order_payment)}: $it") }
    order.paidAt?.let { Text("${stringResource(Res.string.organizer_order_paid)}: ${formatEventDate(it)}") }
    order.cancelledAt?.let { Text("${stringResource(Res.string.organizer_order_cancelled)}: ${formatEventDate(it)}") }
    order.refundedAt?.let { Text("${stringResource(Res.string.organizer_order_refunded_at)}: ${formatEventDate(it)}") }
    order.refund?.let {
        Text("${stringResource(Res.string.organizer_order_refunded)}: ${it.amount} ₸ · ${it.providerReference}")
        it.reason?.let { reason -> Text(reason) }; Text(formatEventDate(it.createdAt))
    }
    if(order.isSimulated) Text(stringResource(Res.string.organizer_refund_simulated))
    }
}

@Composable
fun OrganizerOrdersPage(vm: OrganizerOrdersViewModel) {
    val state by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = vm::back, enabled = !state.refundBusy) {
                Text(stringResource(if (state.selectedId == null) Res.string.ticket_create_back else Res.string.organizer_order_back))
            }
            Text(stringResource(if (state.selectedId == null) Res.string.organizer_orders_title else Res.string.organizer_order_title), style = MaterialTheme.typography.headlineLarge)
            Text(state.title)
        }
        if (state.selectedId == null) {
            item {
                OutlinedTextField(state.query, vm::search, label={Text(stringResource(Res.string.organizer_orders_search))},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(null,"pending","confirmed","cancelled","refunded","expired").forEach { value ->
                        FilterChip(selected=state.status == value,onClick={vm.status(value)},label={Text(if(value == null) stringResource(Res.string.events_filter_all) else orderStatus(value))})
                    }
                }
            }
            item { TextButton(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.my_events_refresh)) } }
            items(state.orders, key = { it.id }) { order ->
                Card(onClick = { vm.select(order.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { OrderSummary(order) }
                }
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.error != null) item {
                Text(stringResource(Res.string.organizer_orders_error), color = MaterialTheme.colorScheme.error)
                Text(state.error!!)
                Button(onClick = if (state.orders.isEmpty()) vm::reload else vm::more) { Text(stringResource(Res.string.events_retry)) }
            }
            if (!state.loading && state.error == null && state.orders.isEmpty()) item {
                Text(stringResource(if(state.query.isNotBlank() || state.status != null) Res.string.organizer_orders_no_matches else Res.string.organizer_orders_empty))
            }
            if (state.nextOffset < state.total && !state.loading && state.error == null) item {
                Button(onClick = vm::more) { Text(stringResource(Res.string.organizer_orders_more)) }
            }
        } else {
            if (state.detailLoading || state.refundBusy) item { CircularProgressIndicator() }
            if (state.detailError != null) item {
                Text(stringResource(Res.string.organizer_orders_error), color = MaterialTheme.colorScheme.error)
                Text(state.detailError!!)
                Button(onClick = vm::checkOrder, enabled = !state.refundBusy && !state.detailLoading) { Text(stringResource(Res.string.events_retry)) }
            }
            state.order?.let { order ->
                item { OrderSummary(order, details = true) }
                if (state.refundSucceeded) item { Text(stringResource(Res.string.organizer_refund_success)) }
                if (state.refundError != null) item {
                    Text(stringResource(when (state.refundError) {
                        "PaymentFailedError" -> Res.string.organizer_refund_declined
                        "OrderNotRefundableError" -> Res.string.organizer_refund_conflict
                        else -> Res.string.organizer_refund_error
                    }), color = MaterialTheme.colorScheme.error)
                    Text(state.refundError!!)
                }
                if (order.id in state.unknown) item {
                    Text(stringResource(Res.string.organizer_refund_unknown))
                    Button(onClick = vm::checkOrder, enabled = !state.detailLoading) { Text(stringResource(Res.string.payment_check_status)) }
                }
                if (order.refundable && order.id !in state.unknown && state.detailError == null) item {
                    Button(onClick = vm::askRefund, enabled = !state.refundBusy && !state.detailLoading) { Text(stringResource(Res.string.organizer_refund_action)) }
                }
            }
        }
    }
    if (state.confirming) state.order?.let { order ->
        AlertDialog(onDismissRequest = vm::dismissRefund,
            title = { Text(stringResource(Res.string.organizer_refund_action)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("#${order.id} · ${order.totalAmount} ₸")
                Text(stringResource(Res.string.organizer_refund_simulated))
                OutlinedTextField(state.reason, vm::reason, label = { Text(stringResource(Res.string.organizer_refund_reason)) },
                    isError = state.invalidReason, modifier = Modifier.fillMaxWidth())
                if (state.invalidReason) Text(stringResource(Res.string.organizer_refund_reason_error), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = vm::confirmRefund) { Text(stringResource(Res.string.organizer_refund_action)) } },
            dismissButton = { TextButton(onClick = vm::dismissRefund) { Text(stringResource(Res.string.auth_close)) } })
    }
}
