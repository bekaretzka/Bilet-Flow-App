package com.example.biletflow
import biletflow.shared.generated.resources.*
import com.example.biletflow.presentation.organizer.*
import com.example.biletflow.presentation.support.*
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
    /** Website host whose event pages (`/events/{id}`, Campaign QR `?campaign=`) open the app; blank ignores all links. */
    webHost: String = "",
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
            if (useMockEvents) createMockEventsHttpClient(visitorCheckoutReady = true, catalogPageFixtures = true) else createHttpClient()
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
        val currentUserVm: com.example.biletflow.presentation.auth.CurrentUserViewModel = viewModel {
            com.example.biletflow.presentation.auth.CurrentUserViewModel(apiClient,ordersRepository,sessionStore)
        }
        val currentUserState by currentUserVm.state.collectAsState()
        val currentUser by sessionStore.user.collectAsState()
        val suspended by sessionStore.suspended.collectAsState()
        val supportVm: SupportViewModel = viewModel { SupportViewModel(apiClient, ordersRepository, sessionStore) }
        val supportState by supportVm.state.collectAsState()
        val supportHistoryVm: SupportCasesViewModel = viewModel { SupportCasesViewModel(apiClient, ordersRepository, sessionStore) }
        val supportHistoryState by supportHistoryVm.state.collectAsState()

        val eventsViewModel: EventsViewModel = viewModel {
            EventsViewModel(eventsRepository)
        }
        val eventsState by eventsViewModel.state.collectAsState()
        LaunchedEffect(eventsState.filterRevision) { if(eventsState.filterRevision > 0) catalogScroll.scrollTo(0) }
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
        val organizerVm: OrganizerViewModel = viewModel { OrganizerViewModel(apiClient, ordersRepository, sessionStore, onApplied = currentUserVm::refreshAfterChange) }
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
        val organizerOrdersVm: OrganizerOrdersViewModel = viewModel { OrganizerOrdersViewModel(apiClient, ordersRepository, sessionStore) }
        val organizerOrdersState by organizerOrdersVm.state.collectAsState()
        val campaignsVm: CampaignsViewModel = viewModel { CampaignsViewModel(apiClient,ordersRepository,sessionStore) }
        val campaignsState by campaignsVm.state.collectAsState()
        LaunchedEffect(campaignsState.denied) {
            if(campaignsState.denied) { campaignsVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        LaunchedEffect(organizerOrdersState.denied) {
            if (organizerOrdersState.denied) { organizerOrdersVm.close(); myEventsVm.close(); organizerVm.load() }
        }
        val adminVm: com.example.biletflow.presentation.admin.AdminViewModel = viewModel { com.example.biletflow.presentation.admin.AdminViewModel(apiClient, ordersRepository, sessionStore) }
        val adminState by adminVm.state.collectAsState()
        // 403 PlatformAdminRequiredError: rights changed on the server; re-read /auth/me (hides the entry) and leave the admin screens.
        LaunchedEffect(adminState.denied) { if (adminState.denied) { adminVm.close(); currentUserVm.load() } }
        LaunchedEffect(currentUser?.isPlatformAdmin) { if (currentUser?.isPlatformAdmin == false && adminVm.state.value.open) adminVm.close() }
        val insightsVm: EventInsightsViewModel = viewModel { EventInsightsViewModel(apiClient, ordersRepository, sessionStore) }
        val insightsState by insightsVm.state.collectAsState()
        LaunchedEffect(insightsState.denied) {
            if (insightsState.denied) { insightsVm.close(); myEventsVm.close(); organizerVm.load() }
        }
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
        LaunchedEffect(ticketCreateState.denied) {
            if (ticketCreateState.denied) { ticketCreateVm.close(); myEventsVm.close(); organizerVm.load() }
        }
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
            if (selectedTab == 2 && session != null) currentUserVm.load()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (checkInVm.state.value.event != null && sessionStore.tokens.value != null) checkInVm.reloadStats()
            if (staffEventsVm.state.value.open && sessionStore.tokens.value != null) staffEventsVm.reload()
            if (sessionStore.tokens.value != null) currentUserVm.load()
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
            campaignsVm.close(); adminVm.close()
            supportHistoryVm.close()
            supportVm.close()
            previousAccount = null
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
        LaunchedEffect(suspended) {
            if(suspended) {
                campaignsVm.close(); supportHistoryVm.close(); supportVm.close(); adminVm.close()
                orderHistoryViewModel.close(); orderViewModel.reset(); paymentViewModel.reset(); ticketQrViewModel.reset()
                eventDetailsViewModel.clearSelection(); organizerOrdersVm.close(); salesVm.close(); insightsVm.close(); attendeesVm.close()
                staffVm.close(); staffEventsVm.close(); checkInVm.close(); paidSalesVm.close(); myEventsVm.close()
                createEventVm.reset(); ticketCreateVm.close(); organizerVm.closeForm(); authViewModel.resetForSessionEnd()
                completedOrder=null; selectedTicketId=null; selectedEventId=null; showOrderReview=false
                showOrderHistory=false; showLogin=false; loginForHistory=false; orderOpenedFromHistory=false
                previousAccount=null; selectedTab=0
            }
        }
        // Incoming event links (core/links/IncomingLinks): this point is reached only after the session is restored.
        // Each delivered link is taken once; GET /campaign-links/{token} counts an open, so a taken link is never retried.
        var campaignLinkError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            com.example.biletflow.core.links.IncomingLinks.pending.collect { pending ->
                if (pending == null) return@collect
                val link = com.example.biletflow.core.links.IncomingLinks.take() ?: return@collect
                val parsed = com.example.biletflow.core.links.parseEventLink(link, webHost) ?: return@collect
                val openEvent = { eventId: Int, promoCode: String? ->
                    orderViewModel.reset(); paymentViewModel.reset(); ticketQrViewModel.reset()
                    completedOrder = null; selectedTicketId = null; showOrderReview = false; orderOpenedFromHistory = false
                    selectedTab = 0
                    selectedEventId = eventId
                    // stays in the order form across a login: signing in from no account does not reset orderViewModel
                    promoCode?.let(orderViewModel::prefillPromoCode)
                }
                val token = parsed.campaignToken
                if (token == null) { openEvent(parsed.eventId, null); return@collect }
                try {
                    val resolved = com.example.biletflow.data.repositories.resolveCampaignLink(apiClient, com.example.biletflow.core.network.apiBaseUrl, token)
                    openEvent(resolved.eventId, resolved.promoCode)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    campaignLinkError = (error as? com.example.biletflow.data.repositories.CampaignApiException)?.code ?: "network"
                }
            }
        }
        campaignLinkError?.let { code -> AlertDialog(onDismissRequest = { campaignLinkError = null },
            text = { Text(stringResource(if (code == "CampaignLinkNotFoundError") Res.string.campaign_link_invalid else Res.string.campaign_link_failed)) },
            confirmButton = { TextButton(onClick = { campaignLinkError = null }) { Text("OK") } }) }
        if(suspended) AlertDialog(onDismissRequest=sessionStore::dismissSuspended,
            text={ Text(stringResource(Res.string.session_suspended)) },
            confirmButton={ TextButton(onClick=sessionStore::dismissSuspended) { Text("OK") } })
        val isRoot = !adminState.open && campaignsState.eventId == null && !supportHistoryState.open && !supportState.open && !staffEventsState.open && !myEventsState.open && !organizerState.form && !showLogin && selectedTicketId == null && completedOrder == null && selectedEventId == null
        val backAction: (() -> Unit)? = when {
            campaignsState.eventId != null && !showLogin -> ({ campaignsVm.back() })
            supportHistoryState.open && !showLogin -> ({ supportHistoryVm.back() })
            supportState.open && !showLogin -> ({ if (!supportState.sending) supportVm.close() })
            organizerOrdersState.eventId != null && !showLogin -> ({ organizerOrdersVm.back() })
            salesState.eventId != null && !showLogin -> ({ salesVm.close() })
            insightsState.eventId != null && !showLogin -> ({ insightsVm.close() })
            checkInState.scannerOpen && !showLogin -> ({ checkInVm.closeScanner() })
            checkInState.event != null && !showLogin -> ({ checkInVm.close() })
            staffEventsState.open && !showLogin -> ({ staffEventsVm.close() })
            staffState.eventId != null && !showLogin -> ({ staffVm.close() })
            attendeesState.eventId != null && !showLogin -> ({ attendeesVm.close() })
            paidSalesState.eventId != null && !showLogin -> ({ paidSalesVm.back() })
            adminState.open && !showLogin -> ({ adminVm.back() })
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

        val documents = remember(apiClient) { com.example.biletflow.presentation.documents.DocumentDownloader(apiClient, ordersRepository, sessionStore, com.example.biletflow.core.network.apiBaseUrl) }
        androidx.compose.runtime.CompositionLocalProvider(com.example.biletflow.presentation.documents.LocalDocuments provides documents) {
        Column(Modifier.fillMaxSize()) {
        if(session != null && currentUserState.error != null) {
            Text(stringResource(Res.string.current_user_error))
            Text(currentUserState.error.orEmpty())
            TextButton(onClick=currentUserVm::load) { Text(stringResource(Res.string.events_retry)) }
        }
        if(session != null && currentUser?.emailVerified == false && !showLogin) Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
            Text(stringResource(Res.string.email_unverified_banner))
            TextButton(onClick=currentUserVm::resendVerification, enabled=!currentUserState.resending) { Text(stringResource(Res.string.email_resend)) }
            if(currentUserState.resendSent) Text(stringResource(Res.string.email_resend_sent))
            currentUserState.resendError?.let { Text("${stringResource(Res.string.email_resend_failed)} ($it)") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            campaignsState.eventId != null && !showLogin -> CampaignsPage(campaignsVm)
            supportHistoryState.open && !showLogin -> SupportCasesPage(supportHistoryVm, session != null, eventsRepository) {
                loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true
            }
            supportState.open && !showLogin -> SupportPage(supportVm, session != null, onOpenRequest = { supportHistoryVm.select(it, fromCreation = true) }, onCheckCases = { supportHistoryVm.open() }) {
                loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true
            }
            organizerOrdersState.eventId != null && !showLogin -> OrganizerOrdersPage(organizerOrdersVm)
            salesState.eventId != null && !showLogin -> EventSalesPage(salesVm)
            insightsState.eventId != null && !showLogin -> EventInsightsPage(insightsVm)
            checkInState.scannerOpen && !showLogin -> QrScannerPage(checkInVm)
            checkInState.event != null && !showLogin -> CheckInOverviewPage(checkInVm, checkInVm::close)
            staffEventsState.open && !showLogin -> StaffEventsPage(staffEventsVm, checkInVm::open)
            staffState.eventId != null && !showLogin -> EventStaffPage(staffVm)
            attendeesState.eventId != null && !showLogin -> AttendeesPage(attendeesVm)
            paidSalesState.eventId != null && !showLogin -> PaidSalesPage(paidSalesVm)
            adminState.open && !showLogin -> com.example.biletflow.presentation.admin.AdminPage(adminVm)
            ticketCreateState.open && !showLogin -> CreateTicketTypePage(ticketCreateVm)
            createEventState.open && !showLogin -> CreateEventPage(createEventVm) {
                createEventVm.reset(); myEventsVm.backToList()
            }
            myEventsState.selectedId != null && !showLogin -> EventManagementPage(myEventsVm, createEventVm::edit, ticketCreateVm,
                onPaidSales = { paidSalesVm.open(it.id, it.title) }, onAttendees = { attendeesVm.open(it.id, it.title) },
                onStaff = { staffVm.open(it.id, it.title) }, onSales = { salesVm.open(it.id, it.title) }, onCopy = createEventVm::copySettings,
                onOrders = { organizerOrdersVm.open(it.id, it.title) }, onSupport = { supportHistoryVm.openEvent(it.id,it.title) }, onCampaigns = { campaignsVm.open(it.id,it.title) },
                onAnalytics = { insightsVm.openAnalytics(it.id, it.title) }, onHistory = { insightsVm.openHistory(it.id, it.title) },
                onPlatformSupport = { supportVm.openPlatform(it.id) })
            myEventsState.open && !showLogin -> MyEventsPage(myEventsVm, createEventVm::open, onPlatformSupport = { supportVm.openPlatform() })
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
                    eventsRepository = eventsRepository,
                    typeName = eventDetailsState.ticketTypes
                        .firstOrNull { it.id == ticket.ticketTypeId }?.name,
                    viewModel = ticketQrViewModel,
                    onBack = { selectedTicketId = null },
                    onSupport = { supportVm.open(ticket.eventId, ticketId = ticket.id) },
                    onLogin = {
                        authViewModel.closeLoginFlow()
                        showLogin = true
                    },
                )
            }
            completedOrder != null -> OrderResultPage(
                order = requireNotNull(completedOrder),
                eventsRepository = eventsRepository,
                viewModel = paymentViewModel,
                onOrderUpdated = { completedOrder = it },
                onLogin = {
                    authViewModel.closeLoginFlow()
                    showLogin = true
                },
                ticketTypeNames = eventDetailsState.ticketTypes.associate { it.id to it.name },
                onOpenTicket = { selectedTicketId = it },
                onSupport = {
                    completedOrder?.let { supportVm.open(it.eventId, it.id) }
                    if (session == null) { loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true }
                },
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
                Text(currentUser?.email ?: stringResource(
                    if (session == null) Res.string.profile_guest else Res.string.profile_signed_in,
                ))
                TextButton(onClick = supportHistoryVm::open) { Text(stringResource(Res.string.support_history)) }
                if (session == null) Button(onClick = {
                    loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true
                }) { Text(stringResource(Res.string.auth_login_button)) }
                else Button(onClick = logout) { Text(stringResource(Res.string.auth_logout)) }
                if(session != null) {
                    if(currentUserState.loading) CircularProgressIndicator()
                    currentUser?.let { user ->
                        Text("ID: ${user.id}")
                        Text(stringResource(if(user.emailVerified) Res.string.profile_email_verified else Res.string.profile_email_unverified))
                        if(user.isPlatformAdmin) { Text(stringResource(Res.string.profile_admin)); Button(onClick=adminVm::open) { Text(stringResource(Res.string.admin_title)) } }
                        when(user.organizer?.status) {
                            "pending" -> Text(stringResource(Res.string.organizer_pending))
                            "approved" -> Text(stringResource(Res.string.organizer_approved))
                            null -> Button(onClick=organizerVm::openForm) { Text(stringResource(Res.string.organizer_apply)) }
                        }
                        TextButton(onClick=currentUserVm::load) { Text(stringResource(Res.string.organizer_refresh)) }
                    }
                }
                if (session != null && currentUser?.canScan == true) Button(onClick = staffEventsVm::open) { Text(stringResource(Res.string.staff_events_title)) }
                if (session != null && currentUser?.organizer?.status == "approved") {
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
                eventsRepository = eventsRepository,
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
                onSupport = {
                    selectedEventId?.let { supportVm.open(it) }
                    if (session == null) { loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true }
                },
                onLogin = { loginForHistory = false; authViewModel.closeLoginFlow(); showLogin = true },
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
}

@Composable
fun NetworkConfigurationError(message: String) { MaterialTheme { Text(message, modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) } }
