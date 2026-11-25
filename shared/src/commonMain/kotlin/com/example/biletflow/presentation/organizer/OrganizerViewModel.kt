package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.core.network.apiBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class OrganizerProfile(
    val id: Int,
    @kotlinx.serialization.SerialName("user_id") val userId: Int,
    val status: String,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    @kotlinx.serialization.SerialName("rejection_reason") val rejectionReason: String? = null,
    @kotlinx.serialization.SerialName("legal_name") val legalName: String? = null,
    @kotlinx.serialization.SerialName("contact_phone") val contactPhone: String? = null,
    @kotlinx.serialization.SerialName("identity_verified_at") val identityVerifiedAt: String? = null,
    @kotlinx.serialization.SerialName("payout_account") val payoutAccount: OrganizerPayoutAccount? = null,
)

@Serializable
data class OrganizerPayoutAccount(
    @kotlinx.serialization.SerialName("holder_name") val holderName: String,
    @kotlinx.serialization.SerialName("bank_name") val bankName: String,
    @kotlinx.serialization.SerialName("iban_last4") val ibanLast4: String,
    @kotlinx.serialization.SerialName("is_simulated") val isSimulated: Boolean,
)
data class OrganizerState(val loading: Boolean = false, val profile: OrganizerProfile? = null,
    val absent: Boolean = false, val error: Boolean = false, val unauthorized: Boolean = false,
    val form: Boolean = false, val editName: Boolean = false, val displayName: String = "",
    val nameError: String? = null, val nameSaved: Boolean = false)

class OrganizerViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl, private val onApplied: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(OrganizerState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var sequence = 0
    init { viewModelScope.launch { session.tokens.collect {
        if (generation != session.generation) {
            generation = session.generation
            sequence++
            mutable.value = OrganizerState()
        }
    } } }
    fun openForm() { mutable.value = mutable.value.copy(form = true, editName = false, error = false, displayName = "", nameError = null, nameSaved = false) }
    /** Edits the public name of an existing profile (PATCH /organizers/me). */
    fun openNameEditor(current: String?) { mutable.value = mutable.value.copy(form = true, editName = true, error = false, displayName = current.orEmpty(), nameError = null, nameSaved = false) }
    fun updateName(value: String) { if (!mutable.value.loading) mutable.value = mutable.value.copy(displayName = value, nameError = null, nameSaved = false) }
    private fun nameValid(name: String) = name.isEmpty() || name.length in 2..100
    fun closeForm() { if (!mutable.value.loading) mutable.value = mutable.value.copy(form = false) }
    fun load() = run(false)
    fun apply() {
        if (mutable.value.loading) return
        if (!nameValid(mutable.value.displayName.trim())) { mutable.value = mutable.value.copy(nameError = "length"); return }
        run(true)
    }
    /** PATCH /organizers/me {display_name: 2-100 | null}; an empty field clears the name (event pages then show the legal name). */
    fun saveName() {
        val s = mutable.value
        val name = s.displayName.trim()
        if (s.loading || session.tokens.value == null) return
        if (!nameValid(name)) { mutable.value = s.copy(nameError = "length"); return }
        val expected = session.generation; val requestId = ++sequence
        mutable.value = s.copy(loading = true, nameError = null, nameSaved = false)
        viewModelScope.launch {
            try {
                val profile = requests.request { token ->
                    val response = client.patch("$baseUrl/organizers/me") {
                        bearerAuth(token); contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { if (name.isEmpty()) put("display_name", JsonNull) else put("display_name", name) })
                    }
                    if (response.status.value == 200) response.body<OrganizerProfile>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Organizer request failed")
                    }
                }
                if (expected != session.generation || requestId != sequence) return@launch
                mutable.value = mutable.value.copy(loading = false, profile = profile, displayName = profile.displayName.orEmpty(), nameSaved = true)
                onApplied()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(loading = false, nameError = when {
                    api?.statusCode == 422 -> "length"
                    api == null || api.statusCode >= 500 -> "unknown"
                    else -> "HTTP ${api.statusCode}: ${api.errorCode ?: ""}"
                })
            }
        }
    }
    private fun run(apply: Boolean) {
        if (session.tokens.value == null || mutable.value.loading) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = mutable.value.copy(loading = true, error = false, unauthorized = false)
        viewModelScope.launch {
            try {
                val profile = requests.request { token ->
                    val applyName = mutable.value.displayName.trim()
                    val response = if (apply) client.post("$baseUrl/organizers/apply") {
                            bearerAuth(token)
                            if (applyName.isNotEmpty()) { contentType(ContentType.Application.Json); setBody(buildJsonObject { put("display_name", applyName) }) }
                        }
                        else client.get("$baseUrl/organizers/me") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<OrganizerProfile>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.content, "Organizer request failed")
                    }
                }
                if (expected == session.generation && requestId == sequence)
                    mutable.value = OrganizerState(profile = profile)
                if(apply && expected == session.generation && requestId == sequence) onApplied()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                if (apply && api?.errorCode == "OrganizerProfileAlreadyExistsError") {
                    onApplied()
                    mutable.value = mutable.value.copy(loading = false, form = false)
                    load()
                } else if (!apply && api?.statusCode == 404 && api.errorCode == "OrganizerNotFoundError") {
                    mutable.value = OrganizerState(absent = true)
                } else mutable.value = mutable.value.copy(loading = false, error = true, unauthorized = api?.statusCode == 401)
            }
        }
    }
}
