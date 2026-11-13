package com.example.biletflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biletflow.core.network.createHttpClient
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.AuthRepositoryImpl
import com.example.biletflow.data.repositories.EventsRepositoryImpl
import com.example.biletflow.data.repositories.OrdersRepositoryImpl
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.auth.AuthViewModel
import com.example.biletflow.presentation.auth.LoginPage
import com.example.biletflow.presentation.eventdetails.EventDetailsPage
import com.example.biletflow.presentation.eventdetails.EventDetailsViewModel
import com.example.biletflow.presentation.events.EventCatalogPage
import com.example.biletflow.presentation.events.EventsViewModel
import com.example.biletflow.presentation.orderreview.OrderReviewPage
import com.example.biletflow.presentation.orderreview.OrderCreationViewModel
import com.example.biletflow.presentation.orderreview.OrderResultPage

@Composable
fun App(useMockEvents: Boolean = false) {
    MaterialTheme {
        var selectedEventId by remember { mutableStateOf<Int?>(null) }
        var showOrderReview by remember { mutableStateOf(false) }
        var showLogin by remember { mutableStateOf(false) }
        var completedOrder by remember { mutableStateOf<Order?>(null) }

        val apiClient = remember(useMockEvents) {
            if (useMockEvents) createMockEventsHttpClient() else createHttpClient()
        }
        val eventsRepository = remember(apiClient) { EventsRepositoryImpl(client = apiClient) }
        val authRepository = remember(apiClient) { AuthRepositoryImpl(client = apiClient) }
        val ordersRepository = remember(apiClient) { OrdersRepositoryImpl(client = apiClient) }
        val sessionStore = remember { SessionStore() }
        val session by sessionStore.tokens.collectAsState()

        val eventsViewModel: EventsViewModel = viewModel {
            EventsViewModel(eventsRepository)
        }
        val eventDetailsViewModel: EventDetailsViewModel = viewModel {
            EventDetailsViewModel(eventsRepository)
        }
        val authViewModel: AuthViewModel = viewModel {
            AuthViewModel(authRepository, sessionStore)
        }
        val orderViewModel: OrderCreationViewModel = viewModel {
            OrderCreationViewModel(ordersRepository, sessionStore)
        }

        when {
            completedOrder != null -> OrderResultPage(
                order = requireNotNull(completedOrder),
                onDone = {
                    completedOrder = null
                    selectedEventId = null
                    showOrderReview = false
                    eventDetailsViewModel.clearSelection()
                    orderViewModel.reset()
                },
            )
            showLogin -> LoginPage(
                viewModel = authViewModel,
                onClose = {
                    authViewModel.closeLoginFlow()
                    showLogin = false
                },
                onLoginSuccess = {
                    authViewModel.closeLoginFlow()
                    orderViewModel.clearError()
                    showLogin = false
                },
            )
            selectedEventId == null -> EventCatalogPage(
                viewModel = eventsViewModel,
                onOpenEvent = { eventId ->
                    orderViewModel.reset()
                    selectedEventId = eventId
                    showOrderReview = false
                },
            )
            showOrderReview -> OrderReviewPage(
                viewModel = eventDetailsViewModel,
                orderViewModel = orderViewModel,
                isAuthenticated = session != null,
                onBack = { showOrderReview = false },
                onLogin = {
                    authViewModel.closeLoginFlow()
                    showLogin = true
                },
                onConflict = {
                    orderViewModel.clearError()
                    showOrderReview = false
                    selectedEventId?.let(eventDetailsViewModel::loadTicketTypes)
                },
                onOrderCreated = { completedOrder = it },
            )
            else -> EventDetailsPage(
                eventId = requireNotNull(selectedEventId),
                viewModel = eventDetailsViewModel,
                onBack = {
                    selectedEventId = null
                    showOrderReview = false
                    orderViewModel.reset()
                },
                onContinue = { showOrderReview = true },
            )
        }
    }
}
