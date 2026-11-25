package com.example.biletflow.presentation.orderreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.models.OrderPreview
import com.example.biletflow.domain.repositories.OrdersRepository
import com.example.biletflow.domain.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren

enum class OrderCreationError { InvalidName, Unauthorized, Conflict, UnknownResult, Server, Promo }

val promoErrorCodes = setOf("PromoCodeNotFoundError", "PromoCodeNotActiveError", "PromoCodeUsedUpError", "PromoCodeNotApplicableError")

/** Exactly one name per ticket; blank names fall back to buyer_name. */
fun withAttendeeNames(items: List<CreateOrderItem>, names: Map<Int, List<String>>, buyerName: String): List<CreateOrderItem> =
    items.map { item ->
        val entered = names[item.ticketTypeId].orEmpty()
        item.copy(attendeeNames = List(item.quantity) { index -> entered.getOrNull(index)?.trim().orEmpty().ifEmpty { buyerName }.take(200) })
    }

data class OrderCreationUiState(
    val buyerName: String = "",
    val isSubmitting: Boolean = false,
    val error: OrderCreationError? = null,
    val conflictCode: String? = null,
    val createdOrder: Order? = null,
    val promoCode: String = "",
    val previewing: Boolean = false,
    val preview: OrderPreview? = null,
    /** Items and code the preview was computed for; any change invalidates it. */
    val previewFor: Pair<List<CreateOrderItem>, String>? = null,
    val promoError: String? = null,
    val attendeeNames: Map<Int, List<String>> = emptyMap(),
) {
    fun previewValid(items: List<CreateOrderItem>) = preview != null && previewFor == (items to promoCode.trim().uppercase())
}

class OrderCreationViewModel(
    private val repository: OrdersRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderCreationUiState())
    val state: StateFlow<OrderCreationUiState> = _state.asStateFlow()
    private var previewSequence = 0

    fun updatePromoCode(value: String) {
        if (_state.value.isSubmitting) return
        previewSequence++
        _state.value = _state.value.copy(promoCode = value.take(32), preview = null, previewFor = null, previewing = false, promoError = null,
            error = if (_state.value.error == OrderCreationError.Promo) null else _state.value.error)
    }

    /** Prefill from a Campaign QR link; does not call preview by itself. */
    fun prefillPromoCode(code: String) { if (_state.value.createdOrder == null) updatePromoCode(code) }

    fun updateAttendeeName(ticketTypeId: Int, index: Int, value: String) {
        if (_state.value.isSubmitting) return
        val current = _state.value.attendeeNames[ticketTypeId].orEmpty().toMutableList()
        while (current.size <= index) current += ""
        current[index] = value.take(200)
        _state.value = _state.value.copy(attendeeNames = _state.value.attendeeNames + (ticketTypeId to current))
    }

    fun applyPromo(eventId: Int, items: List<CreateOrderItem>) {
        val current = _state.value
        if (current.previewing || current.isSubmitting || items.isEmpty()) return
        val code = current.promoCode.trim().uppercase()
        val token = sessionStore.tokens.value?.accessToken ?: run { _state.value = current.copy(error = OrderCreationError.Unauthorized); return }
        val generation = sessionStore.generation
        val sequence = ++previewSequence
        val plain = items.map { it.copy(attendeeNames = null) }
        _state.value = current.copy(previewing = true, promoError = null, preview = null, previewFor = null)
        viewModelScope.launch {
            try {
                val preview = repository.previewOrder(eventId, plain, code.ifEmpty { null }, token)
                if (generation == sessionStore.generation && sequence == previewSequence)
                    _state.value = _state.value.copy(previewing = false, preview = preview, previewFor = plain to code)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                if (generation != sessionStore.generation || sequence != previewSequence) return@launch
                val api = throwable as? OrdersApiException
                _state.value = _state.value.copy(previewing = false,
                    promoError = api?.errorCode?.takeIf { it in promoErrorCodes } ?: api?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network")
            }
        }
    }

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

        val promoCode = current.promoCode.trim().uppercase().ifEmpty { null }
        val namedItems = withAttendeeNames(items, current.attendeeNames, buyerName)
        _state.value = current.copy(isSubmitting = true, error = null, conflictCode = null, promoError = null)
        viewModelScope.launch {
            try {
                val order = repository.createOrder(
                    eventId = eventId,
                    buyerName = buyerName,
                    items = namedItems,
                    accessToken = accessToken,
                    promoCode = promoCode,
                )
                _state.value = _state.value.copy(
                    buyerName = buyerName,
                    isSubmitting = false,
                    createdOrder = order,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                val apiError = throwable as? OrdersApiException
                val error = when {
                    apiError?.errorCode in promoErrorCodes -> OrderCreationError.Promo
                    else -> when (apiError?.statusCode) {
                    401 -> OrderCreationError.Unauthorized
                    409 -> OrderCreationError.Conflict
                    null -> OrderCreationError.UnknownResult
                    else -> OrderCreationError.Server
                } }
                if (error == OrderCreationError.Unauthorized) sessionStore.clear()
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = error,
                    conflictCode = apiError?.errorCode,
                    promoError = if (error == OrderCreationError.Promo) apiError?.errorCode else _state.value.promoError,
                    preview = if (error == OrderCreationError.Promo) null else _state.value.preview,
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, conflictCode = null)
    }

    fun reset() {
        previewSequence++
        viewModelScope.coroutineContext.cancelChildren()
        _state.value = OrderCreationUiState()
    }
}
