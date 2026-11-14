package com.example.biletflow.presentation.orderreview

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biletflow.shared.generated.resources.Res
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.models.Ticket
import org.jetbrains.compose.resources.stringResource

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)

@Composable
fun OrderResultPage(
    order: Order,
    viewModel: OrderPaymentViewModel,
    onOrderUpdated: (Order) -> Unit,
    onLogin: () -> Unit,
    ticketTypeNames: Map<Int, String>,
    onOpenTicket: (Int) -> Unit,
    backToOrders: Boolean,
    onDone: () -> Unit,
) {
    val paymentState by viewModel.state.collectAsState()
    val confirmed = order.status.equals("confirmed", ignoreCase = true)
    val pending = order.status.equals("pending", ignoreCase = true)
    val expired = pending && !orderCanBePaid(order)
    val busy = paymentState.isPaying || paymentState.isCheckingStatus

    LaunchedEffect(order.id) { viewModel.startOrder(order) }
    LaunchedEffect(paymentState.updatedOrder) {
        paymentState.updatedOrder?.let {
            onOrderUpdated(it)
            viewModel.consumeUpdatedOrder()
        }
    }
    val statusText = if (confirmed) {
        stringResource(Res.string.order_result_confirmed)
    } else if (pending && (expired || paymentState.error == PaymentError.Expired)) {
        stringResource(Res.string.payment_expired)
    } else if (pending) {
        stringResource(Res.string.order_result_pending)
    } else {
        order.status
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground).statusBarsPadding().padding(24.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(stringResource(Res.string.order_result_title), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    ResultRow(stringResource(Res.string.order_result_number), order.id.toString())
                    Spacer(Modifier.height(14.dp))
                    ResultRow(stringResource(Res.string.order_result_status), statusText)
                    Spacer(Modifier.height(14.dp))
                    ResultRow(stringResource(Res.string.order_result_total), formatOrderPrice(order.totalAmount))
                    if (!confirmed && order.expiresAt != null) {
                        Spacer(Modifier.height(14.dp))
                        ResultRow(stringResource(Res.string.order_result_expires), order.expiresAt)
                    }
                }
            }
            if (pending) {
                Spacer(Modifier.height(18.dp))
                PaymentErrorMessage(paymentState.error, expired)
                when (paymentState.error) {
                    PaymentError.Unauthorized -> ResultActionButton(
                        text = stringResource(Res.string.payment_login_again),
                        onClick = onLogin,
                        enabled = !busy,
                    )
                    PaymentError.UnknownResult, PaymentError.StatusCheckFailed -> ResultActionButton(
                        text = stringResource(Res.string.payment_check_status),
                        onClick = { viewModel.checkStatus(order.id) },
                        enabled = !busy,
                        loading = paymentState.isCheckingStatus,
                    )
                    PaymentError.Expired -> Unit
                    else -> ResultActionButton(
                        text = stringResource(Res.string.payment_pay),
                        onClick = { viewModel.pay(order) },
                        enabled = !busy && !expired,
                        loading = paymentState.isPaying,
                    )
                }
            }
            if (confirmed) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(Res.string.order_tickets_title),
                    color = Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                if (order.tickets.isEmpty()) {
                    Text(stringResource(Res.string.order_tickets_empty), color = Muted, fontSize = 15.sp)
                } else {
                    order.tickets.forEach { ticket ->
                        TicketResultCard(
                            ticket = ticket,
                            typeName = ticketTypeNames[ticket.ticketTypeId],
                            onClick = { onOpenTicket(ticket.id) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
        ) {
            Text(
                stringResource(
                    if (backToOrders) Res.string.order_result_back_to_orders
                    else Res.string.order_result_back_to_events,
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TicketResultCard(ticket: Ticket, typeName: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFD9D5CC)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                typeName ?: stringResource(Res.string.order_ticket_number, ticket.id),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(ticket.attendeeName, color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(ticketStatusText(ticket.status), color = Purple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ticketStatusText(status: String): String = when (status.lowercase()) {
    "valid" -> stringResource(Res.string.ticket_status_valid)
    "checked_in" -> stringResource(Res.string.ticket_status_checked_in)
    "cancelled" -> stringResource(Res.string.ticket_status_cancelled)
    "refunded" -> stringResource(Res.string.ticket_status_refunded)
    else -> status
}

@Composable
private fun PaymentErrorMessage(error: PaymentError?, locallyExpired: Boolean) {
    val text = when {
        locallyExpired || error == PaymentError.Expired -> stringResource(Res.string.payment_error_expired)
        error == PaymentError.Declined -> stringResource(Res.string.payment_error_declined)
        error == PaymentError.Unauthorized -> stringResource(Res.string.payment_error_unauthorized)
        error == PaymentError.UnknownResult -> stringResource(Res.string.payment_error_unknown)
        error == PaymentError.StatusCheckFailed -> stringResource(Res.string.payment_error_status_check)
        error == PaymentError.Server -> stringResource(Res.string.payment_error_server)
        else -> null
    } ?: return
    Text(text, color = Color(0xFFB3261E), fontSize = 14.sp, lineHeight = 20.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ResultActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.height(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 15.sp)
        Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun formatOrderPrice(value: Long): String = if (value == 0L) {
    stringResource(Res.string.ticket_price_free)
} else {
    stringResource(
        Res.string.ticket_price_kzt,
        value.toString().reversed().chunked(3).joinToString(" ").reversed(),
    )
}
