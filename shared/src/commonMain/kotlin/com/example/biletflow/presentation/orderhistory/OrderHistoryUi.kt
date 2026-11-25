package com.example.biletflow.presentation.orderhistory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.biletflow.presentation.events.EventContextCaption
import com.example.biletflow.domain.repositories.EventsRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.models.Order
import org.jetbrains.compose.resources.stringResource

@Composable
fun OrderHistoryPage(
    viewModel: OrderHistoryViewModel,
    eventNames: Map<Int, String>,
    eventsRepository: EventsRepository? = null,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    isAuthenticated: Boolean,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F4EC))
            .statusBarsPadding().padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹", color = Color(0xFF17171B), fontSize = 32.sp) }
            Text("BiletFlow", color = Color(0xFF17171B), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (isAuthenticated) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLogout) { Text(stringResource(Res.string.auth_logout)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(Res.string.order_history_title), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.isLoading -> HistoryMessage(stringResource(Res.string.order_history_loading), true)
                state.error == OrderHistoryError.Unauthorized -> HistoryAction(
                    message = stringResource(Res.string.order_history_session_expired),
                    button = stringResource(Res.string.payment_login_again),
                    onClick = onLogin,
                )
                state.error == OrderHistoryError.Network -> HistoryAction(
                    message = stringResource(Res.string.order_history_load_error),
                    button = stringResource(Res.string.events_retry),
                    onClick = viewModel::retry,
                )
                state.orders.isEmpty() -> HistoryMessage(stringResource(Res.string.order_history_empty))
                else -> state.orders.forEach { order ->
                    OrderHistoryCard(
                        order = order,
                        eventName = eventNames[order.eventId],
                        eventsRepository = eventsRepository,
                        isOpening = state.openingOrderId == order.id,
                        onClick = { viewModel.openOrder(order.id) },
                    )
                }
            }
            if (state.orderLoadFailed) {
                HistoryAction(
                    message = stringResource(Res.string.order_history_detail_error),
                    button = stringResource(Res.string.events_retry),
                    onClick = {
                        state.failedOrderId?.let(viewModel::openOrder)
                            ?: viewModel.retry()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OrderHistoryCard(
    order: Order,
    eventName: String?,
    eventsRepository: EventsRepository?,
    isOpening: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !isOpening,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFD9D5CC)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(Res.string.order_history_number, order.id),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (isOpening) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            }
            EventContextCaption(order.eventId,eventsRepository)
            Spacer(Modifier.height(12.dp))
            HistoryRow(stringResource(Res.string.order_history_created), formatOrderDate(order.createdAt))
            Spacer(Modifier.height(7.dp))
            HistoryRow(stringResource(Res.string.order_result_status), orderStatus(order.status))
            Spacer(Modifier.height(7.dp))
            if (order.discountAmount > 0) HistoryRow("${stringResource(Res.string.order_discount)}${order.promoCode?.let { " ($it)" }.orEmpty()}", "−" + historyPrice(order.discountAmount), false)
            HistoryRow(stringResource(Res.string.order_result_total), historyPrice(order.totalAmount), true)
        }
    }
}

@Composable
private fun HistoryRow(label: String, value: String, emphasized: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF6F6C72), fontSize = 14.sp)
        Text(value, fontSize = 14.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun HistoryMessage(text: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (loading) CircularProgressIndicator(color = Color(0xFF4035D6))
        Text(text, color = Color(0xFF6F6C72), fontSize = 16.sp)
    }
}

@Composable
private fun HistoryAction(message: String, button: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, color = Color(0xFF6F6C72), fontSize = 15.sp)
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4035D6)),
            shape = RoundedCornerShape(14.dp),
        ) { Text(button, color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun orderStatus(status: String): String = when (status.lowercase()) {
    "pending" -> stringResource(Res.string.order_result_pending)
    "confirmed" -> stringResource(Res.string.order_result_confirmed)
    "cancelled" -> stringResource(Res.string.order_status_cancelled)
    "refunded" -> stringResource(Res.string.order_status_refunded)
    "expired" -> stringResource(Res.string.payment_expired)
    else -> status
}

@Composable
private fun historyPrice(value: Long): String = if (value == 0L) {
    stringResource(Res.string.ticket_price_free)
} else {
    stringResource(
        Res.string.ticket_price_kzt,
        value.toString().reversed().chunked(3).joinToString(" ").reversed(),
    )
}

private fun formatOrderDate(value: String): String {
    val date = value.substringBefore('T').split('-')
    val time = value.substringAfter('T', "").take(5)
    return if (date.size == 3 && time.length == 5) {
        "${date[2]}.${date[1]}.${date[0]} · $time"
    } else {
        value
    }
}
