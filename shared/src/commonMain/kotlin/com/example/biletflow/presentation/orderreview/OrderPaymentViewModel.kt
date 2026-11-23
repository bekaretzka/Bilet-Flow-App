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
import kotlinx.coroutines.cancelChildren
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class PaymentError {
    Declined, Expired, Unauthorized, UnknownResult, CancellationUnknown,
    StatusCheckFailed, Server, CancellationServer,
}

data class OrderPaymentUiState(
    val orderId: Int? = null,
    val isPaying: Boolean = false,
    val isCancelling: Boolean = false,
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
        if (current.orderId != order.id || current.isPaying || current.isCancelling ||
            current.isCheckingStatus || current.hasUnknownResult() || !orderCanBePaid(order)
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

    fun cancel(order: Order) {
        val current = _state.value
        if (current.orderId != order.id || current.isPaying || current.isCancelling ||
            current.isCheckingStatus || current.hasUnknownResult() || !orderCanBeCancelled(order)
        ) return
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = current.copy(error = PaymentError.Unauthorized)
            return
        }
        _state.value = current.copy(isCancelling = true, error = null, updatedOrder = null)
        viewModelScope.launch {
            try {
                val updated = repository.cancelOrder(order.id, token)
                _state.value = _state.value.copy(
                    isCancelling = false,
                    error = paymentErrorFor(updated),
                    updatedOrder = updated,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val apiError = throwable as? OrdersApiException
                when {
                    apiError?.statusCode == 401 -> {
                        sessionStore.clear()
                        _state.value = _state.value.copy(
                            isCancelling = false,
                            error = PaymentError.Unauthorized,
                        )
                    }
                    apiError?.errorCode == "OrderNotCancellableError" -> {
                        _state.value = _state.value.copy(isCancelling = false, error = null)
                        checkStatus(order.id)
                    }
                    apiError == null -> _state.value = _state.value.copy(
                        isCancelling = false,
                        error = PaymentError.CancellationUnknown,
                    )
                    else -> _state.value = _state.value.copy(
                        isCancelling = false,
                        error = PaymentError.CancellationServer,
                    )
                }
            }
        }
    }

    fun checkStatus(orderId: Int) {
        val current = _state.value
        if (current.orderId != orderId || current.isPaying || current.isCancelling || current.isCheckingStatus) return
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
            val orderId = _state.value.orderId
            _state.value = _state.value.copy(error = null)
            orderId?.let(::checkStatus)
        }
    }

    fun consumeUpdatedOrder() {
        _state.value = _state.value.copy(updatedOrder = null)
    }

    fun reset() {
        viewModelScope.coroutineContext.cancelChildren()
        _state.value = OrderPaymentUiState()
    }
}

fun orderCanBePaid(order: Order, now: Instant = Clock.System.now()): Boolean {
    if (!order.status.equals("pending", ignoreCase = true)) return false
    val expiresAt = order.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return expiresAt == null || now < expiresAt
}

fun orderCanBeCancelled(order: Order): Boolean =
    order.status.equals("pending", ignoreCase = true) ||
        (order.status.equals("confirmed", ignoreCase = true) && order.totalAmount == 0L)

private fun OrderPaymentUiState.hasUnknownResult(): Boolean =
    error == PaymentError.UnknownResult || error == PaymentError.CancellationUnknown

private fun paymentErrorFor(order: Order): PaymentError? = when {
    order.status.equals("expired", ignoreCase = true) -> PaymentError.Expired
    order.status.equals("pending", ignoreCase = true) && !orderCanBePaid(order) -> PaymentError.Expired
    else -> null
}
