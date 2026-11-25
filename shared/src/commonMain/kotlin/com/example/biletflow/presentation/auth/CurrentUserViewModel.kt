package com.example.biletflow.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.CurrentUser
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class CurrentUserState(val loading: Boolean = false,val user: CurrentUser? = null,val error: String? = null,
    val resending: Boolean = false,val resendSent: Boolean = false,val resendError: String? = null)
class CurrentUserViewModel(private val client: HttpClient,private val requests: SessionOrdersRepository,
    private val session: SessionStore,private val baseUrl: String = apiBaseUrl): ViewModel() {
    private val mutable=MutableStateFlow(CurrentUserState())
    val state=mutable.asStateFlow()
    private var generation = -1L
    private var job: Job? = null
    private var sequence=0
    init { viewModelScope.launch { session.tokens.collect {
        if(generation != session.generation) {
            generation=session.generation; sequence++; job?.cancel(); mutable.value=CurrentUserState()
            if(it != null) load()
        }
    } } }
    fun refreshAfterChange() {
        job?.cancel()
        mutable.value=mutable.value.copy(loading=false)
        load()
    }
    /** POST /auth/resend-verification. 409 EmailAlreadyVerifiedError re-reads /auth/me, which hides the banner. */
    fun resendVerification() {
        if(session.tokens.value == null || mutable.value.resending) return
        val expected=session.generation
        mutable.value=mutable.value.copy(resending=true,resendError=null,resendSent=false)
        viewModelScope.launch {
            try {
                requests.request { token ->
                    val response=client.post("$baseUrl/auth/resend-verification") { bearerAuth(token) }
                    if(response.status.value != 200) {
                        val body=runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value,(body?.get("error") as? JsonPrimitive)?.contentOrNull,"HTTP ${response.status.value}")
                    }
                }
                if(expected == session.generation) mutable.value=mutable.value.copy(resending=false,resendSent=true)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation) return@launch
                val api=error as? OrdersApiException
                if(api?.errorCode == "EmailAlreadyVerifiedError") { mutable.value=mutable.value.copy(resending=false); refreshAfterChange() }
                else mutable.value=mutable.value.copy(resending=false,resendError=api?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network")
            }
        }
    }
    fun load() {
        if(session.tokens.value == null || mutable.value.loading) return
        val expected=session.generation; val requestId=++sequence
        mutable.value=mutable.value.copy(loading=true,error=null)
        job=viewModelScope.launch {
            try {
                val result=requests.request { token ->
                    val response=client.get("$baseUrl/auth/me") { bearerAuth(token) }
                    if(response.status.value != 200) {
                        val body=runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value,(body?.get("error") as? JsonPrimitive)?.contentOrNull,
                            (body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${response.status.value}")
                    }
                    response.body<CurrentUser>()
                }
                if(expected != session.generation || requestId != sequence) return@launch
                session.setUser(result,expected)
                mutable.value=CurrentUserState(user=result)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation || requestId != sequence) return@launch
                mutable.value=mutable.value.copy(loading=false,error=(error as? OrdersApiException)?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network")
            }
        }
    }
}
