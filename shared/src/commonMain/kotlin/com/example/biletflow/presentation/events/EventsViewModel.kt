package com.example.biletflow.presentation.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.repositories.EventsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class EventCategoryFilter { All, Concerts, Lectures, Sport }

data class EventsUiState(
    val isLoading: Boolean = true,
    val events: List<Event> = emptyList(),
    val loadFailed: Boolean = false,
    val query: String = "",
    val category: EventCategoryFilter = EventCategoryFilter.All,
)

class EventsViewModel(private val repository: EventsRepository) : ViewModel() {
    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        if (_state.value.isLoading && (_state.value.events.isNotEmpty() || _state.value.loadFailed)) return
        _state.value = _state.value.copy(isLoading = true, loadFailed = false)
        viewModelScope.launch {
            runCatching { repository.getEvents() }
                .onSuccess { events ->
                    _state.value = _state.value.copy(isLoading = false, events = events, loadFailed = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false, events = emptyList(), loadFailed = true)
                }
        }
    }

    fun updateQuery(query: String) { _state.value = _state.value.copy(query = query) }
    fun selectCategory(category: EventCategoryFilter) { _state.value = _state.value.copy(category = category) }
}

internal fun filterEvents(events: List<Event>, query: String, category: EventCategoryFilter): List<Event> {
    val normalizedQuery = query.trim().lowercase()
    return events.filter { event ->
        val matchesQuery = normalizedQuery.isEmpty() ||
            event.title.lowercase().contains(normalizedQuery) ||
            event.venueName?.lowercase()?.contains(normalizedQuery) == true
        matchesQuery && category.matches(event.category)
    }
}

private fun EventCategoryFilter.matches(value: String?): Boolean {
    if (this == EventCategoryFilter.All) return true
    val normalized = value?.trim()?.lowercase() ?: return false
    return when (this) {
        EventCategoryFilter.All -> true
        EventCategoryFilter.Concerts -> normalized in setOf("concert", "concerts", "концерт", "концерты")
        EventCategoryFilter.Lectures -> normalized in setOf("lecture", "lectures", "лекция", "лекции")
        EventCategoryFilter.Sport -> normalized in setOf("sport", "sports", "спорт")
    }
}

internal fun formatEventDate(value: String): String {
    val timeSeparator = value.indexOf('T')
    val positiveOffset = value.indexOf('+', startIndex = timeSeparator + 1)
    val negativeOffset = value.indexOf('-', startIndex = timeSeparator + 1)
    val offsetIndex = listOf(positiveOffset, negativeOffset).filter { it >= 0 }.minOrNull()
    val dateAndTime = when {
        value.endsWith('Z') -> value.dropLast(1)
        offsetIndex != null -> value.substring(0, offsetIndex)
        else -> value
    }
    val parts = dateAndTime.split('T')
    if (parts.size != 2) return value
    val date = parts[0].split('-')
    val time = parts[1].take(5)
    if (date.size != 3 || time.length != 5) return value
    val offset = when {
        value.endsWith('Z') -> "UTC"
        offsetIndex != null -> "UTC${value.substring(offsetIndex)}"
        else -> null
    }
    return buildString {
        append("${date[2]}.${date[1]}.${date[0]} · $time")
        offset?.let { append(" ($it)") }
    }
}
