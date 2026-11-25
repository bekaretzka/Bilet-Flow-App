package com.example.biletflow.presentation.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.repositories.EventsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.toLocalDateTime

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


/** Kazakhstan has one time zone, UTC+5, since 2024. A fixed offset avoids old tzdata on devices that still put Almaty at +6. */
internal val AstanaOffset = kotlinx.datetime.UtcOffset(hours = 5)

/**
 * Server times are instants (`...Z` or with an offset). They are shown in Astana time, as in Figma «Время Астаны (UTC+5)»:
 * `25.11.2026 · 02:48 (UTC+5)`. A value that is not an instant is returned unchanged.
 */
internal fun formatEventDate(value: String): String = formatAstana(value, withZone = true) ?: value

internal fun formatAstana(value: String, withZone: Boolean): String? {
    val local = runCatching {
        kotlinx.datetime.Instant.parse(value.trim()).toLocalDateTime(kotlinx.datetime.FixedOffsetTimeZone(AstanaOffset))
    }.getOrNull() ?: return null
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(local.dayOfMonth)}.${two(local.monthNumber)}.${local.year} · ${two(local.hour)}:${two(local.minute)}" +
        if (withZone) " (UTC+5)" else ""
}
