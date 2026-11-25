package com.example.biletflow.presentation.eventdetails

import com.example.biletflow.core.designsystem.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.presentation.documents.LocalDocuments
import com.example.biletflow.presentation.documents.postJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource

val reportReasons = listOf("fraud", "inappropriate", "misleading", "spam", "other")

/** POST /events/{id}/reports → 201. Signed-in users only; 409 EventAlreadyReportedError. One submission per press. */
@Composable
fun ReportEventButton(eventId: Int, onLogin: () -> Unit) {
    val documents = LocalDocuments.current ?: return
    val tokens by documents.session.tokens.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember(eventId) { mutableStateOf(false) }
    var reason by remember(eventId) { mutableStateOf<String?>(null) }
    var details by remember(eventId) { mutableStateOf("") }
    var sending by remember(eventId) { mutableStateOf(false) }
    var result by remember(eventId) { mutableStateOf<String?>(null) }
    TextButton(onClick = { if (tokens == null) onLogin() else { open = true; result = if (result == "sent" || result == "EventAlreadyReportedError") result else null } }) {
        Text(stringResource(Res.string.report_event))
    }
    if (!open) return
    val done = result == "sent" || result == "EventAlreadyReportedError"
    AlertDialog(onDismissRequest = { if (!sending) open = false },
        title = { Text(stringResource(Res.string.report_event)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            reportReasons.forEach { value ->
                FilterChip(selected = reason == value, onClick = { reason = value }, enabled = !sending && !done, label = { Text(stringResource(when (value) {
                    "fraud" -> Res.string.report_fraud; "inappropriate" -> Res.string.report_inappropriate
                    "misleading" -> Res.string.report_misleading; "spam" -> Res.string.report_spam; else -> Res.string.report_other })) })
            }
            OutlinedTextField(details, { details = it.take(2000) }, enabled = !sending && !done, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.report_details)) }, supportingText = { Text("${details.length}/2000") })
            if (sending) CircularProgressIndicator()
            result?.let { code -> Text(when (code) {
                "sent" -> stringResource(Res.string.report_sent)
                "EventAlreadyReportedError" -> stringResource(Res.string.report_already)
                "network" -> stringResource(Res.string.report_unknown)
                else -> "${stringResource(Res.string.report_failed)} ($code)"
            }) }
        } },
        confirmButton = { TextButton(enabled = !sending && !done && reason != null && result != "network", onClick = {
            val chosen = reason ?: return@TextButton
            sending = true; result = null
            val generation = documents.session.generation
            scope.launch {
                try {
                    documents.postJson("/events/$eventId/reports", buildJsonObject {
                        put("reason", chosen); details.trim().takeIf { it.isNotEmpty() }?.let { put("details", it) }
                    }, 201)
                    if (generation == documents.session.generation) result = "sent"
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (generation == documents.session.generation) result = (error as? OrdersApiException)?.let { it.errorCode ?: "HTTP ${it.statusCode}" } ?: "network"
                } finally { sending = false }
            }
        }) { Text(stringResource(Res.string.report_send)) } },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !sending) { Text(stringResource(Res.string.auth_close)) } })
}
