package com.example.biletflow.presentation.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

/** 55.7: paid sales activations (GET /admin/paid-sales → array) and platform settings (activation fee, Long KZT). */
fun AdminViewModel.openSection(section: String) {
    update { AdminState(open = true, section = section) }
    if (section == "paid_sales") loadActivations() else loadSettings()
}

fun AdminViewModel.setPaidStatus(status: String?) { update { it.copy(paidStatus = status) }; loadActivations() }

fun AdminViewModel.loadActivations() {
    val expected = generationNow; val status = state.value.paidStatus
    update { it.copy(loading = true, error = null) }
    viewModelScope.launch {
        try {
            val rows = get("/admin/paid-sales", status?.let { mapOf("status" to it) } ?: emptyMap()).jsonArray.mapNotNull { it as? JsonObject }
            if (expected == generationNow && state.value.paidStatus == status) update { it.copy(loading = false, activations = rows) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected == generationNow) update { it.copy(loading = false, error = describe(error), denied = isDenied(error)) }
        }
    }
}

/** POST /admin/events/{id}/paid-sales/suspend {reason} or /reinstate → PaidSalesStatus; the list is re-read either way. */
fun AdminViewModel.setPaidSales(eventId: Int, suspend: Boolean) {
    val s = state.value
    if (s.busy) return
    if (suspend && !validReason(s.reason)) { update { it.copy(reasonInvalid = true) }; return }
    val expected = generationNow
    update { it.copy(busy = true, actionError = null, actionUnknown = false, actionDone = false) }
    viewModelScope.launch {
        try {
            val result = call(HttpMethod.Post, "/admin/events/$eventId/paid-sales/${if (suspend) "suspend" else "reinstate"}",
                if (suspend) buildJsonObject { put("reason", s.reason.trim()) } else null)
            if (expected != generationNow) return@launch
            val ok = (result["event_id"] as? JsonPrimitive)?.intOrNull == eventId
            update { it.copy(busy = false, actionDone = ok, actionUnknown = !ok, reason = if (ok) "" else it.reason) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected != generationNow) return@launch
            val failure = failure(error)
            update { it.copy(busy = false, actionError = failure, actionUnknown = failure == null, denied = isDenied(error)) }
        }
        if (expected == generationNow) loadActivations()
    }
}

fun AdminViewModel.loadSettings() {
    val expected = generationNow
    update { it.copy(loading = true, error = null) }
    viewModelScope.launch {
        try {
            val fee = (get("/admin/settings").jsonObject["activation_fee_kzt"] as? JsonPrimitive)?.longOrNull
            if (expected == generationNow) update { it.copy(loading = false, feeKzt = fee, feeDraft = if (it.feeDraft.isEmpty()) fee?.toString().orEmpty() else it.feeDraft) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected == generationNow) update { it.copy(loading = false, error = describe(error), denied = isDenied(error)) }
        }
    }
}

fun AdminViewModel.setFeeDraft(value: String) { update { it.copy(feeDraft = value.filter(Char::isDigit).take(15), feeInvalid = false) } }

/** PUT /admin/settings/activation-fee {amount_kzt ≥ 0}. Unknown result: GET /admin/settings, no retry. */
fun AdminViewModel.saveFee() {
    val s = state.value
    if (s.busy) return
    val amount = s.feeDraft.toLongOrNull()
    if (amount == null || amount < 0) { update { it.copy(feeInvalid = true) }; return }
    val expected = generationNow
    update { it.copy(busy = true, actionError = null, actionUnknown = false, actionDone = false) }
    viewModelScope.launch {
        try {
            val fee = (call(HttpMethod.Put, "/admin/settings/activation-fee", buildJsonObject { put("amount_kzt", amount) })["activation_fee_kzt"] as? JsonPrimitive)?.longOrNull
            if (expected == generationNow) update { it.copy(busy = false, actionDone = fee != null, feeKzt = fee ?: it.feeKzt, actionUnknown = fee == null) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (expected != generationNow) return@launch
            val failure = failure(error)
            update { it.copy(busy = false, actionError = failure, actionUnknown = failure == null, denied = isDenied(error)) }
            if (failure == null) { update { it.copy(feeDraft = "") }; loadSettings() }
        }
    }
}

@Composable
private fun ActionFeedback(state: AdminState) {
    if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(stringResource(Res.string.insights_error), color = MaterialTheme.colorScheme.error); Text(it) }
    state.actionError?.let { Text(adminFailureText(it), color = MaterialTheme.colorScheme.error) }
    if (state.actionUnknown) Text(stringResource(Res.string.admin_result_unknown), color = MaterialTheme.colorScheme.error)
    if (state.actionDone) Text(stringResource(Res.string.admin_done))
}

@Composable
internal fun PaidSalesSection(vm: AdminViewModel, state: AdminState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.admin_paid_sales), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = state.paidStatus == null, onClick = { vm.setPaidStatus(null) }, label = { Text(stringResource(Res.string.admin_any)) })
            listOf("pending", "active", "suspended").forEach { s -> FilterChip(selected = state.paidStatus == s, onClick = { vm.setPaidStatus(s) }, label = { Text(s) }) }
        }
        OutlinedTextField(state.reason, vm::setReason, enabled = !state.busy, isError = state.reasonInvalid, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.admin_reason)) })
        if (state.reasonInvalid) Text(stringResource(Res.string.admin_reason_invalid), color = MaterialTheme.colorScheme.error)
        ActionFeedback(state)
        if (!state.loading && state.error == null && state.activations.isEmpty()) Text(stringResource(Res.string.history_empty))
        state.activations.forEach { row ->
            val eventId = (row["event_id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("#$eventId ${row.text("event_title").orEmpty()} · ${row.text("status").orEmpty()}")
                Text("${stringResource(Res.string.admin_fee)}: ${row.text("fee_amount") ?: "—"} ₸ · ${row.text("fee_paid_at") ?: "—"}", style = MaterialTheme.typography.bodySmall)
                row.text("activated_at")?.let { Text("${stringResource(Res.string.admin_activated)}: $it", style = MaterialTheme.typography.bodySmall) }
                row.text("suspended_at")?.let { Text("${stringResource(Res.string.admin_suspended)}: $it · ${row.text("suspension_reason").orEmpty()}", color = MaterialTheme.colorScheme.error) }
                if (row.text("status") == "suspended") Button(onClick = { vm.setPaidSales(eventId, false) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_reinstate)) }
                else Button(onClick = { vm.setPaidSales(eventId, true) }, enabled = !state.busy) { Text(stringResource(Res.string.admin_suspend_sales)) }
            } }
        }
    }
}

@Composable
internal fun SettingsSection(vm: AdminViewModel, state: AdminState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.admin_settings), style = MaterialTheme.typography.headlineMedium)
        Text("${stringResource(Res.string.admin_fee_current)}: ${state.feeKzt?.let { "$it ₸" } ?: "—"}")
        OutlinedTextField(state.feeDraft, vm::setFeeDraft, enabled = !state.busy, isError = state.feeInvalid, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.admin_fee_new)) })
        if (state.feeInvalid) Text(stringResource(Res.string.admin_invalid), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveFee, enabled = !state.busy) { Text(stringResource(Res.string.admin_save)) }
            OutlinedButton(onClick = vm::loadSettings, enabled = !state.loading) { Text(stringResource(Res.string.support_refresh)) }
        }
        ActionFeedback(state)
    }
}
