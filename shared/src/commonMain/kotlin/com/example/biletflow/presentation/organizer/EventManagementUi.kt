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
fun EventManagementPage(vm: MyEventsViewModel, onEdit: (Event) -> Unit, tickets: CreateTicketTypeViewModel, onPaidSales: (Event) -> Unit = {}, onAttendees: (Event) -> Unit = {}, onStaff: (Event) -> Unit = {}, onSales: (Event) -> Unit = {}, onCopy: (Event) -> Unit = {}, onOrders: (Event) -> Unit = {}, onSupport: (Event) -> Unit = {}, onCampaigns: (Event) -> Unit = {}, onAnalytics: (Event) -> Unit = {}, onHistory: (Event) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val ticketState by tickets.state.collectAsState()
    val event = state.events.firstOrNull { it.id == state.selectedId }
    LaunchedEffect(event?.id, event?.status) { event?.let { tickets.load(it.id) } }
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
                Button(onClick = { onSales(event) }) { Text(stringResource(Res.string.sales_title)) }
                Button(onClick = { onAnalytics(event) }) { Text(stringResource(Res.string.analytics_title)) }
                Button(onClick = { onHistory(event) }) { Text(stringResource(Res.string.history_title)) }
                Button(onClick = { onCampaigns(event) }) { Text(stringResource(Res.string.campaigns_title)) }
                Button(onClick = { onSupport(event) }) { Text(stringResource(Res.string.support_event_requests)) }
                Button(onClick = { onOrders(event) }) { Text(stringResource(Res.string.organizer_orders_title)) }
                // Server-side duplicate replaces the old copy-through-form flow (onCopy is kept for API compatibility).
                Button(onClick = vm::openDuplicate, enabled = !state.loading && !state.changingStatus && !state.duplicating) { Text(stringResource(Res.string.event_duplicate)) }
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
                Text(stringResource(Res.string.organizer_ticket_list_title))
                TextButton(onClick = { tickets.load(event.id) }, enabled = !ticketState.listLoading && !ticketState.saving && ticketState.hiding.isEmpty()) {
                    Text(stringResource(Res.string.my_events_refresh))
                }
                if (ticketState.listError) {
                    Text(stringResource(Res.string.organizer_ticket_list_error), color = MaterialTheme.colorScheme.error)
                    ticketState.listErrorMessage?.let { Text(it) }
                    Button(onClick = { tickets.load(event.id) }) { Text(stringResource(Res.string.events_retry)) }
                }
                ticketState.verificationErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                tickets.list(event.id).let { added ->
                    if (!ticketState.listLoading && !ticketState.listError && added.isEmpty()) Text(stringResource(Res.string.organizer_ticket_list_empty))
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
                            }
                        }
                        if(ticket.id in ticketState.hideErrors) Text(stringResource(if(ticket.id in ticketState.hideUnknown) Res.string.organizer_ticket_visibility_unknown else Res.string.create_failed))
                        if(ticket.id in ticketState.hideUnknown) TextButton(onClick = { tickets.checkHidden(ticket) }, enabled = ticket.id !in ticketState.hiding) {
                            Text(stringResource(Res.string.payment_check_status))
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
    if (state.duplicateOpen && event != null) AlertDialog(
        onDismissRequest = vm::closeDuplicate,
        title = { Text(stringResource(Res.string.event_duplicate)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.event_duplicate_info))
            OutlinedTextField(state.duplicateTitle, { vm.updateDuplicate(title = it) }, singleLine = true, enabled = !state.duplicating,
                label = { Text(stringResource(Res.string.event_duplicate_title)) })
            OutlinedTextField(state.duplicateStart, { vm.updateDuplicate(start = it) }, singleLine = true, enabled = !state.duplicating,
                isError = state.duplicateStartInvalid, label = { Text(stringResource(Res.string.event_duplicate_start)) })
            if (state.duplicateStartInvalid) Text(stringResource(Res.string.create_date_error), color = MaterialTheme.colorScheme.error)
            state.duplicateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.duplicateUnknown && !state.duplicating) Text(stringResource(Res.string.event_duplicate_unknown), color = MaterialTheme.colorScheme.error)
            if (state.duplicating) CircularProgressIndicator()
        } },
        confirmButton = { TextButton(onClick = vm::duplicate, enabled = !state.duplicating && !state.duplicateUnknown) { Text(stringResource(Res.string.event_duplicate_confirm)) } },
        dismissButton = { TextButton(onClick = vm::closeDuplicate, enabled = !state.duplicating) { Text(stringResource(Res.string.auth_close)) } },
    )
    if (confirm && event != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (event.status == "draft") Res.string.event_publish else Res.string.event_unpublish)) },
        text = { Text(stringResource(if (event.status == "draft") Res.string.event_publish_info else Res.string.event_unpublish_info)) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.changeStatus(event.status == "draft") }) { Text(stringResource(Res.string.event_status_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.auth_close)) } },
    )
}
