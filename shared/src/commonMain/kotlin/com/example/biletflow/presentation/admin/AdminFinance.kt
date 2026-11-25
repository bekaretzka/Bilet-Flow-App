package com.example.biletflow.presentation.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import biletflow.shared.generated.resources.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.stringResource

/** 55.8 read-only finance rows (api-reference AdminOrder/AdminPayment/AdminRefund/AdminCampaign = backend schemas/admin.py). Money is Long KZT. */
@Serializable data class AdminOrder(val id: Int, @SerialName("event_id") val eventId: Int, @SerialName("user_id") val userId: Int? = null,
    val status: String, @SerialName("total_amount") val totalAmount: Long, @SerialName("processing_fee") val processingFee: Long,
    @SerialName("buyer_name") val buyerName: String, @SerialName("buyer_email") val buyerEmail: String, @SerialName("created_at") val createdAt: String,
    @SerialName("paid_at") val paidAt: String? = null, @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("refunded_at") val refundedAt: String? = null)
@Serializable data class AdminPayment(val id: Int, val purpose: String, val amount: Long, val currency: String, val status: String,
    @SerialName("is_simulated") val isSimulated: Boolean, @SerialName("provider_reference") val providerReference: String,
    @SerialName("failure_reason") val failureReason: String? = null, @SerialName("payer_user_id") val payerUserId: Int? = null,
    @SerialName("event_id") val eventId: Int, @SerialName("order_id") val orderId: Int? = null, @SerialName("created_at") val createdAt: String)
@Serializable data class AdminRefund(val id: Int, @SerialName("order_id") val orderId: Int, @SerialName("event_id") val eventId: Int,
    val amount: Long, val currency: String, val status: String, @SerialName("is_simulated") val isSimulated: Boolean,
    @SerialName("provider_reference") val providerReference: String, val reason: String? = null,
    @SerialName("initiated_by_user_id") val initiatedByUserId: Int? = null, @SerialName("created_at") val createdAt: String)
@Serializable data class AdminCampaign(val id: Int, @SerialName("event_id") val eventId: Int, @SerialName("event_title") val eventTitle: String,
    val code: String, val name: String, @SerialName("discount_type") val discountType: String, @SerialName("discount_value") val discountValue: Long,
    @SerialName("is_active") val isActive: Boolean, @SerialName("max_redemptions") val maxRedemptions: Int? = null,
    @SerialName("redemptions_count") val redemptionsCount: Int, @SerialName("link_opens") val linkOpens: Long, val orders: Long, val tickets: Long,
    val gross: Long, val discount: Long, val net: Long, @SerialName("created_at") val createdAt: String)

private fun <T> JsonObject.decode(serializer: KSerializer<T>): T? = runCatching { adminJson.decodeFromJsonElement(serializer, this) }.getOrNull()
private fun currency(value: String) = if (value == "KZT") "₸" else value

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)
@Composable
private fun Simulated(simulated: Boolean) { if (simulated) com.example.biletflow.core.designsystem.DemoBanner(stringResource(Res.string.sales_simulated)) }
@Composable
private fun Unreadable(row: JsonObject) = Text("#${row.id} ${stringResource(Res.string.admin_row_unreadable)}", color = MaterialTheme.colorScheme.error)

@Composable
internal fun FinanceRow(list: AdminList, row: JsonObject) {
    val event = stringResource(Res.string.support_event)
    when (list) {
        AdminList.Orders -> row.decode(AdminOrder.serializer())?.let { o ->
            Text("#${o.id} · $event #${o.eventId} · ${o.status}")
            Text("${o.buyerName} · ${o.buyerEmail}")
            Small("${o.totalAmount} ₸ · ${stringResource(Res.string.sales_fees)}: ${o.processingFee} ₸ · ${o.createdAt}")
            listOfNotNull(o.paidAt?.let { "paid $it" }, o.cancelledAt?.let { "cancelled $it" }, o.refundedAt?.let { "refunded $it" })
                .takeIf { it.isNotEmpty() }?.let { Small(it.joinToString(" · ")) }
        } ?: Unreadable(row)
        AdminList.Payments -> row.decode(AdminPayment.serializer())?.let { p ->
            Text("#${p.id} · ${p.purpose} · ${p.status} · ${p.amount} ${currency(p.currency)}")
            Simulated(p.isSimulated)
            Small("${p.providerReference} · $event #${p.eventId}${p.orderId?.let { " · #$it" }.orEmpty()} · ${p.createdAt}")
            p.failureReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } ?: Unreadable(row)
        AdminList.Refunds -> row.decode(AdminRefund.serializer())?.let { r ->
            Text("#${r.id} · ${r.status} · ${r.amount} ${currency(r.currency)}")
            Simulated(r.isSimulated)
            Small("${r.providerReference} · $event #${r.eventId} · ${stringResource(Res.string.order_result_number)} #${r.orderId} · ${r.createdAt}")
            r.reason?.let { Small(it) }
        } ?: Unreadable(row)
        AdminList.Campaigns -> row.decode(AdminCampaign.serializer())?.let { c ->
            Text("#${c.id} ${c.code} · ${c.name} · ${stringResource(if (c.isActive) Res.string.campaigns_active else Res.string.campaigns_inactive)}")
            Small("$event #${c.eventId} ${c.eventTitle} · ${c.discountType} ${c.discountValue}")
            Small("${c.redemptionsCount}/${c.maxRedemptions ?: "∞"} · ${stringResource(Res.string.campaigns_link_opens)} ${c.linkOpens} · ${c.orders}/${c.tickets} · ${c.gross}−${c.discount}=${c.net} ₸")
        } ?: Unreadable(row)
        else -> Unit
    }
}
