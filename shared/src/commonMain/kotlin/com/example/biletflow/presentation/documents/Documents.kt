package com.example.biletflow.presentation.documents

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.biletflow.core.designsystem.OutlinedButton
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
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
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
    fun publicClient(): HttpClient = client
    fun absolute(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    suspend fun <T> requestsCall(call: suspend (HttpClient, String, String) -> T): T = requests.request { token -> call(client, baseUrl, token) }
}

/** Authorized JSON POST used by small actions (event report). Returns the response text; never retried after a network failure. */
suspend fun DocumentDownloader.postJson(path: String, body: kotlinx.serialization.json.JsonObject, expected: Int): String = requestsCall { client, baseUrl, token ->
    val response = client.post("$baseUrl$path") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody(body)
    }
    val text = response.bodyAsText()
    if (response.status.value != expected) throw insightsError(response.status.value, text)
    text
}

val LocalDocuments = staticCompositionLocalOf<DocumentDownloader?> { null }

private val publicImageCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

/** Cover image from image_url: loaded without a token, decoded like the ticket QR PNG, cached in memory per URL. */
@Composable
fun PublicImage(url: String, description: String?, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val documents = LocalDocuments.current ?: return
    var image by remember(url) { mutableStateOf(publicImageCache[url]) }
    LaunchedEffect(url) {
        if (image != null) return@LaunchedEffect
        val loaded = runCatching {
            val response = documents.publicClient().get(documents.absolute(url))
            if (response.status.value == 200) response.body<ByteArray>().decodeToImageBitmap() else null
        }.getOrNull()
        if (loaded != null) { publicImageCache[url] = loaded; image = loaded }
    }
    image?.let { androidx.compose.foundation.Image(it, description, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
}

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

/** Loads an authorized PNG (e.g. Campaign QR) once per path and shows it; errors are shown inline. */
@Composable
fun RemotePngImage(path: String, description: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val documents = LocalDocuments.current ?: return
    var image by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        val generation = documents.session.generation
        try {
            val bytes = documents.fetch(path, "image/png")
            if (generation == documents.session.generation) image = runCatching { bytes.decodeToImageBitmap() }.getOrElse { failed = "png"; null }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (generation == documents.session.generation) failed = (failure as? OrdersApiException)?.let { it.errorCode ?: "HTTP ${it.statusCode}" } ?: "network"
        }
    }
    val bitmap = image
    when {
        bitmap != null -> androidx.compose.foundation.Image(bitmap, description, modifier)
        failed != null -> Text("${stringResource(Res.string.pdf_failed)} ($failed)", color = MaterialTheme.colorScheme.error)
        else -> CircularProgressIndicator()
    }
}
