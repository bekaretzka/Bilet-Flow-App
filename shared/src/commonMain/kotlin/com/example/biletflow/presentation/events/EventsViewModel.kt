package com.example.biletflow.presentation.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.repositories.EventsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EventsUiState(
    val isLoading: Boolean = true, val events: List<Event> = emptyList(), val loadFailed: Boolean = false,
    val query: String = "", val category: String? = null, val categories: List<String> = emptyList(),
    val categoriesLoading: Boolean = false, val categoriesError: String? = null,
    val total: Int = 0, val nextOffset: Int = 0, val loadingMore: Boolean = false,
    val pageError: String? = null, val error: String? = null, val filterRevision: Int = 0,
) { val hasMore: Boolean get() = nextOffset < total }

class EventsViewModel(private val repository: EventsRepository, private val pageSize: Int = 20): ViewModel() {
    private val mutable=MutableStateFlow(EventsUiState())
    val state=mutable.asStateFlow()
    private var job: Job?=null
    private var sequence=0
    private var categoryJob: Job?=null
    init { reload() }
    fun reloadCategories() {
        if(categoryJob?.isActive == true) return
        mutable.value=mutable.value.copy(categoriesLoading=true,categoriesError=null)
        categoryJob=viewModelScope.launch {
            try { val categories=repository.getCategories().distinct(); mutable.value=mutable.value.copy(categories=categories,categoriesLoading=false) }
            catch(error: Exception) { if(error is CancellationException) throw error; mutable.value=mutable.value.copy(categoriesLoading=false,categoriesError=error.message ?: "network") }
        }
    }
    fun updateQuery(query: String) {
        if(query == mutable.value.query) return
        mutable.value=mutable.value.copy(query=query)
        reset(300)
    }
    fun selectCategory(category: String?) {
        if(category == mutable.value.category) return
        mutable.value=mutable.value.copy(category=category)
        reset(0)
    }
    private fun reset(debounce: Long) {
        mutable.value=mutable.value.copy(filterRevision=mutable.value.filterRevision+1)
        load(false,debounce)
    }
    fun reload() { load(false,0); reloadCategories() }
    fun loadMore() { if(!mutable.value.isLoading && !mutable.value.loadingMore && mutable.value.hasMore) load(true,0) }
    private fun load(append: Boolean,debounce: Long) {
        job?.cancel(); val id=++sequence
        val current=mutable.value
        val offset=if(append) current.nextOffset else 0
        mutable.value=current.copy(isLoading=!append,loadingMore=append,loadFailed=false,error=null,pageError=null,
            events=if(append) current.events else emptyList(),total=if(append) current.total else 0,nextOffset=offset)
        job=viewModelScope.launch {
            try {
                delay(debounce)
                val page=repository.getEventsPage(current.query.trim().takeIf { it.isNotEmpty() },current.category,pageSize,offset)
                if(id != sequence) return@launch
                mutable.value=mutable.value.copy(isLoading=false,loadingMore=false,
                    events=((if(append) mutable.value.events else emptyList())+page.items).distinctBy { it.id },
                    total=page.total,nextOffset=if(page.items.isEmpty()) page.total else offset+page.items.size)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(id != sequence) return@launch
                mutable.value=mutable.value.copy(isLoading=false,loadingMore=false,loadFailed=!append,
                    error=if(append) null else error.message ?: "network",pageError=if(append) error.message ?: "network" else null)
            }
        }
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
