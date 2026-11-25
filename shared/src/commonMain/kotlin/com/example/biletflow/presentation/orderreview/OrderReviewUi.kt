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
import com.example.biletflow.core.designsystem.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.example.biletflow.core.designsystem.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.example.biletflow.core.designsystem.BF
import com.example.biletflow.core.designsystem.HeadingFont
import com.example.biletflow.core.designsystem.DemoBanner
import com.example.biletflow.core.designsystem.SectionCard
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

private val PageBackground = com.example.biletflow.core.designsystem.BF.paper
private val Ink = com.example.biletflow.core.designsystem.BF.ink
private val Muted = com.example.biletflow.core.designsystem.BF.muted
private val Purple = com.example.biletflow.core.designsystem.BF.primary
private val FieldBorder = com.example.biletflow.core.designsystem.BF.lineField

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

    // Figma «Зритель / Оформление»: header, demo banner, then cards «Данные участника», «Ваш заказ», «Способ оплаты».
    var termsAccepted by remember(event?.id) { mutableStateOf(false) }
    val editable = !orderState.isSubmitting && orderState.createdOrder == null
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = FieldBorder,
        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, cursorColor = Purple)
    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground).statusBarsPadding(),
    ) {
        ReviewHeader(onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            DemoBanner(stringResource(Res.string.payment_demo_banner))
            if (selectedItems.isEmpty()) {
                Text(stringResource(Res.string.order_review_empty), color = Muted, fontSize = 15.sp)
            } else if (!isAuthenticated) {
                OrderSummaryCard(event?.title, selectedItems, total, null, orderState, requestItems, editable = false,
                    onPromo = {}, onPromoChange = {})
                if (orderState.error == OrderCreationError.Unauthorized) Text(stringResource(Res.string.order_error_unauthorized),
                    color = BF.dangerFg, fontSize = 14.sp, lineHeight = 20.sp)
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(if (orderState.error == OrderCreationError.Unauthorized) Res.string.order_login_again else Res.string.order_review_login),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                SectionCard {
                    CardTitle(stringResource(Res.string.checkout_attendee_title))
                    FieldLabel(stringResource(Res.string.order_buyer_name))
                    OutlinedTextField(
                        value = orderState.buyerName, onValueChange = orderViewModel::updateBuyerName,
                        modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true, colors = fieldColors,
                        placeholder = { Text(stringResource(Res.string.checkout_name_placeholder), color = Muted, fontSize = 15.sp) },
                        isError = orderState.error == OrderCreationError.InvalidName,
                    )
                    if (orderState.error == OrderCreationError.InvalidName)
                        Text(stringResource(Res.string.order_buyer_name_error), color = BF.dangerFg, fontSize = 13.sp)
                    if (totalQuantity > 1) {
                        Text(stringResource(Res.string.order_attendees_help), color = Muted, fontSize = 13.sp)
                        selectedItems.forEach { item ->
                            repeat(item.quantity) { index ->
                                FieldLabel("${item.ticketType.name} · ${stringResource(Res.string.order_attendee_name)} ${index + 1}")
                                OutlinedTextField(
                                    value = orderState.attendeeNames[item.ticketType.id]?.getOrNull(index).orEmpty(),
                                    onValueChange = { orderViewModel.updateAttendeeName(item.ticketType.id, index, it) },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true, colors = fieldColors,
                                )
                            }
                        }
                    }
                }
                OrderSummaryCard(event?.title, selectedItems, total, orderState.preview?.takeIf { orderState.previewValid(requestItems) },
                    orderState, requestItems, editable,
                    onPromo = { event?.let { orderViewModel.applyPromo(it.id, requestItems) } }, onPromoChange = orderViewModel::updatePromoCode)
                SectionCard {
                    CardTitle(stringResource(Res.string.checkout_payment_title))
                    Surface(Modifier.fillMaxWidth(), shape = BF.radiusControl, color = BF.primarySoft, border = BorderStroke(1.5.dp, BF.primary)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = true, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = BF.primary))
                            Column {
                                Text(stringResource(Res.string.checkout_test_card), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(Res.string.checkout_test_card_hint), color = BF.ink2, fontSize = 13.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().toggleable(value = termsAccepted, enabled = editable, role = Role.Checkbox) { termsAccepted = it },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = termsAccepted, onCheckedChange = null, enabled = editable,
                            colors = CheckboxDefaults.colors(checkedColor = BF.primary, uncheckedColor = BF.lineField))
                        Text(stringResource(Res.string.checkout_terms), color = BF.ink2, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                    event?.refundPolicy?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
                }
                OrderError(error = orderState.error, conflictCode = orderState.conflictCode, onLogin = onLogin, onConflict = onConflict)
                val payable = orderState.preview?.takeIf { orderState.previewValid(requestItems) }?.total ?: total
                Button(
                    onClick = { event?.let { orderViewModel.submit(eventId = it.id, items = requestItems) } },
                    enabled = termsAccepted && requestItems.isNotEmpty() && !orderState.isSubmitting &&
                        orderState.error != OrderCreationError.UnknownResult,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (orderState.isSubmitting) CircularProgressIndicator(modifier = Modifier.height(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(stringResource(Res.string.checkout_submit, formatPrice(payable)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(Res.string.checkout_footer), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text, color = Ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)

@Composable
private fun FieldLabel(text: String) = Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)

/** «Ваш заказ»: lines «Тип × N … сумма», promo code, server preview, «Итого». */
@Composable
private fun OrderSummaryCard(eventTitle: String?, items: List<SelectedTicket>, total: Long, preview: com.example.biletflow.domain.models.OrderPreview?,
    orderState: OrderCreationUiState, requestItems: List<CreateOrderItem>, editable: Boolean, onPromo: () -> Unit, onPromoChange: (String) -> Unit) {
    SectionCard {
        CardTitle(stringResource(Res.string.checkout_order_title))
        eventTitle?.let { Text(it, color = Muted, fontSize = 13.sp) }
        items.forEach { item ->
            ReviewValueRow("${item.ticketType.name} × ${item.quantity}", formatPrice(item.ticketType.price.toLong() * item.quantity))
        }
        if (editable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = orderState.promoCode, onValueChange = onPromoChange, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(stringResource(Res.string.order_promo_code), color = Muted, fontSize = 15.sp) },
                    isError = orderState.promoError != null)
                TextButton(onClick = onPromo, enabled = !orderState.previewing && requestItems.isNotEmpty()) {
                    Text(stringResource(Res.string.order_promo_apply), color = Purple)
                }
            }
            if (orderState.previewing) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
            orderState.promoError?.let { code ->
                Text(when (code) {
                    "PromoCodeNotFoundError" -> stringResource(Res.string.promo_not_found)
                    "PromoCodeNotActiveError" -> stringResource(Res.string.promo_not_active)
                    "PromoCodeUsedUpError" -> stringResource(Res.string.promo_used_up)
                    "PromoCodeNotApplicableError" -> stringResource(Res.string.promo_not_applicable)
                    else -> "${stringResource(Res.string.order_error_server)} ($code)"
                }, color = BF.dangerFg, fontSize = 13.sp)
            }
            if (preview == null && orderState.preview != null) Text(stringResource(Res.string.order_preview_stale), color = Muted, fontSize = 13.sp)
        }
        preview?.takeIf { it.discount > 0 }?.let {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${stringResource(Res.string.order_discount)} ${it.promoCode.orEmpty()}", color = Purple, fontSize = 14.sp)
                Text("−${formatPrice(it.discount)}", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        HorizontalDivider(color = BF.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.order_review_total), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(formatPrice(preview?.total ?: total), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
        }
        if (preview?.total == 0L) Text(stringResource(Res.string.order_free_after_discount), color = Muted, fontSize = 13.sp)
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
        OrderCreationError.InvalidName, OrderCreationError.Promo -> return
    }
    Text(text, color = com.example.biletflow.core.designsystem.BF.dangerFg, fontSize = 14.sp, lineHeight = 20.sp)
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("←", color = Ink, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
        }
        Text(stringResource(Res.string.checkout_title), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
    }
}

@Composable
private fun ReviewItem(item: SelectedTicket) {
    val unitPrice = item.ticketType.price.toLong()
    val lineTotal = unitPrice * item.quantity.toLong()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
        color = com.example.biletflow.core.designsystem.BF.primarySoft,
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
