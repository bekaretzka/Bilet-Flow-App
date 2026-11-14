package com.example.biletflow.presentation.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import org.jetbrains.compose.resources.stringResource

private val PageBackground = Color(0xFFF7F4EC)
private val Ink = Color(0xFF17171B)
private val Muted = Color(0xFF6F6C72)
private val Purple = Color(0xFF4035D6)
private val FieldBorder = Color(0xFFD9D5CC)
private val Cover = Color(0xFFDDD7C7)

@Composable
fun EventCatalogPage(
    viewModel: EventsViewModel,
    onOpenEvent: (Int) -> Unit,
    onOpenOrders: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val visibleEvents = filterEvents(state.events, state.query, state.category)
    val filters = listOf(
        EventCategoryFilter.All to stringResource(Res.string.events_filter_all),
        EventCategoryFilter.Concerts to stringResource(Res.string.events_filter_concerts),
        EventCategoryFilter.Lectures to stringResource(Res.string.events_filter_lectures),
        EventCategoryFilter.Sport to stringResource(Res.string.events_filter_sport),
    )

    Column(
        modifier = Modifier.fillMaxSize().background(PageBackground)
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        CatalogHeader(onOpenOrders)
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.events_search_placeholder), color = Muted, fontSize = 15.sp) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = FieldBorder,
                focusedBorderColor = Purple,
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
            ),
        )
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            filters.forEach { (filter, label) ->
                FilterChip(label, selected = state.category == filter) { viewModel.selectCategory(filter) }
            }
        }
        Spacer(Modifier.height(22.dp))
        when {
            state.isLoading -> CatalogMessage(stringResource(Res.string.events_loading), loading = true)
            state.loadFailed -> ErrorState(onRetry = viewModel::reload)
            state.events.isEmpty() -> CatalogMessage(stringResource(Res.string.events_empty_catalog))
            visibleEvents.isEmpty() -> CatalogMessage(stringResource(Res.string.events_no_matches))
            else -> visibleEvents.forEach { event ->
                EventCard(event = event, onClick = { onOpenEvent(event.id) })
                Spacer(Modifier.height(16.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CatalogHeader(onOpenOrders: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("BiletFlow", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onOpenOrders) {
                Text(
                    stringResource(Res.string.order_history_action),
                    color = Purple,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, FieldBorder)) {
                Text(
                    text = stringResource(Res.string.language_ru),
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogMessage(text: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loading) CircularProgressIndicator(color = Purple)
        Text(text, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.events_load_error), color = Muted, fontSize = 16.sp)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        ) { Text(stringResource(Res.string.events_retry), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.border(1.dp, if (selected) Ink else FieldBorder, RoundedCornerShape(28.dp)),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) Ink else Color.White,
            contentColor = if (selected) Color.White else Ink,
        ),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun EventCard(event: Event, onClick: () -> Unit) {
    val category = event.category?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.event_category_unavailable)
    val venue = event.venueName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.event_venue_unavailable)

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, FieldBorder),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(190.dp).background(Cover),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.event_cover_placeholder), color = Muted, fontSize = 16.sp) }
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Tag(category)
                Spacer(Modifier.height(10.dp))
                Text(event.title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(formatEventDate(event.startTime), color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(venue, color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "${stringResource(Res.string.event_more)} →",
                    color = Purple,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(9.dp), color = PageBackground) {
        Text(
            text = text,
            color = Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private val previewEvent = Event(
    id = 1,
    organizerId = 1,
    title = "Инди-вечер в Астане",
    startTime = "2026-12-05T19:00:00+05:00",
    endTime = "2026-12-05T22:00:00+05:00",
    status = "published",
    category = "Концерт",
    venueName = "Конгресс-центр",
    visibility = "public",
)

@Composable
@Preview(showBackground = true)
private fun EventCatalogPreview() {
    val repository = object : EventsRepository {
        override suspend fun getEvents() = listOf(previewEvent)
        override suspend fun getEvent(eventId: Int) = previewEvent
        override suspend fun getTicketTypes(eventId: Int) = emptyList<TicketType>()
    }
    MaterialTheme {
        EventCatalogPage(
            viewModel = EventsViewModel(repository),
            onOpenEvent = {},
            onOpenOrders = {},
        )
    }
}
