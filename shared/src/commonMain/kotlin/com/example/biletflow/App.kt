package com.example.biletflow
import biletflow.shared.generated.resources.*
import com.example.biletflow.presentation.organizer.*
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.example.biletflow.data.repositories.SessionOrdersRepository
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import org.jetbrains.compose.resources.stringResource
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
fun App(useMockEvents: Boolean = false, sessionStorage: com.example.biletflow.domain.session.SessionStorage? = null,
    registerBack: (((() -> Unit)?) -> Unit)? = null,
) {
    MaterialTheme {
        var selectedEventId by remember { mutableStateOf<Int?>(null) }
        var showOrderReview by remember { mutableStateOf(false) }
        var showLogin by remember { mutableStateOf(false) }
        var completedOrder by remember { mutableStateOf<Order?>(null) }
        var selectedTicketId by remember { mutableStateOf<Int?>(null) }
        var showOrderHistory by remember { mutableStateOf(false) }
        var loginForHistory by remember { mutableStateOf(false) }
        var orderOpenedFromHistory by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableStateOf(0) }
        val catalogScroll = rememberScrollState()

        val apiClient = remember(useMockEvents) {
            if (useMockEvents) createMockEventsHttpClient() else createHttpClient()
        }
        val eventsRepository = remember(apiClient) { EventsRepositoryImpl(client = apiClient) }
        val authRepository = remember(apiClient) { AuthRepositoryImpl(client = apiClient) }
        val sessionScope = rememberCoroutineScope()
        val sessionStore = remember { SessionStore(sessionStorage, sessionScope) }
        val restoring by sessionStore.restoring.collectAsState()
        val storageError by sessionStore.storageError.collectAsState()
        LaunchedEffect(sessionStore) { sessionStore.restore() }
        if (restoring) {
            androidx.compose.material3.Text(org.jetbrains.compose.resources.stringResource(biletflow.shared.generated.resources.Res.string.session_restoring))
            return@MaterialTheme
        }
        if (storageError) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                text = { androidx.compose.material3.Text(org.jetbrains.compose.resources.stringResource(biletflow.shared.generated.resources.Res.string.session_storage_error)) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { sessionStore.dismissStorageError() }) {
                    androidx.compose.material3.Text("OK")
                } },
            )
        }
        val ordersRepository = remember(apiClient) {
            SessionOrdersRepository(OrdersRepositoryImpl(client = apiClient), authRepository, sessionStore, sessionScope)
        }
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
        val organizerVm: OrganizerViewModel = viewModel { OrganizerViewModel(apiClient, ordersRepository, sessionStore) }
        val organizerState by organizerVm.state.collectAsState()
        val myEventsVm: MyEventsViewModel = viewModel { MyEventsViewModel(apiClient, ordersRepository, sessionStore) }
        val myEventsState by myEventsVm.state.collectAsState()
        val paidSalesVm: PaidSalesViewModel = viewModel { PaidSalesViewModel(apiClient,ordersRepository,sessionStore) }
        val paidSalesState by paidSalesVm.state.collectAsState()
        val attendeesVm: AttendeesViewModel = viewModel { AttendeesViewModel(apiClient, ordersRepository, sessionStore) }
        val attendeesState by attendeesVm.state.collectAsState()
        val staffVm: EventStaffViewModel = viewModel { EventStaffViewModel(apiClient, ordersRepository, sessionStore) }
        val staffState by staffVm.state.collectAsState()
        val salesVm: EventSalesViewModel = viewModel { EventSalesViewModel(apiClient, ordersRepository, sessionStore) }
        val salesState by salesVm.state.collectAsState()
        LaunchedEffect(salesState.denied) {
            if (salesState.denied) { salesVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        val staffEventsVm: StaffEventsViewModel = viewModel { StaffEventsViewModel(apiClient, ordersRepository, sessionStore) }
        val staffEventsState by staffEventsVm.state.collectAsState()
        val checkInVm: CheckInOverviewViewModel = viewModel { CheckInOverviewViewModel(apiClient, ordersRepository, sessionStore) }
        val checkInState by checkInVm.state.collectAsState()
        LaunchedEffect(checkInState.denied) {
            if (checkInState.denied) { checkInVm.close(); staffEventsVm.accessLost() }
        }
        LaunchedEffect(staffState.denied) {
            if (staffState.denied) { staffVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        LaunchedEffect(attendeesState.denied) {
            if (attendeesState.denied) { attendeesVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        LaunchedEffect(paidSalesState.denied) {
            if(paidSalesState.denied) { paidSalesVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        LaunchedEffect(selectedTab, selectedEventId, myEventsState.open, myEventsState.catalogDirty) {
            if (selectedTab == 0 && selectedEventId == null && !myEventsState.open && myEventsState.catalogDirty) {
                eventsViewModel.reload(); myEventsVm.catalogRefreshed()
            }
        }
        val createEventVm: CreateEventViewModel = viewModel { CreateEventViewModel(apiClient, ordersRepository, sessionStore) }
        val createEventState by createEventVm.state.collectAsState()
        val ticketCreateVm: CreateTicketTypeViewModel = viewModel { CreateTicketTypeViewModel(apiClient, ordersRepository, sessionStore) }
        val ticketCreateState by ticketCreateVm.state.collectAsState()
        LaunchedEffect(createEventState.created?.id, createEventState.denied) {
            if (createEventState.denied) { createEventVm.reset(); myEventsVm.close(); organizerVm.load() }
            else if (createEventState.created != null) {
                if (createEventState.copyingFromId != null) {
                    myEventsVm.openCreated(requireNotNull(createEventState.created))
                    createEventVm.reset()
                } else if (createEventState.editingId != null) {
                    myEventsVm.updated(requireNotNull(createEventState.created))
                    createEventVm.reset()
                } else { createEventVm.reset(); myEventsVm.filter(null); myEventsVm.reload() }
            }
        }
        LaunchedEffect(myEventsState.accessDenied) {
            if (myEventsState.accessDenied) { myEventsVm.close(); organizerVm.load() }
        }
        LaunchedEffect(selectedTab, session?.email, session != null) {
            if (selectedTab == 2 && session != null) organizerVm.load()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (checkInVm.state.value.event != null && sessionStore.tokens.value != null) checkInVm.reloadStats()
            if (staffEventsVm.state.value.open && sessionStore.tokens.value != null) staffEventsVm.reload()
            if (selectedTab == 2 && sessionStore.tokens.value != null) organizerVm.load()
        }
        var previousAccount by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(session?.email) {
            val account = session?.email
            if (account != null) {
                if (previousAccount != null && previousAccount != account) {
                    completedOrder = null
                    selectedTicketId = null
                    selectedEventId = null
                    showOrderReview = false
                    orderOpenedFromHistory = false
                    orderViewModel.reset()
                    paymentViewModel.reset()
                    ticketQrViewModel.reset()
                    eventDetailsViewModel.clearSelection()
                    orderHistoryViewModel.close()
                    if (selectedTab == 1) orderHistoryViewModel.open()
                }
                previousAccount = account
            }
        }

        val logout = {
            authViewModel.logout()
            orderHistoryViewModel.close()
            orderViewModel.reset()
            paymentViewModel.reset()
            ticketQrViewModel.reset()
            eventDetailsViewModel.clearSelection()
            completedOrder = null
            selectedTicketId = null
            selectedEventId = null
            showOrderHistory = false
            showOrderReview = false
            showLogin = false
            loginForHistory = false
            orderOpenedFromHistory = false
            selectedTab = 0
        }
        val isRoot = !staffEventsState.open && !myEventsState.open && !organizerState.form && !showLogin && selectedTicketId == null && completedOrder == null && selectedEventId == null
        val backAction: (() -> Unit)? = when {
            salesState.eventId != null && !showLogin -> ({ salesVm.close() })
            checkInState.scannerOpen && !showLogin -> ({ checkInVm.closeScanner() })
            checkInState.event != null && !showLogin -> ({ checkInVm.close() })
            staffEventsState.open && !showLogin -> ({ staffEventsVm.close() })
            staffState.eventId != null && !showLogin -> ({ staffVm.close() })
            attendeesState.eventId != null && !showLogin -> ({ attendeesVm.close() })
            paidSalesState.eventId != null && !showLogin -> ({ paidSalesVm.back() })
            ticketCreateState.open && !showLogin -> ({ ticketCreateVm.close() })
            createEventState.open && !showLogin -> ({ createEventVm.close() })
            myEventsState.selectedId != null && !showLogin -> ({ myEventsVm.backToList() })
            myEventsState.open && !showLogin -> ({ myEventsVm.close() })
            organizerState.form && !showLogin -> ({ organizerVm.closeForm() })
            showLogin -> ({
                if (authViewModel.page.value == com.example.biletflow.presentation.auth.AuthPage.Registration) {
                    authViewModel.openLogin()
                } else if (!authViewModel.state.value.isLoading) {
                    authViewModel.closeLoginFlow(); showLogin = false; loginForHistory = false
                }
            })
            selectedTicketId != null -> ({ selectedTicketId = null })
            completedOrder != null -> ({
                completedOrder = null; paymentViewModel.reset(); ticketQrViewModel.reset()
                if (orderOpenedFromHistory) {
                    selectedTab = 1; showOrderHistory = true; orderHistoryViewModel.refreshAfterOrder()
                } else {
                    selectedEventId = null; showOrderReview = false; selectedTab = 0
                    eventDetailsViewModel.clearSelection(); orderViewModel.reset()
                }
            })
            showOrderReview -> ({ showOrderReview = false })
            selectedEventId != null -> ({ selectedEventId = null })
            selectedTab != 0 -> ({ selectedTab = 0; showOrderHistory = false; orderHistoryViewModel.close() })
            else -> null
        }
        DisposableEffect(backAction) {
            registerBack?.invoke(backAction)
            onDispose { registerBack?.invoke(null) }
        }

        LaunchedEffect(orderHistoryState.openedOrder?.id) {
            orderHistoryState.openedOrder?.let { order ->
                orderOpenedFromHistory = true
                paymentViewModel.startOrder(order)
                completedOrder = order
                orderHistoryViewModel.consumeOpenedOrder()
            }
        }

        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            salesState.eventId != null && !showLogin -> EventSalesPage(salesVm)
            checkInState.scannerOpen && !showLogin -> QrScannerPage(checkInVm)
            checkInState.event != null && !showLogin -> CheckInOverviewPage(checkInVm, checkInVm::close)
            staffEventsState.open && !showLogin -> StaffEventsPage(staffEventsVm, checkInVm::open)
            staffState.eventId != null && !showLogin -> EventStaffPage(staffVm)
            attendeesState.eventId != null && !showLogin -> AttendeesPage(attendeesVm)
            paidSalesState.eventId != null && !showLogin -> PaidSalesPage(paidSalesVm)
            ticketCreateState.open && !showLogin -> CreateTicketTypePage(ticketCreateVm)
            createEventState.open && !showLogin -> CreateEventPage(createEventVm) {
                createEventVm.reset(); myEventsVm.backToList()
            }
            myEventsState.selectedId != null && !showLogin -> EventManagementPage(myEventsVm, createEventVm::edit, ticketCreateVm,
                onPaidSales = { paidSalesVm.open(it.id, it.title) }, onAttendees = { attendeesVm.open(it.id, it.title) },
                onStaff = { staffVm.open(it.id, it.title) }, onSales = { salesVm.open(it.id, it.title) }, onCopy = createEventVm::copySettings)
            myEventsState.open && !showLogin -> MyEventsPage(myEventsVm, createEventVm::open)
            organizerState.form && !showLogin -> OrganizerApplicationPage(organizerVm) { showLogin = true }
            showLogin -> LoginPage(
                viewModel = authViewModel,
                onClose = {
                    authViewModel.closeLoginFlow()
                    showLogin = false
                    if (loginForHistory) {
                        loginForHistory = false
                        showOrderHistory = selectedTab == 1
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
            isRoot && selectedTab == 2 -> Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(Res.string.nav_profile), style = MaterialTheme.typography.headlineLarge)
                Text(session?.email ?: stringResource(
                    if (session == null) Res.string.profile_guest else Res.string.profile_signed_in,
                ))
                if (session == null) Button(onClick = {
                    loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true
                }) { Text(stringResource(Res.string.auth_login_button)) }
                else Button(onClick = logout) { Text(stringResource(Res.string.auth_logout)) }
                if (session != null) OrganizerContent(organizerVm) { showLogin = true }
                if (session != null) Button(onClick = staffEventsVm::open) { Text(stringResource(Res.string.staff_events_title)) }
                if (session != null && organizerState.profile?.status == "approved") {
                    Button(onClick = myEventsVm::open) { Text(stringResource(Res.string.my_events_title)) }
                }
            }
            isRoot && selectedTab == 1 && session == null -> Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(Res.string.order_history_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(Res.string.order_history_session_expired))
                Button(onClick = { loginForHistory = true; showLogin = true }) {
                    Text(stringResource(Res.string.auth_login_button))
                }
            }
            showOrderHistory -> OrderHistoryPage(
                isAuthenticated = session != null,
                onLogout = logout,
                viewModel = orderHistoryViewModel,
                eventNames = eventsState.events.associate { it.id to it.title },
                onBack = {
                    selectedTab = 0
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
                scrollState = catalogScroll,
                viewModel = eventsViewModel,
                onOpenEvent = { eventId ->
                    orderViewModel.reset()
                    paymentViewModel.reset()
                    ticketQrViewModel.reset()
                    selectedEventId = eventId
                    showOrderReview = false
                },
                onOpenOrders = {
                    selectedTab = 1
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
        if (isRoot) NavigationBar {
            val labels = listOf(stringResource(Res.string.nav_events), stringResource(Res.string.order_history_title), stringResource(Res.string.nav_profile))
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = {
                        if (selectedTab != index) {
                            selectedTab = index
                            showOrderHistory = index == 1
                            if (index == 1 && session != null) orderHistoryViewModel.open()
                            else orderHistoryViewModel.close()
                            if (index == 1 && session == null) {
                                loginForHistory = true
                                authViewModel.closeLoginFlow()
                                showLogin = true
                            }
                        }
                    },
                    icon = { Text(listOf("▦", "▤", "○")[index], modifier = Modifier) },
                    label = { Text(label) },
                )
            }
        }
        }
    }
}
