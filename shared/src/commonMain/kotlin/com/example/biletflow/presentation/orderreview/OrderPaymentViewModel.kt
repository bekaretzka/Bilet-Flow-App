package com.example.biletflow.presentation.orderreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.repositories.OrdersRepository
import com.example.biletflow.domain.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class PaymentError { Declined, Expired, Unauthorized, UnknownResult, StatusCheckFailed, Server }

data class OrderPaymentUiState(
    val orderId: Int? = null,
    val isPaying: Boolean = false,
    val isCheckingStatus: Boolean = false,
    val error: PaymentError? = null,
    val updatedOrder: Order? = null,
)

class OrderPaymentViewModel(
    private val repository: OrdersRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderPaymentUiState())
    val state: StateFlow<OrderPaymentUiState> = _state.asStateFlow()

    fun startOrder(order: Order) {
        if (_state.value.orderId == order.id) return
        _state.value = OrderPaymentUiState(orderId = order.id)
    }

    fun pay(order: Order) {
        val current = _state.value
        if (current.orderId != order.id || current.isPaying || current.isCheckingStatus ||
            current.error == PaymentError.UnknownResult || !orderCanBePaid(order)
        ) return
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = current.copy(error = PaymentError.Unauthorized)
            return
        }
        _state.value = current.copy(isPaying = true, error = null, updatedOrder = null)
        viewModelScope.launch {
            try {
                val updated = repository.payOrder(order.id, token)
                _state.value = _state.value.copy(
                    isPaying = false,
                    error = paymentErrorFor(updated),
                    updatedOrder = updated,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val apiError = throwable as? OrdersApiException
                val error = when {
                    apiError?.statusCode == 401 -> PaymentError.Unauthorized
                    apiError?.statusCode == 402 -> PaymentError.Declined
                    apiError?.errorCode == "OrderExpiredError" -> PaymentError.Expired
                    apiError == null -> PaymentError.UnknownResult
                    else -> PaymentError.Server
                }
                if (error == PaymentError.Unauthorized) sessionStore.clear()
                _state.value = _state.value.copy(isPaying = false, error = error)
            }
        }
    }

    fun checkStatus(orderId: Int) {
        val current = _state.value
        if (current.orderId != orderId || current.isPaying || current.isCheckingStatus) return
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = current.copy(error = PaymentError.Unauthorized)
            return
        }
        _state.value = current.copy(isCheckingStatus = true, updatedOrder = null)
        viewModelScope.launch {
            try {
                val updated = repository.getOrder(orderId, token)
                _state.value = _state.value.copy(
                    isCheckingStatus = false,
                    error = paymentErrorFor(updated),
                    updatedOrder = updated,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val apiError = throwable as? OrdersApiException
                val error = if (apiError?.statusCode == 401) {
                    sessionStore.clear()
                    PaymentError.Unauthorized
                } else {
                    PaymentError.StatusCheckFailed
                }
                _state.value = _state.value.copy(isCheckingStatus = false, error = error)
            }
        }
    }

    fun clearAfterLogin() {
        if (_state.value.error == PaymentError.Unauthorized) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun consumeUpdatedOrder() {
        _state.value = _state.value.copy(updatedOrder = null)
    }

    fun reset() {
        _state.value = OrderPaymentUiState()
    }
}

fun orderCanBePaid(order: Order, now: Instant = Clock.System.now()): Boolean {
    if (!order.status.equals("pending", ignoreCase = true)) return false
    val expiresAt = order.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return expiresAt == null || now < expiresAt
}

private fun paymentErrorFor(order: Order): PaymentError? = when {
    order.status.equals("expired", ignoreCase = true) -> PaymentError.Expired
    order.status.equals("pending", ignoreCase = true) && !orderCanBePaid(order) -> PaymentError.Expired
    else -> null
}
