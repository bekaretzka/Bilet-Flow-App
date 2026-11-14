package com.example.biletflow.presentation.orderreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.domain.repositories.OrdersRepository
import com.example.biletflow.domain.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TicketQrError { NotFound, Unauthorized, Network }

data class TicketQrUiState(
    val ticketId: Int? = null,
    val isLoading: Boolean = false,
    val pngBytes: ByteArray? = null,
    val error: TicketQrError? = null,
)

class TicketQrViewModel(
    private val repository: OrdersRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TicketQrUiState())
    val state: StateFlow<TicketQrUiState> = _state.asStateFlow()

    fun load(ticketId: Int) {
        _state.value = TicketQrUiState(ticketId = ticketId, isLoading = true)
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = TicketQrUiState(ticketId = ticketId, error = TicketQrError.Unauthorized)
            return
        }
        viewModelScope.launch {
            try {
                val bytes = repository.getTicketQr(ticketId, token)
                if (_state.value.ticketId == ticketId) {
                    _state.value = TicketQrUiState(ticketId = ticketId, pngBytes = bytes)
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                if (_state.value.ticketId != ticketId) return@launch
                val apiError = throwable as? OrdersApiException
                val error = when (apiError?.statusCode) {
                    401 -> TicketQrError.Unauthorized
                    404 -> TicketQrError.NotFound
                    else -> TicketQrError.Network
                }
                if (error == TicketQrError.Unauthorized) sessionStore.clear()
                _state.value = TicketQrUiState(ticketId = ticketId, error = error)
            }
        }
    }

    fun clearUnauthorizedAfterLogin() {
        val ticketId = _state.value.ticketId ?: return
        if (_state.value.error == TicketQrError.Unauthorized) load(ticketId)
    }

    fun reset() {
        _state.value = TicketQrUiState()
    }
}
