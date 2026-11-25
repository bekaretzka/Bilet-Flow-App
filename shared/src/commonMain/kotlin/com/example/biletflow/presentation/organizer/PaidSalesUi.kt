package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class PaidSalesStatus(
    @SerialName("event_id") val eventId: Int, val status: String,
    @SerialName("fee_amount") val feeAmount: Long, val currency: String,
    @SerialName("is_simulated") val isSimulated: Boolean,
    val checklist: Map<String, Boolean>, @SerialName("can_activate") val canActivate: Boolean,
    @SerialName("activated_at") val activatedAt: String? = null,
    @SerialName("suspended_at") val suspendedAt: String? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
)
@Serializable
data class ActivationFeePayment(
    val id: Int, val purpose: String, val amount: Long, val currency: String, val status: String,
    val provider: String,
    @SerialName("provider_reference") val providerReference: String,
    @SerialName("is_simulated") val isSimulated: Boolean,
    @SerialName("created_at") val createdAt: String,
)

internal fun PaidSalesStatus.allows(action: String): Boolean {
    if (status !in listOf("not_started", "pending")) return false
    return when (action) {
        "accept-terms" -> checklist["terms_accepted"] == false
        "pay-fee" -> checklist["fee_paid"] == false
        "activate" -> canActivate
        else -> false
    }
}

// Readiness describes a future activation, never an already active or suspended sale.
internal fun PaidSalesStatus.activationReadiness(): Boolean? =
    canActivate.takeIf { status in listOf("not_started", "pending") }
data class PaidSalesState(val eventId: Int? = null,val title: String = "",val loading: Boolean = false,
    val data: PaidSalesStatus? = null,val error: Boolean = false,val missing: Boolean = false,val denied: Boolean = false,
    val requirement: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val fieldErrors: Map<String, String> = emptyMap(),
    val submitting: Boolean = false,
    val unknownResult: Boolean = false,
    val formError: Boolean = false,
    val observedProfile: OrganizerProfile? = null,
    val confirmation: String? = null,
    val activationBusy: Boolean = false,
    val activationUnknown: Boolean = false,
    val activationError: String? = null,
)

class PaidSalesViewModel(private val client: HttpClient,private val requests: SessionOrdersRepository,
    private val session: SessionStore,private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(PaidSalesState())
    val state = mutable.asStateFlow()
    private var sequence = 0
    private var generation = session.generation
    init { viewModelScope.launch { session.tokens.collect {
        if(generation != session.generation) { generation = session.generation; close() }
    } } }
    fun open(id: Int,title: String) { sequence++; unresolvedRequirement = null; mutable.value = PaidSalesState(eventId = id,title = title); reload() }
    fun close() { sequence++; unresolvedRequirement = null; mutable.value = PaidSalesState() }
    fun back() {
        if (mutable.value.submitting || mutable.value.activationBusy) return
        if (mutable.value.confirmation != null) { dismissAction(); return }
        if (mutable.value.requirement == null) close()
        else {
            // Keep an unresolved operation and its input until checked, even if the form is reopened.
            mutable.value = mutable.value.copy(requirement = null)
            reload()
        }
    }
    private var unresolvedRequirement: String? = null
    fun openRequirement(key: String) {
        if (mutable.value.loading || mutable.value.submitting || mutable.value.activationBusy || mutable.value.activationUnknown || mutable.value.eventId == null) return
        if (key !in listOf("identity_verified", "payout_account_connected")) return
        mutable.value = mutable.value.copy(requirement = unresolvedRequirement ?: key, formError = false)
    }
    fun askAction(action: String) {
        val current = mutable.value
        if (current.loading || current.submitting || current.activationBusy || current.activationUnknown || current.unknownResult || current.requirement != null) return
        if (current.data?.allows(action) == true) mutable.value = current.copy(confirmation = action)
    }
    fun dismissAction() { mutable.value = mutable.value.copy(confirmation = null) }
    fun confirmAction() {
        val current = mutable.value
        val action = current.confirmation ?: return
        val id = current.eventId ?: return
        if (current.activationBusy || current.activationUnknown || current.loading || current.submitting || current.data?.allows(action) != true) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(confirmation = null, activationBusy = true, activationError = null)
        viewModelScope.launch {
            try {
                requests.request { token ->
                    val response = client.post("$baseUrl/events/$id/paid-sales/$action") {
                        bearerAuth(token)
                        if (action == "pay-fee") {
                            contentType(ContentType.Application.Json)
                            setBody(JsonObject(emptyMap()))
                        }
                    }
                    if (response.status.value != 200) {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, body?.get("error")?.jsonPrimitive?.contentOrNull, "Paid sales action failed")
                    }
                    if (action == "pay-fee") response.body<ActivationFeePayment>()
                    else require(response.body<PaidSalesStatus>().eventId == id)
                }
                if (expected != session.generation || requestId != sequence) return@launch
                mutable.value = mutable.value.copy(activationBusy = false)
                reload()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                val unknown = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable"
                mutable.value = mutable.value.copy(activationBusy = false, activationUnknown = unknown,
                    activationError = api?.errorCode ?: "unknown",
                    denied = api?.statusCode == 403 || api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError"))
                if (api?.statusCode == 409 || api?.statusCode == 402) reload()
            }
        }
    }
    fun updateField(key: String, value: String) {
        if (mutable.value.submitting || mutable.value.unknownResult) return
        mutable.value = mutable.value.copy(fields = mutable.value.fields + (key to value),
            fieldErrors = mutable.value.fieldErrors - key, observedProfile = null)
    }
    fun submitRequirement() {
        val current = mutable.value
        val requirement = current.requirement ?: return
        if (current.submitting || current.unknownResult) return
        val errors = validateOrganizerRequirement(requirement, current.fields)
        mutable.value = current.copy(fieldErrors = errors)
        if (errors.isNotEmpty()) return
        runRequirement(checkOnly = false)
    }
    fun checkRequirement() {
        if (!mutable.value.submitting) runRequirement(checkOnly = true)
    }
    private fun runRequirement(checkOnly: Boolean) {
        val current = mutable.value
        val requirement = current.requirement ?: return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(submitting = true, formError = false, observedProfile = null)
        viewModelScope.launch {
            var serverFields = emptyMap<String, String>()
            try {
                val profile = requests.request { token ->
                    val response = if (checkOnly) client.get("$baseUrl/organizers/me") { bearerAuth(token) }
                    else {
                        val keys = if (requirement == "identity_verified") listOf("legal_name", "contact_phone")
                            else listOf("holder_name", "bank_name", "iban")
                        val payload = buildJsonObject { keys.forEach { put(it, current.fields[it].orEmpty().trim()) } }
                        if (requirement == "identity_verified") client.post("$baseUrl/organizers/me/verification") {
                            bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
                        } else client.put("$baseUrl/organizers/me/payout-account") {
                            bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
                        }
                    }
                    if (response.status.value == 200) response.body<OrganizerProfile>() else {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        serverFields = organizerFieldErrors(body)
                        throw OrdersApiException(response.status.value, body?.get("error")?.jsonPrimitive?.contentOrNull,
                            "Organizer requirement request failed")
                    }
                }
                if (expected != session.generation || requestId != sequence) return@launch
                unresolvedRequirement = null
                if (checkOnly) {
                    mutable.value = mutable.value.copy(submitting = false, unknownResult = false, observedProfile = profile)
                } else {
                    mutable.value = mutable.value.copy(requirement = null, fields = emptyMap(), fieldErrors = emptyMap(),
                        submitting = false, unknownResult = false)
                    reload()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                val uncertain = !checkOnly && (api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable")
                if (uncertain) unresolvedRequirement = requirement
                mutable.value = mutable.value.copy(submitting = false, formError = true,
                    unknownResult = current.unknownResult || uncertain,
                    fieldErrors = serverFields,
                    denied = api?.statusCode == 403 || api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError"))
            }
        }
    }
    fun reload() {
        val current = mutable.value
        val id = current.eventId ?: return
        if(current.loading || current.activationBusy || current.submitting) return
        val requestId = ++sequence
        val expected = session.generation
        mutable.value = current.copy(loading = true, data = null, error = false, missing = false, denied = false, confirmation = null)
        viewModelScope.launch {
            try {
                val data = requests.request { token ->
                    val response = client.get("$baseUrl/events/$id/paid-sales") { bearerAuth(token) }
                    if(response.status.value == 200) response.body<PaidSalesStatus>() else {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value,body?.get("error")?.jsonPrimitive?.content,"Paid sales unavailable")
                    }
                }
                require(data.eventId == id)
                if(expected == session.generation && requestId == sequence) mutable.value = mutable.value.copy(
                    loading = false, data = data, activationUnknown = false,
                    activationError = if (current.activationUnknown) null else current.activationError)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                val missing = api?.statusCode == 404 && api.errorCode == "PaidSalesNotFoundError"
                mutable.value = mutable.value.copy(loading = false,missing = missing,error = !missing,
                    denied = api?.statusCode == 403 || api?.errorCode in listOf("EventAccessDeniedError","OrganizerNotFoundError","OrganizerNotApprovedError"))
            }
        }
    }
}

@Composable
fun PaidSalesPage(vm: PaidSalesViewModel) {
    val state by vm.state.collectAsState()
    if (state.requirement != null) {
        OrganizerRequirementPage(vm)
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = vm::back, enabled = !state.activationBusy) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(stringResource(Res.string.paid_sales_title),style = MaterialTheme.typography.headlineLarge)
        Text(state.title)
        TextButton(onClick = vm::reload,enabled = !state.loading && !state.activationBusy) { Text(stringResource(if (state.activationUnknown) Res.string.payment_check_status else Res.string.my_events_refresh)) }
        if (state.activationBusy) CircularProgressIndicator()
        if (state.activationUnknown) Text(stringResource(Res.string.paid_action_unknown))
        else state.activationError?.let { code ->
            Text(stringResource(when (code) {
                "PaymentFailedError" -> Res.string.paid_fee_declined
                "ActivationFeeAlreadyPaidError" -> Res.string.paid_fee_already_paid
                "ActivationRequirementsNotMetError" -> Res.string.paid_requirements_unmet
                "InvalidActivationStatusError" -> Res.string.paid_invalid_status
                else -> Res.string.paid_action_error
            }), color = MaterialTheme.colorScheme.error)
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.error -> {
                Text(stringResource(Res.string.paid_sales_error))
                Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
            }
            state.missing -> Text(stringResource(Res.string.paid_sales_missing))
            else -> state.data?.let { data ->
                Text(stringResource(when(data.status) {
                    "not_started" -> Res.string.paid_not_started
                    "pending" -> Res.string.paid_pending
                    "active" -> Res.string.paid_active
                    "suspended" -> Res.string.paid_suspended
                    else -> Res.string.paid_sales_title
                }))
                if (data.status !in listOf("not_started", "pending", "active", "suspended")) Text(data.status)
                if (data.isSimulated) com.example.biletflow.core.designsystem.DemoBanner(stringResource(Res.string.paid_simulated))
                Text("${stringResource(Res.string.paid_fee)}: ${data.feeAmount} ${data.currency}")
                data.checklist.forEach { (key,complete) ->
                    val label = when(key) {
                        "has_paid_ticket_type" -> stringResource(Res.string.paid_has_type)
                        "identity_verified" -> stringResource(Res.string.paid_identity)
                        "payout_account_connected" -> stringResource(Res.string.paid_account)
                        "terms_accepted" -> stringResource(Res.string.paid_terms)
                        "fee_paid" -> stringResource(Res.string.paid_fee)
                        else -> key
                    }
                    Text("$label — ${stringResource(if(complete) Res.string.paid_done else Res.string.paid_required)}")
                    if (!complete && key in listOf("identity_verified", "payout_account_connected")) {
                        TextButton(onClick = { vm.openRequirement(key) }, enabled = !state.activationBusy && !state.activationUnknown) { Text(label) }
                    }
                }
                data.activationReadiness()?.let { ready ->
                    Text(stringResource(if(ready) Res.string.paid_can_activate else Res.string.paid_cannot_activate))
                }
                data.activatedAt?.let { Text("${stringResource(Res.string.paid_activated_at)}: $it") }
                data.suspendedAt?.let { Text("${stringResource(Res.string.paid_suspended_at)}: $it") }
                data.suspensionReason?.let { Text(it) }
                listOf("accept-terms", "pay-fee", "activate").filter { data.allows(it) }.forEach { action ->
                    Button(onClick = { vm.askAction(action) }, enabled = !state.activationBusy && !state.activationUnknown) {
                        Text(stringResource(paidActionLabel(action)))
                    }
                }
            }
        }
    }
    state.confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = vm::dismissAction,
            title = { Text(stringResource(paidActionLabel(action))) },
            text = {
                Text(when (action) {
                    "accept-terms" -> stringResource(Res.string.paid_terms_simulation)
                    "pay-fee" -> "${stringResource(Res.string.paid_fee_confirm)} ${state.data?.feeAmount} ${state.data?.currency}"
                    else -> stringResource(Res.string.paid_activate_confirm)
                })
            },
            confirmButton = { TextButton(onClick = vm::confirmAction) { Text(stringResource(paidActionLabel(action))) } },
            dismissButton = { TextButton(onClick = vm::dismissAction) { Text(stringResource(Res.string.auth_close)) } },
        )
    }
}

private fun paidActionLabel(action: String) = when (action) {
    "accept-terms" -> Res.string.paid_terms
    "pay-fee" -> Res.string.paid_pay_fee
    else -> Res.string.paid_activate
}
