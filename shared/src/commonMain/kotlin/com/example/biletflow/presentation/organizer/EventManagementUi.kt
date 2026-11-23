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
fun EventManagementPage(vm: MyEventsViewModel, onEdit: (Event) -> Unit, tickets: CreateTicketTypeViewModel, onPaidSales: (Event) -> Unit = {}, onAttendees: (Event) -> Unit = {}, onStaff: (Event) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val ticketState by tickets.state.collectAsState()
    val event = state.events.firstOrNull { it.id == state.selectedId }
    LaunchedEffect(event?.id, event?.status) { if(event?.status == "published") tickets.load(event.id) }
    var confirm by remember(state.selectedId) { mutableStateOf(false) }
    var cancelConfirm by remember(state.selectedId) { mutableStateOf(false) }
    var hideConfirm by remember(state.selectedId) { mutableStateOf<com.example.biletflow.domain.models.TicketType?>(null) }
    val locked = state.changingStatus || state.statusUnknown || state.loading || event?.status == "cancelled"
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
                Button(onClick = { onPaidSales(event) }) { Text(stringResource(Res.string.paid_sales_title)) }
                Button(onClick = { onAttendees(event) }) { Text(stringResource(Res.string.attendees_title)) }
                Button(onClick = { onStaff(event) }) { Text(stringResource(Res.string.staff_title)) }
                listOfNotNull(event.description, event.category, event.venueName, event.venueAddress,
                    event.capacity?.toString(), event.registrationOpensAt, event.registrationClosesAt, event.refundPolicy)
                    .forEach { Text(it) }
                Button(onClick = { onEdit(event) }, enabled = !locked) { Text(stringResource(Res.string.event_edit)) }
                Button(onClick = { tickets.open(event.id) }, enabled = !locked) { Text(stringResource(Res.string.ticket_create_title)) }
                if (event.status in listOf("draft", "published")) Button(onClick = { confirm = true }, enabled = !locked) {
                    Text(stringResource(if (event.status == "draft") Res.string.event_publish else Res.string.event_unpublish))
                }
                if(event.status in listOf("draft","published")) Button(onClick = { cancelConfirm = true },enabled = !locked && ticketState.hiding.isEmpty()) {
                    Text(stringResource(Res.string.event_cancel))
                }
                if(event.status == "cancelled") Text(stringResource(Res.string.event_cancelled_success))
                if (state.changingStatus) CircularProgressIndicator()
                if (state.statusUnknown) {
                    Text(stringResource(Res.string.event_status_unknown))
                    Button(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.payment_check_status)) }
                } else state.statusError?.let {
                    Text(stringResource(Res.string.event_status_failed), color = MaterialTheme.colorScheme.error)
                    Text(it)
                }
                if (state.statusSuccess) Text(stringResource(when {
                    event.status == "cancelled" -> Res.string.event_cancelled_success
                    event.status == "draft" -> Res.string.event_unpublished_success
                    event.visibility == "public" -> Res.string.event_published_public
                    event.visibility == "unlisted" -> Res.string.event_published_unlisted
                    else -> Res.string.event_published_private
                }))
                if (ticketState.listLoading) CircularProgressIndicator()
                if (ticketState.listError && event.status == "published") Button(onClick = { tickets.load(event.id) }) { Text(stringResource(Res.string.events_retry)) }
                tickets.list(event.id).let { added ->
                    Text(stringResource(if(event.status == "published") Res.string.ticket_list_partial else Res.string.ticket_created_session))
                    Text(stringResource(Res.string.ticket_hidden_limit))
                    added.forEach { ticket ->
                        val ticketLocked = locked || ticket.id in ticketState.hiding || ticket.id in ticketState.hideUnknown
                        Card(onClick = { tickets.edit(ticket) }, enabled = !ticketLocked, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${ticket.name} · #${ticket.id}")
                                Text(if (ticket.price == 0) stringResource(Res.string.ticket_price_free) else "${ticket.price} ₸")
                                ticket.description?.let { Text(it) }
                                Text("${ticket.quantity} · ${ticket.salesStart} — ${ticket.salesEnd}")
                                if(ticket.isHidden) Text(stringResource(Res.string.ticket_hidden))
                                Button(onClick = { if(ticket.isHidden) tickets.setHidden(ticket,false) else hideConfirm = ticket }, enabled = !ticketLocked) {
                                    Text(stringResource(if(ticket.isHidden) Res.string.ticket_unhide else Res.string.ticket_hide))
                                }
                                if(ticket.id in ticketState.hideErrors) Text(stringResource(if(ticket.id in ticketState.hideUnknown) Res.string.ticket_hide_unknown else Res.string.create_failed))
                                if(ticket.id in ticketState.hideUnknown && event.status == "published") TextButton(onClick = { tickets.load(event.id) }) {
                                    Text(stringResource(Res.string.payment_check_status))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(cancelConfirm && event != null) AlertDialog(
        onDismissRequest = { cancelConfirm = false },
        title = { Text(stringResource(Res.string.event_cancel)) },
        text = { Text(stringResource(Res.string.event_cancel_info)) },
        confirmButton = { TextButton(onClick = { cancelConfirm = false; vm.cancelEvent() }) { Text(stringResource(Res.string.event_cancel)) } },
        dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text(stringResource(Res.string.auth_close)) } },
    )
    hideConfirm?.let { ticket -> AlertDialog(
        onDismissRequest = { hideConfirm = null },
        title = { Text(stringResource(Res.string.ticket_hide)) },
        text = { Text(stringResource(Res.string.ticket_hide_confirm)) },
        confirmButton = { TextButton(onClick = { hideConfirm = null; tickets.setHidden(ticket,true) }) { Text(stringResource(Res.string.ticket_hide)) } },
        dismissButton = { TextButton(onClick = { hideConfirm = null }) { Text(stringResource(Res.string.auth_close)) } },
    ) }
    if (confirm && event != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (event.status == "draft") Res.string.event_publish else Res.string.event_unpublish)) },
        text = { Text(stringResource(if (event.status == "draft") Res.string.event_publish_info else Res.string.event_unpublish_info)) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.changeStatus(event.status == "draft") }) { Text(stringResource(Res.string.event_status_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.auth_close)) } },
    )
}
