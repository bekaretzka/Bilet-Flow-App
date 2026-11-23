package com.example.biletflow.presentation.organizer

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.example.biletflow.domain.models.Event
import com.example.biletflow.presentation.events.formatEventDate

@Composable
fun EventManagementPage(vm: MyEventsViewModel, onEdit: (Event) -> Unit, tickets: CreateTicketTypeViewModel) {
    val state by vm.state.collectAsState()
    val ticketState by tickets.state.collectAsState()
    val event = state.events.firstOrNull { it.id == state.selectedId }
    LaunchedEffect(event?.id, event?.status) { if(event?.status == "published") tickets.load(event.id) }
    var confirm by remember(state.selectedId) { mutableStateOf(false) }
    val locked = state.changingStatus || state.statusUnknown || state.loading
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::backToList, enabled = !state.changingStatus) { Text(stringResource(Res.string.create_back)) }
        when {
            state.loading -> CircularProgressIndicator()
            state.error -> {
                Text(stringResource(Res.string.my_events_error))
                Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
            }
            event == null -> Text(stringResource(Res.string.event_management_missing))
            else -> {
                Text(event.title, style = MaterialTheme.typography.headlineLarge)
                Text(formatEventDate(event.startTime))
                Text(formatEventDate(event.endTime))
                Text(event.status)
                Text(event.visibility)
                listOfNotNull(event.description, event.category, event.venueName, event.venueAddress,
                    event.capacity?.toString(), event.registrationOpensAt, event.registrationClosesAt, event.refundPolicy)
                    .forEach { Text(it) }
                Button(onClick = { onEdit(event) }, enabled = !locked) { Text(stringResource(Res.string.event_edit)) }
                Button(onClick = { tickets.open(event.id) }, enabled = !locked) { Text(stringResource(Res.string.ticket_create_title)) }
                if (event.status in listOf("draft", "published")) Button(onClick = { confirm = true }, enabled = !locked) {
                    Text(stringResource(if (event.status == "draft") Res.string.event_publish else Res.string.event_unpublish))
                }
                if (state.changingStatus) CircularProgressIndicator()
                if (state.statusUnknown) {
                    Text(stringResource(Res.string.event_status_unknown))
                    Button(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.payment_check_status)) }
                } else state.statusError?.let {
                    Text(stringResource(Res.string.event_status_failed), color = MaterialTheme.colorScheme.error)
                    Text(it)
                }
                if (state.statusSuccess) Text(stringResource(when {
                    event.status == "draft" -> Res.string.event_unpublished_success
                    event.visibility == "public" -> Res.string.event_published_public
                    event.visibility == "unlisted" -> Res.string.event_published_unlisted
                    else -> Res.string.event_published_private
                }))
                if (ticketState.listLoading) CircularProgressIndicator()
                if (ticketState.listError && event.status == "published") Button(onClick = { tickets.load(event.id) }) { Text(stringResource(Res.string.events_retry)) }
                tickets.list(event.id).let { added ->
                    Text(stringResource(if(event.status == "published") Res.string.ticket_list_partial else Res.string.ticket_created_session))
                    added.forEach { ticket ->
                        Card(onClick = { tickets.edit(ticket) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${ticket.name} · #${ticket.id}")
                                Text(if (ticket.price == 0) stringResource(Res.string.ticket_price_free) else "${ticket.price} ₸")
                                ticket.description?.let { Text(it) }
                                Text("${ticket.quantity} · ${ticket.salesStart} — ${ticket.salesEnd}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm && event != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (event.status == "draft") Res.string.event_publish else Res.string.event_unpublish)) },
        text = { Text(stringResource(if (event.status == "draft") Res.string.event_publish_info else Res.string.event_unpublish_info)) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.changeStatus(event.status == "draft") }) { Text(stringResource(Res.string.event_status_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.auth_close)) } },
    )
}
