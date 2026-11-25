package com.example.biletflow.presentation.eventdetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biletflow.shared.generated.resources.*
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.repositories.EventsRepository
import com.example.biletflow.presentation.events.formatEventDate
import org.jetbrains.compose.resources.stringResource

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val FieldBorder = Color(0xFFD9D5CC)
private val Cover = Color(0xFFDDD7C7)

@Composable
fun EventDetailsPage(
    eventId: Int,
    viewModel: EventDetailsViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSupport: () -> Unit = {},
    onLogin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(eventId) { viewModel.ensureLoaded(eventId) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground)
            .padding(horizontal = 24.dp).statusBarsPadding(),
    ) {
        EventHeader(onBack)
        val currentState = if (state.eventId == eventId) state else EventDetailsUiState(
            eventId = eventId,
            isLoading = true,
        )
        when {
            currentState.isLoading -> DetailsMessage(
                message = stringResource(Res.string.event_details_loading),
                loading = true,
            )
            currentState.error == EventDetailsError.NotFound -> DetailsMessage(
                message = stringResource(Res.string.event_details_unavailable),
            )
            currentState.error == EventDetailsError.Network -> DetailsError(
                onRetry = { viewModel.load(eventId) },
            )
            currentState.event != null -> EventDetailsContent(
                state = currentState,
                onRetryTickets = { viewModel.loadTicketTypes(eventId) },
                onIncrementTicket = viewModel::incrementTicket,
                onDecrementTicket = viewModel::decrementTicket,
                onContinue = {
                    if (viewModel.prepareOrderReview(eventId)) onContinue()
                },
                modifier = Modifier.weight(1f),
                onSupport = onSupport,
                onLogin = onLogin,
            )
        }
    }
}

@Composable
private fun EventDetailsContent(
    state: EventDetailsUiState,
    onRetryTickets: () -> Unit,
    onIncrementTicket: (Int) -> Unit,
    onDecrementTicket: (Int) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onSupport: () -> Unit,
    onLogin: () -> Unit = {},
) {
    val event = requireNotNull(state.event)
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(190.dp).background(Cover, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.event_details_cover_placeholder), color = Muted, fontSize = 16.sp)
            event.imageUrl?.let { com.example.biletflow.presentation.documents.PublicImage(it, event.title, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) }
        }
        Spacer(Modifier.height(18.dp))
        Tag(
            event.category?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.event_category_unavailable),
        )
        Spacer(Modifier.height(14.dp))
        Text(event.title, color = Ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onSupport) { Text(stringResource(Res.string.support_contact)) }
        ReportEventButton(event.id, onLogin)
        Spacer(Modifier.height(22.dp))
        EventInfo(
            icon = "▣",
            title = formatEventDate(event.startTime),
            subtitle = stringResource(Res.string.event_details_ends, formatEventDate(event.endTime)),
        )
        Spacer(Modifier.height(16.dp))
        EventInfo(
            icon = "⌖",
            title = event.venueName?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.event_venue_unavailable),
            subtitle = event.venueAddress?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.event_details_address_unavailable),
        )
        Spacer(Modifier.height(28.dp))
        TicketTypesSection(
            state = state,
            onRetry = onRetryTickets,
            onIncrement = onIncrementTicket,
            onDecrement = onDecrementTicket,
            onContinue = onContinue,
        )
        Spacer(Modifier.height(28.dp))
        Text(stringResource(Res.string.event_about), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            event.description?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.event_details_description_unavailable),
            color = Muted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TicketTypesSection(
    state: EventDetailsUiState,
    onRetry: () -> Unit,
    onIncrement: (Int) -> Unit,
    onDecrement: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    Text(stringResource(Res.string.event_tickets), color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))
    when {
        state.areTicketsLoading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = Purple)
        }
        state.ticketsLoadFailed -> Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.ticket_types_load_error), color = Muted, fontSize = 15.sp)
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(Res.string.events_retry), fontWeight = FontWeight.Bold) }
        }
        state.ticketTypes.isEmpty() -> Text(
            stringResource(Res.string.ticket_types_empty),
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        else -> state.ticketTypes.forEachIndexed { index, ticketType ->
            TicketTypeCard(
                ticketType = ticketType,
                selectedQuantity = state.selectedQuantities[ticketType.id] ?: 0,
                onIncrement = { onIncrement(ticketType.id) },
                onDecrement = { onDecrement(ticketType.id) },
            )
            if (index != state.ticketTypes.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
    val selectedTickets = state.ticketTypes.mapNotNull { ticketType ->
        val quantity = state.selectedQuantities[ticketType.id] ?: 0
        if (quantity > 0) ticketType to quantity else null
    }
    if (selectedTickets.isNotEmpty()) {
        if (state.selectionNeedsReview) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFE9CC),
            ) {
                Text(
                    stringResource(Res.string.ticket_selection_updated),
                    color = Color(0xFF7A4300),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SelectionSummary(
            quantity = selectedTickets.sumOf { it.second },
            total = selectedTickets.sumOf { (ticketType, quantity) ->
                ticketType.price.toLong() * quantity.toLong()
            },
            onContinue = onContinue,
        )
    } else if (state.selectionNeedsReview) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(Res.string.ticket_selection_unavailable),
            color = Color(0xFFB42B24),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TicketTypeCard(
    ticketType: TicketType,
    selectedQuantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    val remaining = (ticketType.quantity - ticketType.soldCount).coerceAtLeast(0)
    val salesStatus = ticketSalesStatus(ticketType)
    val selectionLimit = ticketSelectionLimit(ticketType)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, FieldBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    ticketType.name,
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    if (ticketType.price == 0) {
                        stringResource(Res.string.ticket_price_free)
                    } else {
                        stringResource(Res.string.ticket_price_kzt, formatMoney(ticketType.price))
                    },
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            ticketType.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(description, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when (salesStatus) {
                    TicketSalesStatus.Available -> stringResource(Res.string.ticket_remaining, remaining)
                    TicketSalesStatus.SoldOut -> stringResource(Res.string.ticket_sold_out)
                    TicketSalesStatus.NotStarted -> stringResource(Res.string.ticket_sales_not_started)
                    TicketSalesStatus.Ended -> stringResource(Res.string.ticket_sales_ended)
                    TicketSalesStatus.Unavailable -> stringResource(Res.string.ticket_sales_unavailable)
                },
                color = if (salesStatus == TicketSalesStatus.Available) Muted else Color(0xFFB42B24),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CounterButton(
                    text = "−",
                    enabled = salesStatus == TicketSalesStatus.Available && selectedQuantity > 0,
                    onClick = onDecrement,
                )
                Text(
                    selectedQuantity.toString(),
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                CounterButton(
                    text = "+",
                    enabled = salesStatus == TicketSalesStatus.Available && selectedQuantity < selectionLimit,
                    filled = true,
                    onClick = onIncrement,
                )
            }
        }
    }
}

@Composable
private fun CounterButton(
    text: String,
    enabled: Boolean,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(48.dp).height(48.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) Purple else Color.White,
            contentColor = if (filled) Color.White else Purple,
            disabledContainerColor = PageBackground,
            disabledContentColor = Muted,
        ),
        border = if (!filled) BorderStroke(1.dp, if (enabled) Purple else FieldBorder) else null,
    ) {
        Text(text, fontSize = 22.sp)
    }
}

@Composable
private fun SelectionSummary(quantity: Int, total: Long, onContinue: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFE9E7FF),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.ticket_selection_total), color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.ticket_selection_count, quantity),
                        color = Ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (total == 0L) {
                        stringResource(Res.string.ticket_price_free)
                    } else {
                        stringResource(Res.string.ticket_price_kzt, formatMoney(total))
                    },
                    color = Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
            ) {
                Text(stringResource(Res.string.order_review_continue), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatMoney(value: Number): String = value.toString()
    .reversed()
    .chunked(3)
    .joinToString(" ")
    .reversed()

@Composable
private fun EventHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("‹", color = Ink, fontSize = 32.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text("BiletFlow", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, FieldBorder),
        ) {
            Text(
                stringResource(Res.string.language_ru),
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun EventInfo(icon: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(icon, color = Purple, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(9.dp), color = Color.White, border = BorderStroke(1.dp, FieldBorder)) {
        Text(
            text,
            color = Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DetailsMessage(message: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loading) CircularProgressIndicator(color = Purple)
        Text(message, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun DetailsError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.event_details_load_error), color = Muted, fontSize = 16.sp)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        ) { Text(stringResource(Res.string.events_retry), fontWeight = FontWeight.Bold) }
    }
}

private val previewEvent = Event(
    id = 101,
    organizerId = 11,
    title = "Инди-вечер в Астане",
    description = "Живой концерт локальных групп.",
    startTime = "2026-12-05T19:00:00+05:00",
    endTime = "2026-12-05T22:00:00+05:00",
    status = "published",
    category = "Концерт",
    venueName = "Конгресс-центр",
    venueAddress = "проспект Сауран, 44",
    visibility = "public",
)

@Composable
@Preview(showBackground = true)
private fun EventDetailsPreview() {
    val repository = object : EventsRepository {
        override suspend fun getEvents() = listOf(previewEvent)
        override suspend fun getEvent(eventId: Int) = previewEvent
        override suspend fun getTicketTypes(eventId: Int) = emptyList<TicketType>()
    }
    MaterialTheme {
        EventDetailsPage(
            eventId = previewEvent.id,
            viewModel = EventDetailsViewModel(repository),
            onBack = {},
            onContinue = {},
        )
    }
}
