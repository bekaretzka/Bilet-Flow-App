package com.example.biletflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
import com.example.biletflow.presentation.orderreview.OrderPaymentViewModel
import com.example.biletflow.presentation.orderreview.TicketQrPage
import com.example.biletflow.presentation.orderreview.TicketQrViewModel
import com.example.biletflow.presentation.orderhistory.OrderHistoryPage
import com.example.biletflow.presentation.orderhistory.OrderHistoryViewModel

@Composable
fun App(useMockEvents: Boolean = false) {
    MaterialTheme {
        var selectedEventId by remember { mutableStateOf<Int?>(null) }
        var showOrderReview by remember { mutableStateOf(false) }
        var showLogin by remember { mutableStateOf(false) }
        var completedOrder by remember { mutableStateOf<Order?>(null) }
        var selectedTicketId by remember { mutableStateOf<Int?>(null) }
        var showOrderHistory by remember { mutableStateOf(false) }
        var loginForHistory by remember { mutableStateOf(false) }
        var orderOpenedFromHistory by remember { mutableStateOf(false) }

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
        val eventsState by eventsViewModel.state.collectAsState()
        val eventDetailsViewModel: EventDetailsViewModel = viewModel {
            EventDetailsViewModel(eventsRepository)
        }
        val eventDetailsState by eventDetailsViewModel.state.collectAsState()
        val authViewModel: AuthViewModel = viewModel {
            AuthViewModel(authRepository, sessionStore)
        }
        val orderViewModel: OrderCreationViewModel = viewModel {
            OrderCreationViewModel(ordersRepository, sessionStore)
        }
        val paymentViewModel: OrderPaymentViewModel = viewModel {
            OrderPaymentViewModel(ordersRepository, sessionStore)
        }
        val ticketQrViewModel: TicketQrViewModel = viewModel {
            TicketQrViewModel(ordersRepository, sessionStore)
        }
        val orderHistoryViewModel: OrderHistoryViewModel = viewModel {
            OrderHistoryViewModel(ordersRepository, sessionStore)
        }
        val orderHistoryState by orderHistoryViewModel.state.collectAsState()

        LaunchedEffect(orderHistoryState.openedOrder?.id) {
            orderHistoryState.openedOrder?.let { order ->
                orderOpenedFromHistory = true
                paymentViewModel.startOrder(order)
                completedOrder = order
                orderHistoryViewModel.consumeOpenedOrder()
            }
        }

        when {
            showLogin -> LoginPage(
                viewModel = authViewModel,
                onClose = {
                    authViewModel.closeLoginFlow()
                    showLogin = false
                    if (loginForHistory) {
                        loginForHistory = false
                        showOrderHistory = false
                        orderHistoryViewModel.close()
                    }
                },
                onLoginSuccess = {
                    authViewModel.closeLoginFlow()
                    orderViewModel.clearError()
                    paymentViewModel.clearAfterLogin()
                    ticketQrViewModel.clearUnauthorizedAfterLogin()
                    showLogin = false
                    if (loginForHistory) {
                        loginForHistory = false
                        showOrderHistory = true
                        orderHistoryViewModel.open()
                    }
                },
            )
            selectedTicketId != null -> {
                val ticket = requireNotNull(
                    completedOrder?.tickets?.firstOrNull { it.id == selectedTicketId },
                )
                TicketQrPage(
                    ticket = ticket,
                    typeName = eventDetailsState.ticketTypes
                        .firstOrNull { it.id == ticket.ticketTypeId }?.name,
                    viewModel = ticketQrViewModel,
                    onBack = { selectedTicketId = null },
                    onLogin = {
                        authViewModel.closeLoginFlow()
                        showLogin = true
                    },
                )
            }
            completedOrder != null -> OrderResultPage(
                order = requireNotNull(completedOrder),
                viewModel = paymentViewModel,
                onOrderUpdated = { completedOrder = it },
                onLogin = {
                    authViewModel.closeLoginFlow()
                    showLogin = true
                },
                ticketTypeNames = eventDetailsState.ticketTypes.associate { it.id to it.name },
                onOpenTicket = { selectedTicketId = it },
                backToOrders = orderOpenedFromHistory,
                onDone = {
                    completedOrder = null
                    paymentViewModel.reset()
                    ticketQrViewModel.reset()
                    selectedTicketId = null
                    if (orderOpenedFromHistory) {
                        orderOpenedFromHistory = false
                        showOrderHistory = true
                        orderHistoryViewModel.refreshAfterOrder()
                    } else {
                        selectedEventId = null
                        showOrderReview = false
                        eventDetailsViewModel.clearSelection()
                        orderViewModel.reset()
                    }
                },
            )
            showOrderHistory -> OrderHistoryPage(
                viewModel = orderHistoryViewModel,
                eventNames = eventsState.events.associate { it.id to it.title },
                onBack = {
                    showOrderHistory = false
                    orderHistoryViewModel.close()
                },
                onLogin = {
                    loginForHistory = true
                    authViewModel.closeLoginFlow()
                    showLogin = true
                },
            )
            selectedEventId == null -> EventCatalogPage(
                viewModel = eventsViewModel,
                onOpenEvent = { eventId ->
                    orderViewModel.reset()
                    paymentViewModel.reset()
                    ticketQrViewModel.reset()
                    selectedEventId = eventId
                    showOrderReview = false
                },
                onOpenOrders = {
                    if (session == null) {
                        loginForHistory = true
                        authViewModel.closeLoginFlow()
                        showLogin = true
                    } else {
                        showOrderHistory = true
                        orderHistoryViewModel.open()
                    }
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
                onOrderCreated = {
                    orderOpenedFromHistory = false
                    paymentViewModel.startOrder(it)
                    completedOrder = it
                },
            )
            else -> EventDetailsPage(
                eventId = requireNotNull(selectedEventId),
                viewModel = eventDetailsViewModel,
                onBack = {
                    selectedEventId = null
                    showOrderReview = false
                    orderViewModel.reset()
                    paymentViewModel.reset()
                    ticketQrViewModel.reset()
                },
                onContinue = { showOrderReview = true },
            )
        }
    }
}
