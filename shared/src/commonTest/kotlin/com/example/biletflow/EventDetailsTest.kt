package com.example.biletflow

import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.EventsApiException
import com.example.biletflow.data.repositories.EventsRepositoryImpl
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.repositories.EventsRepository
import com.example.biletflow.presentation.eventdetails.EventDetailsError
import com.example.biletflow.presentation.eventdetails.EventDetailsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailsTest {
    @Test
    fun mockReturnsDifferentDetailsForDifferentIds() = runTest {
        val repository = mockRepository()

        assertEquals("Инди-вечер в Астане", repository.getEvent(101).title)
        assertEquals("Город будущего", repository.getEvent(102).title)
    }

    @Test
    fun unknownMockIdReturns404() = runTest {
        val error = assertFailsWith<EventsApiException> { mockRepository().getEvent(999) }
        assertEquals(404, error.statusCode)
    }

    @Test
    fun changingIdClearsPreviousEventBeforeNewResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = fakeRepository()
            val viewModel = EventDetailsViewModel(repository)
            viewModel.load(1)
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.event?.id)

            viewModel.load(2)
            assertTrue(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.event)
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.event?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedDetailsRequestCanBeRetriedForSameId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val repository = object : EventsRepository {
                override suspend fun getEvents() = emptyList<Event>()
                override suspend fun getTicketTypes(eventId: Int) = emptyList<TicketType>()
                override suspend fun getEvent(eventId: Int): Event {
                    attempts++
                    if (attempts == 1) error("Network unavailable")
                    return event(eventId)
                }
            }
            val viewModel = EventDetailsViewModel(repository)
            viewModel.load(7)
            advanceUntilIdle()
            assertEquals(EventDetailsError.Network, viewModel.state.value.error)

            viewModel.load(7)
            advanceUntilIdle()
            assertEquals(7, viewModel.state.value.event?.id)
            assertEquals(2, attempts)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun mockRepository() = EventsRepositoryImpl(
        client = createMockEventsHttpClient(),
        baseUrl = "http://mock.biletflow",
    )

    private fun fakeRepository() = object : EventsRepository {
        override suspend fun getEvents() = listOf(event(1), event(2))
        override suspend fun getEvent(eventId: Int) = event(eventId)
        override suspend fun getTicketTypes(eventId: Int) = emptyList<TicketType>()
    }

    private fun event(id: Int) = Event(
        id = id,
        organizerId = 1,
        title = "Event $id",
        startTime = "2026-12-05T19:00:00+05:00",
        endTime = "2026-12-05T22:00:00+05:00",
        status = "published",
        visibility = "public",
    )
}
