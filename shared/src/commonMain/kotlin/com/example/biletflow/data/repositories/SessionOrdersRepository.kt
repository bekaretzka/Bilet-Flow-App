package com.example.biletflow.data.repositories

import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.repositories.*
import com.example.biletflow.domain.session.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only an explicit HTTP 401 permits a single replay, including mutating requests. */
class SessionOrdersRepository(
    private val delegate: OrdersRepository,
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) : OrdersRepository {
    private val mutex = Mutex()
    private var flight: Deferred<String>? = null
    private var flightGeneration = -1L
    private val refreshScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private suspend fun refreshed(failedToken: String, generation: Long): String {
        val task = mutex.withLock {
            ensureSession(generation)
            val current = requireNotNull(session.tokens.value)
            if (current.accessToken != failedToken) return current.accessToken
            if (flightGeneration == generation && flight != null) return@withLock requireNotNull(flight)
            flightGeneration = generation
            refreshScope.async(start = CoroutineStart.LAZY) {
                try {
                    val tokens = auth.refresh(current.refreshToken)
                    ensureSession(generation)
                    session.rotateTokens(tokens)
                    tokens.accessToken
                } catch (error: Exception) {
                    ensureSession(generation)
                    if (error is CancellationException) throw error
                    if(error is AuthApiException && error.statusCode == 403 && error.errorCode == "UserSuspendedError") {
                        session.suspendSession(generation)
                        throw OrdersApiException(403,error.errorCode,error.message)
                    }
                    if (error is AuthApiException && error.statusCode == 401) {
                        session.clear()
                        throw OrdersApiException(401, null, "Session expired")
                    }
                    throw OrdersApiException(503, "RefreshUnavailable", "Couldn't refresh session. Try again.")
                }
            }.also { flight = it; it.start() }
        }
        return task.await()
    }

    private fun ensureSession(generation: Long) {
        if (session.generation != generation || session.tokens.value == null) {
            throw CancellationException("Session changed")
        }
    }

    suspend fun <T> request(call: suspend (String) -> T): T {
        mutex.withLock {
            if (flight?.isCompleted == true) flight = null
        }
        val generation = session.generation
        val token = session.tokens.value?.accessToken ?: throw OrdersApiException(401, null, "Login required")
        try {
            return call(token).also { ensureSession(generation) }
        } catch (error: Exception) {
            ensureSession(generation)
            rejectSuspended(error,generation)
            if (httpStatus(error) != 401) throw error
        }
        val updated = refreshed(token, generation)
        try {
            return call(updated).also { ensureSession(generation) }
        } catch (error: Exception) {
            ensureSession(generation)
            rejectSuspended(error,generation)
            if (httpStatus(error) == 401) session.clear()
            throw error
        }
    }
    private fun httpStatus(error: Exception): Int? = when(error) {
        is OrdersApiException -> error.statusCode
        is AuthApiException -> error.statusCode
        is SupportApiException -> error.statusCode
        is CampaignApiException -> error.status
        else -> null
    }
    private fun rejectSuspended(error: Exception,generation: Long) {
        val code = when(error) {
            is OrdersApiException -> error.errorCode
            is AuthApiException -> error.errorCode
            is SupportApiException -> error.code
            is CampaignApiException -> error.code
            else -> null
        }
        if(httpStatus(error) == 403 && code == "UserSuspendedError") session.suspendSession(generation)
    }

    override suspend fun getMyOrders(accessToken: String) = request { delegate.getMyOrders(it) }
    override suspend fun getOrder(orderId: Int, accessToken: String) = request { delegate.getOrder(orderId, it) }
    override suspend fun getTicketQr(ticketId: Int, accessToken: String) = request { delegate.getTicketQr(ticketId, it) }
    override suspend fun payOrder(orderId: Int, accessToken: String) = request { delegate.payOrder(orderId, it) }
    override suspend fun cancelOrder(orderId: Int, accessToken: String) = request { delegate.cancelOrder(orderId, it) }
    override suspend fun refundOrder(orderId: Int, reason: String, accessToken: String) = request { delegate.refundOrder(orderId, reason, it) }
    override suspend fun createOrder(eventId: Int, buyerName: String, items: List<CreateOrderItem>, accessToken: String, promoCode: String?) =
        request { delegate.createOrder(eventId, buyerName, items, it, promoCode) }
    override suspend fun previewOrder(eventId: Int, items: List<CreateOrderItem>, promoCode: String?, accessToken: String) =
        request { delegate.previewOrder(eventId, items, promoCode, it) }
}
