package com.example.biletflow.presentation.documents

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.platform.rememberFileSharer
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.data.repositories.SessionOrdersRepository
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.insightsError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Authorized binary downloads (ticket/order PDF, Campaign QR PNG) shared through the platform file sharer. */
class DocumentDownloader(private val client: HttpClient, private val requests: SessionOrdersRepository,
    val session: SessionStore, private val baseUrl: String) {
    suspend fun fetch(path: String, mimeType: String): ByteArray = requests.request { token ->
        val response = client.get("$baseUrl$path") { bearerAuth(token) }
        if (response.status.value != 200) throw insightsError(response.status.value, response.bodyAsText())
        val type = response.headers[HttpHeaders.ContentType].orEmpty()
        if (!type.startsWith(mimeType, ignoreCase = true)) throw OrdersApiException(502, null, "Unexpected content type $type")
        response.body<ByteArray>()
    }
}

val LocalDocuments = staticCompositionLocalOf<DocumentDownloader?> { null }

/** One download per press; the button stays disabled until it completes. A result from another account is dropped. */
@Composable
fun DocumentButton(label: String, path: String, fileName: String, mimeType: String = "application/pdf") {
    val documents = LocalDocuments.current ?: return
    val sharer = rememberFileSharer()
    val scope = rememberCoroutineScope()
    var busy by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    Column {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; error = null
            val generation = documents.session.generation
            scope.launch {
                try {
                    val bytes = documents.fetch(path, mimeType)
                    if (generation == documents.session.generation && !sharer.share(bytes, fileName, mimeType)) error = "share"
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (generation == documents.session.generation) error = (failure as? OrdersApiException)?.let { it.errorCode ?: "HTTP ${it.statusCode}" } ?: "network"
                } finally { busy = false }
            }
        }) { if (busy) CircularProgressIndicator() else Text(label) }
        error?.let { code ->
            Text(when (code) {
                "TicketNotPrintableError" -> stringResource(Res.string.pdf_not_printable)
                "share" -> stringResource(Res.string.pdf_share_failed)
                "network" -> stringResource(Res.string.pdf_network)
                else -> "${stringResource(Res.string.pdf_failed)} ($code)"
            }, color = MaterialTheme.colorScheme.error)
        }
    }
}
