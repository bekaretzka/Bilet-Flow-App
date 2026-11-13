package com.example.biletflow.presentation.orderreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.repositories.OrdersRepository
import com.example.biletflow.domain.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OrderCreationError { InvalidName, Unauthorized, Conflict, UnknownResult, Server }

data class OrderCreationUiState(
    val buyerName: String = "",
    val isSubmitting: Boolean = false,
    val error: OrderCreationError? = null,
    val conflictCode: String? = null,
    val createdOrder: Order? = null,
)

class OrderCreationViewModel(
    private val repository: OrdersRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderCreationUiState())
    val state: StateFlow<OrderCreationUiState> = _state.asStateFlow()

    fun updateBuyerName(value: String) {
        _state.value = _state.value.copy(
            buyerName = value,
            error = if (_state.value.error == OrderCreationError.InvalidName) null else _state.value.error,
        )
    }

    fun submit(eventId: Int, items: List<CreateOrderItem>) {
        val current = _state.value
        if (current.isSubmitting || current.createdOrder != null) return

        val buyerName = current.buyerName.trim()
        if (buyerName.length !in 1..200) {
            _state.value = current.copy(error = OrderCreationError.InvalidName)
            return
        }
        val accessToken = sessionStore.tokens.value?.accessToken
        if (accessToken == null) {
            _state.value = current.copy(error = OrderCreationError.Unauthorized)
            return
        }

        _state.value = current.copy(isSubmitting = true, error = null, conflictCode = null)
        viewModelScope.launch {
            try {
                val order = repository.createOrder(
                    eventId = eventId,
                    buyerName = buyerName,
                    items = items,
                    accessToken = accessToken,
                )
                _state.value = _state.value.copy(
                    buyerName = buyerName,
                    isSubmitting = false,
                    createdOrder = order,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val apiError = throwable as? OrdersApiException
                val error = when (apiError?.statusCode) {
                    401 -> OrderCreationError.Unauthorized
                    409 -> OrderCreationError.Conflict
                    null -> OrderCreationError.UnknownResult
                    else -> OrderCreationError.Server
                }
                if (error == OrderCreationError.Unauthorized) sessionStore.clear()
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = error,
                    conflictCode = apiError?.errorCode,
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, conflictCode = null)
    }

    fun reset() {
        _state.value = OrderCreationUiState()
    }
}
