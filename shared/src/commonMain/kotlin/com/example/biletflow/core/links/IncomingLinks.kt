package com.example.biletflow.core.links

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An event page link of the website: `https://<web host>/events/{event_id}` with an optional
 * `?campaign=C-...` (the `link` of a Campaign QR, built by backend campaign_service as
 * `{FRONTEND_URL}/events/{event_id}?campaign={qr_token}`).
 */
data class EventLink(val eventId: Int, val campaignToken: String?)

private val eventPath = Regex("^/events/([0-9]{1,9})/?$")

/** Parses an incoming link; null for another host, scheme or path (such links are ignored). */
fun parseEventLink(text: String, webHost: String): EventLink? {
    val host = webHost.trim().lowercase()
    if (host.isEmpty()) return null
    val trimmed = text.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    if (url.host.lowercase() != host) return null
    val id = eventPath.find(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return EventLink(id, url.parameters["campaign"]?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * The single entry point for links that open the app. Platforms call [deliver] once per real
 * transition (Android: first onCreate and onNewIntent; iOS: onOpenURL); App takes each link once
 * with [take], after the session is restored. A link is never handed out twice, so
 * GET /campaign-links/{token} (which counts an open) runs at most once per transition.
 */
object IncomingLinks {
    private val pendingLink = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = pendingLink.asStateFlow()

    fun deliver(url: String?) {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        pendingLink.value = value
    }

    /** Returns the pending link and clears it, or null when there is none. */
    fun take(): String? {
        while (true) {
            val current = pendingLink.value ?: return null
            if (pendingLink.compareAndSet(current, null)) return current
        }
    }
}
