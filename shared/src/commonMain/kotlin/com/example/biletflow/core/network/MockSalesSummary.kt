package com.example.biletflow.core.network

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

/** Debug assumptions, not backend formulas: gross/paid_orders include paid then refunded orders;
 * free confirmed orders count toward sold, live pending toward reserved; cancelled/refunded release
 * inventory. Fees are zero because the reference supplies no fee formula. Seed sold_count is ignored
 * because the mock order store is the source of truth for this summary. */
internal fun mockSalesSummary(
    eventId: Int, orders: List<JsonObject>, ticketTypes: List<JsonObject>, now: Instant = Clock.System.now(),
): JsonObject {
    val ownOrders = orders.filter { it["event_id"]?.jsonPrimitive?.int == eventId }
    val paid = ownOrders.filter { (it["total_amount"]?.jsonPrimitive?.long ?: 0) > 0 && it["paid_at"] != null && it["paid_at"] != JsonNull }
    val gross = paid.sumOf { it.getValue("total_amount").jsonPrimitive.long }
    val refunds = ownOrders.filter { it["status"]?.jsonPrimitive?.content == "refunded" }
        .sumOf { it.getValue("total_amount").jsonPrimitive.long }
    fun quantity(order: JsonObject, typeId: Int) = order["items"]?.jsonArray.orEmpty()
        .filter { it.jsonObject["ticket_type_id"]?.jsonPrimitive?.int == typeId }
        .sumOf { it.jsonObject.getValue("quantity").jsonPrimitive.int }
    return buildJsonObject {
        put("event_id", eventId); put("currency", "KZT"); put("is_simulated", true)
        put("gross_sales", gross); put("processing_fees", 0); put("refunds", refunds)
        put("estimated_payout", gross - refunds); put("paid_orders", paid.size)
        putJsonArray("ticket_types") {
            ticketTypes.filter { it["event_id"]?.jsonPrimitive?.int == eventId }.distinctBy { it["id"] }.forEach { type ->
                val id = type.getValue("id").jsonPrimitive.int
                val sold = ownOrders.filter { it["status"]?.jsonPrimitive?.content == "confirmed" }.sumOf { quantity(it, id) }
                val reserved = ownOrders.filter {
                    it["status"]?.jsonPrimitive?.content == "pending" &&
                        (it["expires_at"] == null || it["expires_at"] == JsonNull || Instant.parse(it.getValue("expires_at").jsonPrimitive.content) > now)
                }.sumOf { quantity(it, id) }
                val total = type.getValue("quantity").jsonPrimitive.int
                add(buildJsonObject {
                    put("ticket_type_id", id); put("name", type.getValue("name")); put("price", type.getValue("price"))
                    put("quantity", total); put("sold", sold); put("reserved", reserved); put("available", maxOf(0, total - sold - reserved))
                })
            }
        }
    }
}
