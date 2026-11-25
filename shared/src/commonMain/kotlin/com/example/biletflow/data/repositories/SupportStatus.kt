package com.example.biletflow.data.repositories

val supportStatuses = listOf("open", "in_progress", "waiting_for_customer", "resolved")
fun canonicalSupportStatus(value: String) = when(value) {
    "pending" -> "in_progress"
    "closed" -> "resolved"
    else -> value
}
fun supportTransitions(value: String): List<String> = when(val current = canonicalSupportStatus(value)) {
    "resolved" -> listOf("open")
    in supportStatuses -> supportStatuses.filter { it != current }
    else -> emptyList()
}
fun supportCanMessage(status: String?) = status != null && canonicalSupportStatus(status) in supportStatuses.take(3)
