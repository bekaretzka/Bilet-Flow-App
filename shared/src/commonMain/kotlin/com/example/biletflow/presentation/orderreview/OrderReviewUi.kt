package com.example.biletflow.presentation.orderreview

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.Order
import com.example.biletflow.presentation.eventdetails.EventDetailsViewModel
import org.jetbrains.compose.resources.stringResource

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val FieldBorder = Color(0xFFD9D5CC)

@Composable
fun OrderReviewPage(
    viewModel: EventDetailsViewModel,
    orderViewModel: OrderCreationViewModel,
    isAuthenticated: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onConflict: () -> Unit,
    onOrderCreated: (Order) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val orderState by orderViewModel.state.collectAsState()
    val event = state.event
    val selectedItems = state.ticketTypes.mapNotNull { ticketType ->
        val quantity = state.selectedQuantities[ticketType.id] ?: 0
        if (quantity > 0) SelectedTicket(ticketType, quantity) else null
    }
    val totalQuantity = selectedItems.sumOf { it.quantity }
    val total = selectedItems.sumOf { it.ticketType.price.toLong() * it.quantity.toLong() }
    val requestItems = selectedItems.map {
        CreateOrderItem(ticketTypeId = it.ticketType.id, quantity = it.quantity)
    }

    LaunchedEffect(orderState.createdOrder?.id) {
        orderState.createdOrder?.let(onOrderCreated)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground)
            .statusBarsPadding().padding(horizontal = 24.dp),
    ) {
        ReviewHeader(onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(Res.string.order_review_title),
                color = Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                event?.title.orEmpty(),
                color = Muted,
                fontSize = 17.sp,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(24.dp))
            selectedItems.forEachIndexed { index, item ->
                ReviewItem(item)
                if (index != selectedItems.lastIndex) Spacer(Modifier.height(12.dp))
            }
            if (selectedItems.isEmpty()) {
                Text(stringResource(Res.string.order_review_empty), color = Muted, fontSize = 16.sp)
            } else {
                Spacer(Modifier.height(20.dp))
                ReviewTotal(totalQuantity = totalQuantity, total = total)
                if (!isAuthenticated) {
                    Spacer(Modifier.height(16.dp))
                    if (orderState.error == OrderCreationError.Unauthorized) {
                        Text(
                            stringResource(Res.string.order_error_unauthorized),
                            color = Color(0xFFB3261E),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
                    ) {
                        Text(
                            stringResource(
                                if (orderState.error == OrderCreationError.Unauthorized) {
                                    Res.string.order_login_again
                                } else {
                                    Res.string.order_review_login
                                },
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = orderState.buyerName,
                        onValueChange = orderViewModel::updateBuyerName,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !orderState.isSubmitting && orderState.createdOrder == null,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.order_buyer_name)) },
                        supportingText = if (orderState.error == OrderCreationError.InvalidName) {
                            { Text(stringResource(Res.string.order_buyer_name_error), color = Color(0xFFB3261E)) }
                        } else null,
                        isError = orderState.error == OrderCreationError.InvalidName,
                    )
                    OrderError(
                        error = orderState.error,
                        conflictCode = orderState.conflictCode,
                        onLogin = onLogin,
                        onConflict = onConflict,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            event?.let {
                                orderViewModel.submit(eventId = it.id, items = requestItems)
                            }
                        },
                        enabled = requestItems.isNotEmpty() && !orderState.isSubmitting &&
                            orderState.error != OrderCreationError.UnknownResult,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
                    ) {
                        if (orderState.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                stringResource(Res.string.order_create),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OrderError(
    error: OrderCreationError?,
    conflictCode: String?,
    onLogin: () -> Unit,
    onConflict: () -> Unit,
) {
    if (error == null || error == OrderCreationError.InvalidName) return
    Spacer(Modifier.height(12.dp))
    val text = when (error) {
        OrderCreationError.Unauthorized -> stringResource(Res.string.order_error_unauthorized)
        OrderCreationError.Conflict -> when (conflictCode) {
            "RegistrationClosedError" -> stringResource(Res.string.order_error_sales_closed)
            else -> stringResource(Res.string.order_error_availability)
        }
        OrderCreationError.UnknownResult -> stringResource(Res.string.order_error_unknown_result)
        OrderCreationError.Server -> stringResource(Res.string.order_error_server)
        OrderCreationError.InvalidName -> return
    }
    Text(text, color = Color(0xFFB3261E), fontSize = 14.sp, lineHeight = 20.sp)
    if (error == OrderCreationError.Unauthorized || error == OrderCreationError.Conflict) {
        TextButton(onClick = if (error == OrderCreationError.Unauthorized) onLogin else onConflict) {
            Text(
                stringResource(
                    if (error == OrderCreationError.Unauthorized) Res.string.order_login_again
                    else Res.string.order_back_to_selection,
                ),
                color = Purple,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ReviewHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("‹", color = Ink, fontSize = 32.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text("BiletFlow", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReviewItem(item: SelectedTicket) {
    val unitPrice = item.ticketType.price.toLong()
    val lineTotal = unitPrice * item.quantity.toLong()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, FieldBorder),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(item.ticketType.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            ReviewValueRow(
                label = stringResource(Res.string.order_review_quantity),
                value = item.quantity.toString(),
            )
            Spacer(Modifier.height(8.dp))
            ReviewValueRow(
                label = stringResource(Res.string.order_review_unit_price),
                value = formatPrice(unitPrice),
            )
            Spacer(Modifier.height(8.dp))
            ReviewValueRow(
                label = stringResource(Res.string.order_review_line_total),
                value = formatPrice(lineTotal),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun ReviewValueRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(
            value,
            color = Ink,
            fontSize = if (emphasized) 17.sp else 15.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ReviewTotal(totalQuantity: Int, total: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFE9E7FF),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            ReviewValueRow(
                label = stringResource(Res.string.order_review_total_quantity),
                value = totalQuantity.toString(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.order_review_total), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(formatPrice(total), color = Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun formatPrice(value: Long): String = if (value == 0L) {
    stringResource(Res.string.ticket_price_free)
} else {
    stringResource(Res.string.ticket_price_kzt, formatMoney(value))
}

private fun formatMoney(value: Long): String = value.toString()
    .reversed()
    .chunked(3)
    .joinToString(" ")
    .reversed()

private data class SelectedTicket(
    val ticketType: TicketType,
    val quantity: Int,
)
