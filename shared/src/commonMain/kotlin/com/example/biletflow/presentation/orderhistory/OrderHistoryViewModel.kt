package com.example.biletflow.presentation.orderhistory

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
import kotlinx.datetime.Instant

enum class OrderHistoryError { Unauthorized, Network }

data class OrderHistoryUiState(
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val orders: List<Order> = emptyList(),
    val error: OrderHistoryError? = null,
    val openingOrderId: Int? = null,
    val openedOrder: Order? = null,
    val orderLoadFailed: Boolean = false,
    val failedOrderId: Int? = null,
)

class OrderHistoryViewModel(
    private val repository: OrdersRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderHistoryUiState())
    val state: StateFlow<OrderHistoryUiState> = _state.asStateFlow()
    private var sessionKey: String? = null
    private var generation = sessionStore.generation

    init {
        viewModelScope.launch {
            sessionStore.tokens.collect { tokens ->
                val newKey = tokens?.accessToken
                if (generation == sessionStore.generation && newKey != null) {
                    sessionKey = newKey
                    return@collect
                }
                generation = sessionStore.generation
                if (newKey == sessionKey) return@collect
                sessionKey = newKey
                val active = _state.value.isActive
                _state.value = OrderHistoryUiState(
                    isActive = active,
                    error = if (active && newKey == null) OrderHistoryError.Unauthorized else null,
                )
                if (active && newKey != null) load(newKey)
            }
        }
    }

    fun open() {
        val token = sessionStore.tokens.value?.accessToken
        _state.value = OrderHistoryUiState(
            isActive = true,
            error = if (token == null) OrderHistoryError.Unauthorized else null,
        )
        if (token != null) load(token)
    }

    fun close() {
        _state.value = OrderHistoryUiState()
    }

    fun retry() {
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = _state.value.copy(error = OrderHistoryError.Unauthorized)
        } else {
            load(token)
        }
    }

    fun refreshAfterOrder() {
        consumeOpenedOrder()
        retry()
    }

    fun openOrder(orderId: Int) {
        val requestGeneration = sessionStore.generation
        val token = sessionStore.tokens.value?.accessToken
        if (token == null) {
            _state.value = _state.value.copy(error = OrderHistoryError.Unauthorized)
            return
        }
        _state.value = _state.value.copy(
            openingOrderId = orderId,
            openedOrder = null,
            orderLoadFailed = false,
            failedOrderId = null,
        )
        viewModelScope.launch {
            try {
                val order = repository.getOrder(orderId, token)
                if (requestGeneration != sessionStore.generation) return@launch
                if (_state.value.openingOrderId == orderId && sessionKey == token) {
                    _state.value = _state.value.copy(
                        openingOrderId = null,
                        openedOrder = order,
                    )
                }
            } catch (throwable: Throwable) {
                if (requestGeneration != sessionStore.generation) return@launch
                if (throwable is CancellationException) throw throwable
                if (_state.value.openingOrderId != orderId || sessionKey != token) return@launch
                val unauthorized = (throwable as? OrdersApiException)?.statusCode == 401
                if (unauthorized) sessionStore.clear()
                _state.value = _state.value.copy(
                    openingOrderId = null,
                    error = if (unauthorized) OrderHistoryError.Unauthorized else _state.value.error,
                    orderLoadFailed = !unauthorized,
                    failedOrderId = if (unauthorized) null else orderId,
                )
            }
        }
    }

    fun consumeOpenedOrder() {
        _state.value = _state.value.copy(
            openedOrder = null,
            openingOrderId = null,
            orderLoadFailed = false,
            failedOrderId = null,
        )
    }

    private fun load(token: String) {
        val requestGeneration = sessionStore.generation
        _state.value = _state.value.copy(isLoading = true, orders = emptyList(), error = null)
        viewModelScope.launch {
            try {
                val orders = repository.getMyOrders(token).sortedWith(
                    compareByDescending<Order> {
                        runCatching { Instant.parse(it.createdAt) }.getOrNull()
                    }.thenByDescending { it.createdAt },
                )
                if (requestGeneration != sessionStore.generation) return@launch
                if (sessionKey == token && _state.value.isActive) {
                    _state.value = _state.value.copy(isLoading = false, orders = orders, error = null)
                }
            } catch (throwable: Throwable) {
                if (requestGeneration != sessionStore.generation) return@launch
                if (throwable is CancellationException) throw throwable
                if (sessionKey != token || !_state.value.isActive) return@launch
                val unauthorized = (throwable as? OrdersApiException)?.statusCode == 401
                if (unauthorized) sessionStore.clear()
                _state.value = _state.value.copy(
                    isLoading = false,
                    orders = emptyList(),
                    error = if (unauthorized) OrderHistoryError.Unauthorized else OrderHistoryError.Network,
                )
            }
        }
    }
}
