package com.example.biletflow

import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.EventsRepositoryImpl
import com.example.biletflow.domain.models.Event
import com.example.biletflow.presentation.events.EventCategoryFilter
import com.example.biletflow.presentation.events.EventsViewModel
import com.example.biletflow.presentation.events.filterEvents
import com.example.biletflow.presentation.events.formatEventDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventsCatalogTest {
    @Test
    fun mockResponseUsesProductionDeserializationPath() = runTest {
        val events = EventsRepositoryImpl(
            client = createMockEventsHttpClient(),
            baseUrl = "http://mock.biletflow",
        ).getEvents()

        assertEquals(4, events.size)
        assertEquals("Инди-вечер в Астане", events.first().title)
        assertNull(events.last().category)
        assertNull(events.last().venueName)
    }

    @Test
    fun searchTrimsQueryAndIgnoresCase() {
        val events = sampleEvents()

        assertEquals(listOf(1), filterEvents(events, "  ИНДИ  ", EventCategoryFilter.All).map { it.id })
        assertEquals(listOf(2), filterEvents(events, " библиотека ", EventCategoryFilter.All).map { it.id })
    }

    @Test
    fun searchAndCategoryAreAppliedTogether() {
        val events = sampleEvents()

        assertEquals(listOf(1), filterEvents(events, "аст", EventCategoryFilter.Concerts).map { it.id })
        assertEquals(emptyList(), filterEvents(events, "аст", EventCategoryFilter.Lectures))
        assertEquals(listOf(1, 2), filterEvents(events, "", EventCategoryFilter.All).map { it.id })
    }

    @Test
    fun isoDateWithOffsetIsFormattedForCard() {
        assertEquals("05.12.2026 · 19:00 (UTC+05:00)", formatEventDate("2026-12-05T19:00:00+05:00"))
    }

    @Test
    fun retryReplacesErrorWithLoadedEvents() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val repository = object : com.example.biletflow.domain.repositories.EventsRepository {
                override suspend fun getEvents(): List<Event> {
                    attempts++
                    if (attempts == 1) error("Network unavailable")
                    return sampleEvents()
                }
            }
            val viewModel = EventsViewModel(repository)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.loadFailed)
            viewModel.reload()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(2, viewModel.state.value.events.size)
            assertEquals(2, attempts)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun sampleEvents() = listOf(
        event(id = 1, title = "Инди в Астане", category = "Концерт", venue = null),
        event(id = 2, title = "Город будущего", category = "Лекция", venue = "Библиотека"),
    )

    private fun event(id: Int, title: String, category: String?, venue: String?) = Event(
        id = id,
        organizerId = 1,
        title = title,
        startTime = "2026-12-05T19:00:00+05:00",
        endTime = "2026-12-05T22:00:00+05:00",
        status = "published",
        category = category,
        venueName = venue,
        visibility = "public",
    )
}
