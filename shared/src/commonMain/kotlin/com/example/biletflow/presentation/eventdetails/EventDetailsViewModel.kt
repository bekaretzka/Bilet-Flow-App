package com.example.biletflow.presentation.eventdetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.EventsApiException
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.repositories.EventsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class EventDetailsError { NotFound, Network }
enum class TicketSalesStatus { Available, SoldOut, NotStarted, Ended, Unavailable }

data class EventDetailsUiState(
    val eventId: Int? = null,
    val isLoading: Boolean = false,
    val event: Event? = null,
    val error: EventDetailsError? = null,
    val ticketTypes: List<TicketType> = emptyList(),
    val areTicketsLoading: Boolean = false,
    val ticketsLoadFailed: Boolean = false,
    val selectedQuantities: Map<Int, Int> = emptyMap(),
    val selectionNeedsReview: Boolean = false,
)

class EventDetailsViewModel(
    private val repository: EventsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EventDetailsUiState())
    val state: StateFlow<EventDetailsUiState> = _state.asStateFlow()

    fun load(eventId: Int) {
        _state.value = EventDetailsUiState(eventId = eventId, isLoading = true)
        viewModelScope.launch {
            runCatching { repository.getEvent(eventId) }
                .onSuccess { event ->
                    if (_state.value.eventId == eventId) {
                        _state.value = EventDetailsUiState(eventId = eventId, event = event)
                        loadTicketTypes(eventId)
                    }
                }
                .onFailure { throwable ->
                    if (_state.value.eventId == eventId) {
                        _state.value = EventDetailsUiState(
                            eventId = eventId,
                            error = if (throwable is EventsApiException && throwable.statusCode == 404) {
                                EventDetailsError.NotFound
                            } else {
                                EventDetailsError.Network
                            },
                        )
                    }
                }
        }
    }

    fun ensureLoaded(eventId: Int) {
        val state = _state.value
        if (state.eventId == eventId && state.event != null) {
            loadTicketTypes(eventId)
            viewModelScope.launch {
                try {
                    val event = repository.getEvent(eventId)
                    if (_state.value.eventId == eventId) _state.value = _state.value.copy(event = event)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (_state.value.eventId == eventId && error is EventsApiException && error.statusCode == 404)
                        _state.value = EventDetailsUiState(eventId = eventId, error = EventDetailsError.NotFound)
                }
            }
            return
        }
        load(eventId)
    }

    fun loadTicketTypes(eventId: Int) {
        val currentState = _state.value
        if (currentState.eventId != eventId || currentState.event == null) return

        _state.value = currentState.copy(
            ticketTypes = emptyList(),
            areTicketsLoading = true,
            ticketsLoadFailed = false,
        )
        viewModelScope.launch {
            runCatching { repository.getTicketTypes(eventId) }
                .onSuccess { ticketTypes ->
                    if (_state.value.eventId == eventId) {
                        val previousSelection = _state.value.selectedQuantities
                        val reconciledSelection = reconcileSelection(
                            selection = previousSelection,
                            ticketTypes = ticketTypes,
                        )
                        _state.value = _state.value.copy(
                            ticketTypes = ticketTypes,
                            areTicketsLoading = false,
                            ticketsLoadFailed = false,
                            selectedQuantities = reconciledSelection,
                            selectionNeedsReview = _state.value.selectionNeedsReview ||
                                reconciledSelection != previousSelection,
                        )
                    }
                }
                .onFailure {
                    if (_state.value.eventId == eventId) {
                        _state.value = _state.value.copy(
                            ticketTypes = emptyList(),
                            areTicketsLoading = false,
                            ticketsLoadFailed = true,
                        )
                    }
                }
        }
    }

    fun incrementTicket(ticketTypeId: Int) {
        val state = _state.value
        val ticketType = state.ticketTypes.firstOrNull { it.id == ticketTypeId } ?: return
        val limit = ticketSelectionLimit(ticketType)
        val current = state.selectedQuantities[ticketTypeId] ?: 0
        if (current >= limit) return
        _state.value = state.copy(
            selectedQuantities = state.selectedQuantities + (ticketTypeId to current + 1),
            selectionNeedsReview = false,
        )
    }

    fun decrementTicket(ticketTypeId: Int) {
        val state = _state.value
        val current = state.selectedQuantities[ticketTypeId] ?: 0
        if (current <= 0) return
        val updated = state.selectedQuantities.toMutableMap()
        if (current == 1) updated.remove(ticketTypeId) else updated[ticketTypeId] = current - 1
        _state.value = state.copy(selectedQuantities = updated, selectionNeedsReview = false)
    }

    fun prepareOrderReview(eventId: Int): Boolean {
        val state = _state.value
        if (state.eventId != eventId || state.event == null || state.ticketTypes.isEmpty()) return false

        val reconciledSelection = reconcileSelection(
            selection = state.selectedQuantities,
            ticketTypes = state.ticketTypes,
        )
        if (reconciledSelection != state.selectedQuantities) {
            _state.value = state.copy(
                selectedQuantities = reconciledSelection,
                selectionNeedsReview = true,
            )
            return false
        }
        if (reconciledSelection.isEmpty()) return false

        _state.value = state.copy(selectionNeedsReview = false)
        return true
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            selectedQuantities = emptyMap(),
            selectionNeedsReview = false,
        )
    }
}

fun ticketSalesStatus(
    ticketType: TicketType,
    now: Instant = Clock.System.now(),
): TicketSalesStatus {
    val remaining = (ticketType.quantity - ticketType.soldCount).coerceAtLeast(0)
    if (remaining == 0) return TicketSalesStatus.SoldOut
    val salesStart = runCatching { Instant.parse(ticketType.salesStart) }.getOrNull()
        ?: return TicketSalesStatus.Unavailable
    val salesEnd = runCatching { Instant.parse(ticketType.salesEnd) }.getOrNull()
        ?: return TicketSalesStatus.Unavailable
    return when {
        now < salesStart -> TicketSalesStatus.NotStarted
        now > salesEnd -> TicketSalesStatus.Ended
        else -> TicketSalesStatus.Available
    }
}

fun ticketSelectionLimit(ticketType: TicketType, now: Instant = Clock.System.now()): Int {
    if (ticketSalesStatus(ticketType, now) != TicketSalesStatus.Available) return 0
    val remaining = (ticketType.quantity - ticketType.soldCount).coerceAtLeast(0)
    return minOf(ticketType.maxPerOrder.coerceAtLeast(0), remaining)
}

private fun reconcileSelection(
    selection: Map<Int, Int>,
    ticketTypes: List<TicketType>,
    now: Instant = Clock.System.now(),
): Map<Int, Int> = buildMap {
    ticketTypes.forEach { ticketType ->
        val selected = selection[ticketType.id] ?: return@forEach
        val adjusted = selected.coerceIn(0, ticketSelectionLimit(ticketType, now))
        if (adjusted > 0) put(ticketType.id, adjusted)
    }
}
